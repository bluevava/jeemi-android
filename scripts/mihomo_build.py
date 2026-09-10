"""Build Jeemi's minimal VpnService FD adaptation from a locked mihomo source."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import dev
import mihomo

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "resources/mihomo"
SOURCE = RES / "source.json"

def source_tree():
    spec = json.loads(SOURCE.read_text(encoding="utf-8"))
    archive = RES / spec["archive"]
    if not archive.exists():
        archive.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(spec["url"], timeout=60) as response:
            data = response.read(32 * 1024 * 1024 + 1)
        if len(data) > 32 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != spec["sha256"]:
            raise RuntimeError("Mihomo source digest mismatch.")
        archive.write_bytes(data)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != spec["sha256"]:
        raise RuntimeError("Mihomo source digest mismatch.")
    patch = json.loads((RES / "vpn-fd-patch.json").read_text(encoding="utf-8"))
    stamp = hashlib.sha256(archive.read_bytes() + (RES / "vpn-fd-patch.json").read_bytes()).hexdigest()
    destination = ROOT / "temp/core-source" / stamp[:16]
    destination.mkdir(parents=True, exist_ok=True)
    # Extraction uses Python's data filter and the immutable input archive.
    with tarfile.open(archive) as source:
        source.extractall(destination, filter="data")
    tree = destination / spec["directory"]
    for change in patch.get("patches", [patch]):
        target = tree / change["file"]
        if not target.resolve().is_relative_to(tree.resolve()):
            raise RuntimeError("Unexpected source patch path.")
        text = target.read_text(encoding="utf-8")
        if text.count(change["before"]) != 1:
            raise RuntimeError("The patch no longer matches its pinned source: " + change["file"])
        target.write_text(text.replace(change["before"], change["after"]), encoding="utf-8", newline="\n")
    for name, content in patch.get("files", {}).items():
        target = tree / name
        if not target.resolve().is_relative_to(tree.resolve()) or target.exists():
            raise RuntimeError("Unexpected additional source path.")
        target.write_text(content, encoding="utf-8", newline="\n")
    return tree

def build(update_lock=False):
    source = source_tree()
    spec = json.loads(mihomo.LOCK.read_text(encoding="utf-8"))
    env = dev.environment()
    version = dev.run(["go", "version"], capture=True).stdout
    if ("go" + dev.SPEC["go"] + " ") not in version:
        raise RuntimeError("Use the pinned Go version before rebuilding the core.")
    host = "windows-x86_64" if os.name == "nt" else ("darwin-x86_64" if dev.platform.system() == "Darwin" else "linux-x86_64")
    compiler = Path(env["ANDROID_NDK_HOME"]) / "toolchains/llvm/prebuilt" / host / "bin"
    outputs = []
    for item in spec["cores"]:
        target = "aarch64-linux-android26" if item["abi"] == "arm64-v8a" else "x86_64-linux-android26"
        output = ROOT / "temp/core-built" / item["abi"] / "libmihomo_exec.so"
        output.parent.mkdir(parents=True, exist_ok=True)
        core_env = {**env, "GOOS": "android", "GOARCH": item["architecture"], "CGO_ENABLED": "1", "GOTOOLCHAIN": "local",
                    "CC": str(compiler / ("clang.exe" if os.name == "nt" else "clang")) + " --target=" + target}
        flags = "-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384 " + \
            "-X github.com/metacubex/mihomo/constant.Version=" + spec["version"] + \
            " -X github.com/metacubex/mihomo/constant.BuildTime=2026-09-09T00:00:00Z"
        dev.run(["go", "build", "-trimpath", "-buildvcs=false", "-tags=with_gvisor", "-ldflags=" + flags,
                 "-o", output, "."], cwd=source, env=core_env)
        data = output.read_bytes()
        mihomo.verify_elf(data, item["abi"])
        digest = hashlib.sha256(data).hexdigest()
        if not update_lock and digest != item["sha256"]:
            raise RuntimeError("Rebuilt core differs from the locked binary; review toolchain and source changes.")
        outputs.append((item, output, digest, len(data)))
    # Both architectures must compile and verify before replacing either resource.
    for item, output, digest, size in outputs:
        item["sha256"], item["bytes"] = digest, size
        mihomo.core_path(item).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(output, mihomo.core_path(item))
    if update_lock:
        for field in ("sourceLock", "patch"):
            spec[field + "Sha256"] = hashlib.sha256((RES / spec[field]).read_bytes()).hexdigest()
        mihomo.LOCK.write_text(json.dumps(spec, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        shutil.copyfile(mihomo.LOCK, ROOT / "resources/notices/mihomo.json")
        shutil.copyfile(RES / spec["sourceLock"], ROOT / "resources/notices/mihomo-source.json")
        shutil.copyfile(RES / spec["patch"], ROOT / "resources/notices/mihomo-vpn-fd-patch.json")
    mihomo.verify()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update-lock", action="store_true")
    build(parser.parse_args().update_lock)
