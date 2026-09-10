"""Create a project signing identity or sign both previously built Release APKs."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import zipfile

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts import dev
from scripts.release.common import ARCHITECTURES, ROOT, apk_name, digest, fingerprint, github_outputs, version

SECRET_NAMES = ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS",
                "ANDROID_KEY_PASSWORD", "ANDROID_SIGNING_CERT_SHA256")
SIGNING_DIRECTORY = ROOT / "private/android-signing"


def java_tool(name: str) -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        path = Path(java_home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if path.is_file():
            return str(path)
    path = shutil.which(name)
    if not path:
        raise ValueError(f"JDK tool {name} not found. Set JAVA_HOME.")
    return path


def secret_process(command: list[str], environment: dict, *, binary=False):
    # Keytool/apksigner diagnostics are captured, never echoed with credentials.
    result = subprocess.run(command, env=environment, capture_output=True, timeout=120)
    if result.returncode:
        raise ValueError("Signing tool failed; check the keystore, alias, passwords and installed JDK/Build Tools.")
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def initialize(output: Path = SIGNING_DIRECTORY) -> None:
    output = output.resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError("Signing directory is not empty; the existing signing identity will never be overwritten.")
    output.mkdir(parents=True, exist_ok=True)
    password = secrets.token_urlsafe(36)
    alias = "jeemi-android-release"
    environment = {**os.environ, "JEEMI_INIT_PASSWORD": password}
    keystore = output / "jeemi-android-release.jks"
    secret_process([java_tool("keytool"), "-genkeypair", "-keystore", str(keystore), "-storetype", "JKS",
                    "-alias", alias, "-keyalg", "RSA", "-keysize", "4096", "-sigalg", "SHA256withRSA",
                    "-validity", "10000", "-dname", "CN=Jeemi Android, OU=Open Source, O=Jeemi",
                    "-storepass:env", "JEEMI_INIT_PASSWORD", "-keypass:env", "JEEMI_INIT_PASSWORD"], environment)
    certificate = secret_process([java_tool("keytool"), "-exportcert", "-keystore", str(keystore),
                                  "-alias", alias, "-storepass:env", "JEEMI_INIT_PASSWORD"], environment, binary=True)
    certificate_hash = hashlib.sha256(certificate).hexdigest()
    pem = "-----BEGIN CERTIFICATE-----\n" + base64.encodebytes(certificate).decode("ascii") + "-----END CERTIFICATE-----\n"
    (output / "certificate.pem").write_text(pem, encoding="ascii", newline="\n")
    values = {"ANDROID_KEYSTORE_BASE64": base64.b64encode(keystore.read_bytes()).decode("ascii"),
              "ANDROID_KEYSTORE_PASSWORD": password, "ANDROID_KEY_ALIAS": alias,
              "ANDROID_KEY_PASSWORD": password, "ANDROID_SIGNING_CERT_SHA256": certificate_hash}
    (output / "github-secrets.json").write_text(json.dumps(values, indent=2) + "\n", encoding="utf-8", newline="\n")
    (output / "README.md").write_text(
        "# Android 私有签名材料\n\n"
        "按维护者要求仅保留在私有开发仓库；公开同步严格排除整个 private/ 目录。\n\n"
        "将 github-secrets.json 的五个键和值分别添加到公开仓库 Actions Secrets。不要上传该文件或密钥库到公开仓库、Release、Artifact 或日志。\n\n"
        "证书：RSA 4096 / SHA256withRSA，有效期 10000 天；别名 jeemi-android-release。两种架构与后续版本持续使用同一密钥，并另行保留备份。\n\n"
        f"公开证书 SHA-256：`{certificate_hash}`。certificate.pem 只含公钥证书。\n",
        encoding="utf-8", newline="\n")
    if os.name != "nt":
        output.chmod(0o700)
        for path in (keystore, output / "github-secrets.json"):
            path.chmod(0o600)
    print(f"Signing identity created in {output}; secret values were not printed.")
    print("Certificate SHA-256: " + certificate_hash)


def signing_values(environment=None, local_file: Path | None = None) -> dict:
    environment = os.environ if environment is None else environment
    values = {name: environment.get(name, "") for name in SECRET_NAMES}
    if not any(values.values()) and not environment.get("GITHUB_ACTIONS"):
        path = local_file or SIGNING_DIRECTORY / "github-secrets.json"
        if path.exists():
            values = json.loads(path.read_text(encoding="utf-8"))
    if any(not isinstance(values.get(name), str) or not values[name] for name in SECRET_NAMES):
        raise ValueError("Missing Android signing values. Configure all five ANDROID_* signing Secrets; see BUILDING.md or docs/public-release.md.")
    values = {name: values[name] for name in SECRET_NAMES}
    if len(values["ANDROID_KEYSTORE_BASE64"]) > 48 * 1024:
        raise ValueError("Keystore Base64 exceeds the GitHub Secret size limit.")
    try:
        binary = base64.b64decode(values["ANDROID_KEYSTORE_BASE64"], validate=True)
    except ValueError as error:
        raise ValueError("ANDROID_KEYSTORE_BASE64 is not valid Base64.") from error
    if not 32 <= len(binary) <= 36 * 1024:
        raise ValueError("Unexpected keystore size.")
    if any(char in values["ANDROID_KEY_ALIAS"] for char in "\r\n\0"):
        raise ValueError("Invalid key alias.")
    values["ANDROID_SIGNING_CERT_SHA256"] = fingerprint(values["ANDROID_SIGNING_CERT_SHA256"])
    return values


def apksigner() -> list[str]:
    jar = dev.sdk_path() / "build-tools" / dev.SPEC["buildTools"] / "lib/apksigner.jar"
    if not jar.is_file():
        raise ValueError("Install the pinned Android Build Tools before signing.")
    return [java_tool("java"), "-jar", str(jar)]


def verify_signature(path: Path, expected: str) -> None:
    result = secret_process([*apksigner(), "verify", "--verbose", "--print-certs", "--min-sdk-version",
                             str(dev.SPEC["minSdk"]), str(path)], os.environ.copy())
    result = result.replace("\r\n", "\n")
    certificates = [fingerprint(value) for value in re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", result, re.M)]
    if certificates != [fingerprint(expected)]:
        raise ValueError("APK signing certificate does not match ANDROID_SIGNING_CERT_SHA256.")
    for scheme in ("v2", "v3"):
        if not re.search(rf"Verified using {scheme} scheme.*: true", result):
            raise ValueError(f"APK is missing a valid {scheme} signature.")


def verify_package(path: Path, abi: str, metadata: dict) -> None:
    dev.verify_apk(path, abi)
    with zipfile.ZipFile(path) as archive:
        if "META-INF/version-control-info.textproto" in archive.namelist():
            raise ValueError("Release APK contains embedded Git metadata; rebuild with vcsInfo.include disabled.")
    output = dev.run([dev.sdk_path() / "build-tools" / dev.SPEC["buildTools"] / ("aapt.exe" if os.name == "nt" else "aapt"),
                      "dump", "badging", path], capture=True).stdout
    package = next((line for line in output.splitlines() if line.startswith("package: ")), "")
    attributes = dict(re.findall(r"(\w+)='([^']*)'", package))
    if (attributes.get("name"), attributes.get("versionName"), attributes.get("versionCode")) != (
            "io.jeemi.android", metadata["version"], str(metadata["versionCode"])):
        raise ValueError("Release APK package/version differs from app/build.gradle.kts; rebuild Release first.")
    if "application-debuggable" in output.splitlines():
        raise ValueError("A debuggable APK cannot be published as Release.")


def sign_release() -> Path:
    values = signing_values()
    metadata = version()
    commit = os.environ.get("RELEASE_COMMIT", "")
    if commit and not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid public source commit.")
    base = ROOT / "temp/signing"
    base.mkdir(parents=True, exist_ok=True)
    # This temporary directory is exclusively owned by this operation and contains
    # the decoded private key. It is removed on success, error and cancellation.
    with tempfile.TemporaryDirectory(prefix="release-", dir=base) as temporary:
        stage = Path(temporary)
        keystore = stage / "release.jks"
        keystore.write_bytes(base64.b64decode(values["ANDROID_KEYSTORE_BASE64"], validate=True))
        if os.name != "nt":
            keystore.chmod(0o600)
        environment = {**os.environ, "JEEMI_STORE_PASSWORD": values["ANDROID_KEYSTORE_PASSWORD"],
                       "JEEMI_KEY_PASSWORD": values["ANDROID_KEY_PASSWORD"]}
        assets = []
        for architecture, abi in ARCHITECTURES.items():
            source = ROOT / f"Bin/Jeemi-Android/Android-{architecture}/release/Jeemi-Android-{architecture}-unsigned.apk"
            verify_package(source, abi, metadata)
            output = stage / apk_name(metadata["tag"], architecture)
            secret_process([*apksigner(), "sign", "--ks", str(keystore), "--ks-key-alias", values["ANDROID_KEY_ALIAS"],
                            "--ks-pass", "env:JEEMI_STORE_PASSWORD", "--key-pass", "env:JEEMI_KEY_PASSWORD",
                            "--min-sdk-version", str(dev.SPEC["minSdk"]), "--v1-signing-enabled", "false",
                            "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                            "--out", str(output), str(source)], environment)
            verify_signature(output, values["ANDROID_SIGNING_CERT_SHA256"])
            verify_package(output, abi, metadata)
            assets.append({"architecture": architecture, "abi": abi, "file": output.name,
                           "bytes": output.stat().st_size, "sha256": digest(output)})
        manifest = {"schema": 1, **metadata, "applicationId": "io.jeemi.android", "sourceCommit": commit or None,
                    "certificateSha256": values["ANDROID_SIGNING_CERT_SHA256"], "minSdk": dev.SPEC["minSdk"],
                    "targetSdk": dev.SPEC["targetSdk"], "artifacts": assets}
        (stage / "release.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
        sums = []
        for item in assets:
            line = item["sha256"] + "  " + item["file"] + "\n"
            sums.append(line)
            (stage / (item["file"] + ".sha256")).write_text(line, encoding="ascii", newline="\n")
        sums.append(digest(stage / "release.json") + "  release.json\n")
        (stage / "SHA256SUMS").write_text("".join(sums), encoding="ascii", newline="\n")
        destination = ROOT / "Bin/github" / metadata["tag"]
        allowed = {"release.json", "SHA256SUMS"} | {item["file"] + suffix for item in assets for suffix in ("", ".sha256")}
        if destination.exists() and {path.name for path in destination.iterdir()} - allowed:
            raise ValueError("Unexpected files in the version's release directory; refusing to mix artifacts.")
        destination.mkdir(parents=True, exist_ok=True)
        for name in sorted(allowed):
            shutil.copyfile(stage / name, destination / name)
        for item in assets:
            directory = ROOT / f"Bin/Jeemi-Android/Android-{item['architecture']}/release"
            for suffix in ("", ".sha256"):
                shutil.copyfile(stage / (item["file"] + suffix), directory / (item["file"] + suffix))
    github_outputs({"assets": destination.relative_to(ROOT).as_posix()})
    print(f"Signed and verified both Release APKs: {destination}")
    return destination


def build_signed_release() -> Path:
    # Fail early if the existing identity is unavailable; never silently replace it.
    signing_values()
    dev.run([sys.executable, ROOT / "scripts/dev.py", "build-release"])
    # A failed build raises above, so previously archived APKs cannot be signed here.
    return sign_release()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init", "sign", "build"])
    parser.add_argument("--output", type=Path, default=SIGNING_DIRECTORY)
    arguments = parser.parse_args()
    try:
        if arguments.command == "init":
            initialize(arguments.output)
        elif arguments.command == "build":
            build_signed_release()
        else:
            sign_release()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
