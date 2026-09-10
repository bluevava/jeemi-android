"""Jeemi-Android maintenance entry point. Python standard library only."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = json.loads((ROOT / "scripts/toolchain.json").read_text(encoding="utf-8"))
WINDOWS = os.name == "nt"
EXE = ".exe" if WINDOWS else ""
ENGINE = ROOT / "engine"
AAR = ENGINE / "build/jeemi-engine.aar"
APK_DIR = ROOT / "app/build/outputs/apk/debug"


def run(args, *, cwd=ROOT, env=None, capture=False):
    args = [str(a) for a in args]
    print("> " + subprocess.list2cmdline(args), flush=True)
    return subprocess.run(args, cwd=cwd, env=env, check=True,
                          text=True, encoding="utf-8", errors="replace",
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.STDOUT if capture else None)


def sdk_path():
    candidates = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    local = ROOT / "local.properties"
    if local.exists():
        for line in local.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                candidates.append(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    if WINDOWS:
        candidates.append(str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk"))
    else:
        candidates.extend([str(Path.home() / "Android/Sdk"), str(Path.home() / "Library/Android/sdk")])
    for candidate in candidates:
        if candidate and (Path(candidate) / "platforms").is_dir():
            return Path(candidate)
    raise RuntimeError("Android SDK not found. Set ANDROID_HOME, then run doctor.")


def environment():
    env = os.environ.copy()
    sdk = sdk_path()
    java_home = env.get("JAVA_HOME")
    if not java_home:
        java = shutil.which("java")
        if not java:
            raise RuntimeError("JDK 21 not found. Set JAVA_HOME to Android Studio's jbr or JDK 21.")
        java_home = str(Path(java).resolve().parent.parent)
    env["JAVA_HOME"] = java_home
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_NDK_HOME"] = str(sdk / "ndk" / SPEC["ndk"])
    env["PATH"] = os.pathsep.join([str(Path(java_home) / "bin"), str(sdk / "platform-tools"), env.get("PATH", "")])
    return env


def doctor():
    env = environment()
    sdk = Path(env["ANDROID_HOME"])
    checks = {}
    for name, args in {"java": [str(Path(env["JAVA_HOME"]) / "bin" / ("java" + EXE)), "-version"],
                       "go": ["go", "version"], "adb": [str(sdk / "platform-tools" / ("adb" + EXE)), "version"]}.items():
        try:
            checks[name] = run(args, env=env, capture=True).stdout.strip()
        except (OSError, subprocess.CalledProcessError) as exc:
            checks[name] = f"MISSING: {exc}"
    required = {"platform": sdk / "platforms" / f"android-{SPEC['compileSdk']}" / "android.jar",
                "ndk": sdk / "ndk" / SPEC["ndk"] / "source.properties",
                "buildTools": sdk / "build-tools" / SPEC["buildTools"] / ("zipalign" + EXE),
                "wrapper": ROOT / "gradle/wrapper/gradle-wrapper.jar"}
    for name, path in required.items():
        checks[name] = str(path) if path.exists() else f"MISSING: {path}"
    report = {"host": platform.platform(), "python": platform.python_version(), "sdk": str(sdk),
              "javaHome": env["JAVA_HOME"], "policy": SPEC, "checks": checks}
    output = ROOT / "temp/environment.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if any(value.startswith("MISSING:") for value in checks.values()):
        raise RuntimeError("Required tools are missing; see temp/environment.json.")


def setup():
    sdk = sdk_path().as_posix().replace(":", "\\:")
    local = ROOT / "local.properties"
    lines = local.read_text(encoding="utf-8").splitlines() if local.exists() else []
    lines = [line for line in lines if not line.startswith("sdk.dir=")]
    local.write_text("\n".join(lines + [f"sdk.dir={sdk}"]) + "\n", encoding="utf-8")
    print("Configured local.properties. Global environment unchanged.")


def engine_fingerprint():
    digest = hashlib.sha256()
    sources = sorted(ENGINE.rglob("*.go")) + [ENGINE / "go.mod", ENGINE / "go.sum", ROOT / "scripts/toolchain.json", Path(__file__)]
    for path in sources:
        digest.update(path.relative_to(ROOT).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def engine_build():
    env = environment()
    ndk = Path(env["ANDROID_NDK_HOME"])
    if not (ndk / "source.properties").exists():
        raise RuntimeError(f"Install NDK {SPEC['ndk']} using Android Studio's SDK Manager.")
    fingerprint = engine_fingerprint()
    stamp = AAR.with_suffix(".json")
    if AAR.exists() and stamp.exists():
        saved = json.loads(stamp.read_text(encoding="utf-8"))
        if saved.get("inputs") == fingerprint and saved.get("sha256") == hashlib.sha256(AAR.read_bytes()).hexdigest():
            print("ARM64 + x86_64 engine AAR is current.")
            return
    tool_dir = ROOT / "temp/tools" / SPEC["gomobile"]
    tool_dir.mkdir(parents=True, exist_ok=True)
    env["GOBIN"] = str(tool_dir)
    env["PATH"] = str(tool_dir) + os.pathsep + env["PATH"]
    for command in ("gomobile", "gobind"):
        if not (tool_dir / (command + EXE)).exists():
            run(["go", "install", f"golang.org/x/mobile/cmd/{command}@{SPEC['gomobile']}"], cwd=ENGINE, env=env)
    run([tool_dir / ("gomobile" + EXE), "init"], cwd=ENGINE, env=env)
    AAR.parent.mkdir(parents=True, exist_ok=True)
    run([tool_dir / ("gomobile" + EXE), "bind", "-target=android/arm64,android/amd64", "-androidapi", str(SPEC["minSdk"]),
         "-ldflags=-extldflags=-Wl,-z,max-page-size=16384", "-o", AAR, "./mobile"], cwd=ENGINE, env=env)
    with zipfile.ZipFile(AAR) as archive:
        native = [name for name in archive.namelist() if name.endswith(".so")]
        if {name.split("/")[1] for name in native} != set(SPEC["abis"]):
            raise RuntimeError(f"Unexpected engine ABIs: {native}")
    stamp.write_text(json.dumps({"inputs": fingerprint, "sha256": hashlib.sha256(AAR.read_bytes()).hexdigest()}, indent=2) + "\n", encoding="utf-8")


def gradle(*tasks):
    setup()
    return run([ROOT / ("gradlew.bat" if WINDOWS else "gradlew"), *tasks, "--console", "plain"], env=environment())


def verify_apk(path, abi):
    with zipfile.ZipFile(path) as archive:
        libraries = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")]
        if not libraries or any(name.split("/")[1] != abi for name in libraries):
            raise RuntimeError(f"APK must contain only {abi} native libraries: {libraries}")
        core_spec = json.loads((ROOT / "resources/mihomo/manifest.json").read_text(encoding="utf-8"))
        core = next(item for item in core_spec["cores"] if item["abi"] == abi)
        core_name = f"lib/{abi}/libmihomo_exec.so"
        if hashlib.sha256(archive.read(core_name)).hexdigest() != core["sha256"]:
            raise RuntimeError("Bundled mihomo differs from the locked core.")
        if json.loads(archive.read("assets/mihomo.json")) != core_spec:
            raise RuntimeError("APK core provenance differs from the lock.")
        if core_spec.get("variant") == "jeemi-vpn-fd":
            for field, name in (("sourceLock", "mihomo-source.json"), ("patch", "mihomo-vpn-fd-patch.json")):
                if hashlib.sha256(archive.read("assets/" + name)).hexdigest() != core_spec[field + "Sha256"]:
                    raise RuntimeError("APK is missing its pinned core source or patch notice.")
        geo_path = ROOT / "resources/geodata/manifest.json"
        geo = json.loads(geo_path.read_text(encoding="utf-8"))
        if json.loads(archive.read("assets/geodata/manifest.json")) != geo:
            raise RuntimeError("APK GEO manifest differs from the locked snapshot.")
        for asset in geo["files"]:
            content = archive.read("assets/geodata/" + asset["file"])
            if len(content) != asset["bytes"] or hashlib.sha256(content).hexdigest() != asset["sha256"]:
                raise RuntimeError("APK contains an unexpected GEO asset.")
        # ELF64 program headers describe the load alignment independently of ZIP alignment.
        import struct
        for name in libraries:
            data = archive.read(name)
            if data[:5] != b"\x7fELF\x02" or data[5] != 1:
                raise RuntimeError(f"Expected little endian ELF64: {name}")
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            for index in range(count):
                header = offset + index * size
                kind = struct.unpack_from("<I", data, header)[0]
                alignment = struct.unpack_from("<Q", data, header + 48)[0]
                if kind == 1 and alignment < 16384:
                    raise RuntimeError(f"{name} has a LOAD segment below 16 KB alignment")
    run([sdk_path() / "build-tools" / SPEC["buildTools"] / ("zipalign" + EXE), "-c", "-P", "16", "4", path], capture=True)
    aapt = sdk_path() / "build-tools" / SPEC["buildTools"] / ("aapt" + EXE)
    configurations = run([aapt, "dump", "configurations", path], capture=True).stdout
    if "zh-rCN" not in configurations:
        raise RuntimeError("Simplified Chinese resources are missing from the APK.")
    metadata = run([aapt, "dump", "badging", path], capture=True).stdout
    if f"sdkVersion:'{SPEC['minSdk']}'" not in metadata or f"targetSdkVersion:'{SPEC['targetSdk']}'" not in metadata:
        raise RuntimeError("Packaged SDK requirements do not match the toolchain policy.")
    print(f"Verified {abi} APK, ELF LOAD alignment and ZIP alignment for 16 KB pages.")


def build_debug():
    gradle("assembleDebug")
    for abi in SPEC["abis"]:
        apk = APK_DIR / f"app-{abi}-debug.apk"
        verify_apk(apk, abi)
        label = "arm64" if abi == "arm64-v8a" else "amd64"
        target = ROOT / f"Bin/Jeemi-Android/Android-{label}/debug/Jeemi-Android-{label}-dev.apk"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(apk, target)
        target.with_suffix(".apk.sha256").write_text(hashlib.sha256(target.read_bytes()).hexdigest() + "  " + target.name + "\n", encoding="utf-8")
        print(f"APK: {target}")


def select_device(serial):
    adb = sdk_path() / "platform-tools" / ("adb" + EXE)
    output = run([adb, "devices"], capture=True).stdout
    devices = [line.split()[0] for line in output.splitlines() if len(line.split()) == 2 and line.split()[1] == "device"]
    if serial:
        if serial not in devices:
            raise RuntimeError("Selected device is offline or not authorized.")
        return [adb, "-s", serial]
    if len(devices) != 1:
        raise RuntimeError("Connect exactly one authorized device or pass --serial DEVICE_ID.")
    return [adb, "-s", devices[0]]


def scripts_test():
    run([sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"])


FORWARDED_COMMANDS = {
    "public-export": ("scripts/publish/export_public.py",),
    "public-check": ("scripts/publish/check_public.py",),
    "public-sync": ("scripts/publish/sync_public.py",),
    "signing-init": ("scripts/release/signing.py", "init"),
    "sign-release": ("scripts/release/signing.py", "sign"),
    "build-signed-release": ("scripts/release/signing.py", "build"),
    "release-workflow": ("scripts/release/workflow.py",),
}


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["repo", "scripts-test", "doctor", "setup", "engine-test", "engine-build", "core-fetch", "core-verify", "core-build", "geo-fetch", "geo-verify", "ui-check", "check", "device-test", "build-debug", "build-release", "devices", "avds", "install", "run", "logcat", "clean", "verify-apk"] + list(FORWARDED_COMMANDS))
    parser.add_argument("--serial")
    parser.epilog = "Git tasks: python scripts/dev.py repo --help"
    return parser


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    # The Git helper owns its interactive arguments and never needs an Android SDK.
    # run() pins the working directory to this project even when called elsewhere.
    if argv[:1] == ["repo"]:
        run([sys.executable, ROOT / "scripts/git/repo_tasks.py", *argv[1:]])
        return
    if argv and argv[0] in FORWARDED_COMMANDS:
        script, *prefix = FORWARDED_COMMANDS[argv[0]]
        if not (ROOT / script).is_file():
            raise RuntimeError("This command belongs to the private development repository.")
        run([sys.executable, ROOT / script, *prefix, *argv[1:]])
        return
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.serial is not None and not args.serial.strip():
        parser.error("--serial requires a device ID; run devices to list available devices.")
    if args.command == "doctor": doctor()
    elif args.command == "scripts-test": scripts_test()
    elif args.command == "setup": setup()
    elif args.command == "engine-test": run(["go", "test", "./..."], cwd=ENGINE)
    elif args.command == "engine-build": engine_build()
    elif args.command == "core-build": run([sys.executable, ROOT / "scripts/mihomo_build.py"])
    elif args.command in ("core-fetch", "core-verify"):
        run([sys.executable, ROOT / "scripts/mihomo.py", args.command.split("-")[1]])
    elif args.command in ("geo-fetch", "geo-verify"):
        run([sys.executable, ROOT / "scripts/geodata.py", args.command.split("-")[1]])
    elif args.command == "check":
        scripts_test()
        run([sys.executable, ROOT / "scripts/ui_contract.py"])
        run(["go", "test", "./..."], cwd=ENGINE)
        gradle("testDebugUnitTest", "lintDebug")
    elif args.command == "ui-check": run([sys.executable, ROOT / "scripts/ui_contract.py"])
    elif args.command == "build-debug": build_debug()
    elif args.command == "build-release":
        gradle("assembleRelease")
        for abi in SPEC["abis"]:
            apk = ROOT / f"app/build/outputs/apk/release/app-{abi}-release-unsigned.apk"
            verify_apk(apk, abi)
            label = "arm64" if abi == "arm64-v8a" else "amd64"
            target = ROOT / f"Bin/Jeemi-Android/Android-{label}/release/Jeemi-Android-{label}-unsigned.apk"
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(apk, target)
            target.with_suffix(".apk.sha256").write_text(hashlib.sha256(target.read_bytes()).hexdigest() + "  " + target.name + "\n", encoding="utf-8")
            print(f"APK: {target}")
        print("Unsigned release APK only. Configure signing before distribution.")
    elif args.command == "verify-apk":
        for abi in SPEC["abis"]: verify_apk(APK_DIR / f"app-{abi}-debug.apk", abi)
    elif args.command == "device-test":
        adb = select_device(args.serial)
        env = environment()
        env["ANDROID_SERIAL"] = str(adb[-1])
        setup()
        run([ROOT / ("gradlew.bat" if WINDOWS else "gradlew"), "connectedDebugAndroidTest", "--console", "plain"], env=env)
    elif args.command == "devices": run([sdk_path() / "platform-tools" / ("adb" + EXE), "devices", "-l"])
    elif args.command == "avds": run([sdk_path() / "emulator" / ("emulator" + EXE), "-list-avds"])
    elif args.command in ("install", "run", "logcat"):
        adb = select_device(args.serial)
        if args.command == "install":
            abis = run([*adb, "shell", "getprop", "ro.product.cpu.abilist"], capture=True).stdout.strip().split(",")
            abi = next((candidate for candidate in abis if candidate in SPEC["abis"]), None)
            if not abi: raise RuntimeError("This app requires an ARM64 or x86_64 Android OS.")
            apk = APK_DIR / f"app-{abi}-debug.apk"
            if not apk.exists(): raise RuntimeError("Run build-debug first.")
            run([*adb, "install", "-r", apk])
        elif args.command == "run":
            run([*adb, "shell", "am", "start", "-n", "io.jeemi.android.debug/io.jeemi.android.MainActivity"])
        else:
            pid = run([*adb, "shell", "pidof", "-s", "io.jeemi.android.debug"], capture=True).stdout.strip()
            if not pid.isdigit(): raise RuntimeError("Start Jeemi-Android before reading logs.")
            run([*adb, "logcat", "--pid", pid])
    elif args.command == "clean": gradle("clean")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
