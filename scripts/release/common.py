"""Version and release metadata shared by local and GitHub builds."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
PUBLIC_REPOSITORY = "bluevava/jeemi-android"
ARCHITECTURES = {"arm64": "arm64-v8a", "amd64": "x86_64"}


def parse_version(text: str) -> dict:
    def assignment(statement: str) -> str:
        values = re.findall(rf'^[ \t]*{statement}[ \t]*=[ \t]*(.*?)[ \t]*(?://[^\r\n]*)?\r?$', text, re.M)
        if len(values) != 1:
            raise ValueError("app/build.gradle.kts must declare exactly one " + statement + " assignment.")
        return values[0]

    name = assignment(r"val[ \t]+appVersionName")
    code = assignment(r"val[ \t]+appVersionCode")
    if not re.fullmatch(r'"[0-9]+\.[0-9]+\.[0-9]+"', name) or not re.fullmatch(r"[1-9][0-9]*", code) or int(code) > 2100000000:
        raise ValueError("app/build.gradle.kts header must declare literal appVersionName X.Y.Z and appVersionCode in 1..2100000000.")
    if assignment("versionName") != "appVersionName" or assignment("versionCode") != "appVersionCode":
        raise ValueError("Android versionName/versionCode must reference the header appVersionName/appVersionCode.")
    return {"version": name[1:-1], "versionCode": int(code), "tag": "v" + name[1:-1]}


def version(root: Path = ROOT) -> dict:
    return parse_version((root / "app/build.gradle.kts").read_text(encoding="utf-8"))


def release_notes(text: str, number: str) -> str:
    lines = text.splitlines()
    start = next((i for i, line in enumerate(lines) if line == "## " + number or line.startswith("## " + number + " - ")), None)
    if start is None:
        raise ValueError("CHANGELOG 缺少本次版本的公开更新说明。")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    notes = "\n".join(lines[start + 1:end]).strip()
    if not notes:
        raise ValueError("CHANGELOG 本次版本的公开更新说明不能为空。")
    return notes + "\n"


def notes_for(root: Path, number: str) -> str:
    path = root / "CHANGELOG.md"
    if not path.exists():
        path = root / "public/CHANGELOG.md"
    return release_notes(path.read_text(encoding="utf-8"), number)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fingerprint(value: str) -> str:
    value = value.strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("ANDROID_SIGNING_CERT_SHA256 must contain the certificate's SHA-256 fingerprint.")
    return value


def apk_name(tag: str, architecture: str) -> str:
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag) or architecture not in ARCHITECTURES:
        raise ValueError("Invalid APK version or architecture.")
    return f"Jeemi-Android-{tag}-{architecture}.apk"


def download_table(tag: str) -> str:
    """Direct links to the existing versioned assets; Markdown owns badge clicks."""
    base = f"https://github.com/{PUBLIC_REPOSITORY}/releases"
    rows = ["| 版本 | 适用设备 | APK 下载 | 校验 |", "| --- | --- | --- | --- |"]
    for architecture, device, badge, color in (
        ("arm64", "ARM64 手机 / 平板", "ARM64", "3DDC84"),
        ("amd64", "x86_64 设备 / 模拟器", "x86__64", "0078D6"),
    ):
        name = apk_name(tag, architecture)
        url = f"{base}/download/{tag}/{name}"
        image = f"https://img.shields.io/badge/Android-{badge}_APK-{color}?style=for-the-badge&logo=android&logoColor=white"
        rows.append(f"| [{tag}]({base}/tag/{tag}) | {device} | [![下载 {architecture} APK]({image})]({url}) | [SHA-256]({url}.sha256) |")
    return "\n".join(rows) + "\n"


def github_outputs(values: dict) -> None:
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            for key, value in values.items():
                value = str(value)
                if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]*", key) or "\n" in value or "\r" in value:
                    raise ValueError("Invalid workflow output.")
                stream.write(f"{key}={value}\n")


def ci_metadata() -> None:
    toolchain = json.loads((ROOT / "scripts/toolchain.json").read_text(encoding="utf-8"))
    values = {**version(), "java": toolchain["java"], "python": toolchain["python"], "go": toolchain["go"],
              "compileSdk": toolchain["compileSdk"], "buildTools": toolchain["buildTools"], "ndk": toolchain["ndk"]}
    github_outputs(values)
    print(json.dumps(values, ensure_ascii=False))
