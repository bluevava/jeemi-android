"""Validate public release state and publish a fully verified two-APK bundle."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.release.common import ARCHITECTURES, PUBLIC_REPOSITORY, ROOT, apk_name, ci_metadata, digest, fingerprint, github_outputs, notes_for, version
from scripts.release.signing import verify_package, verify_signature


class GitHub:
    def __init__(self):
        self.token = os.environ.get("GH_TOKEN", "")
        if not self.token:
            raise ValueError("GH_TOKEN is required for the release workflow.")

    def request(self, path: str, *, method="GET", data=None, missing=False):
        request = urllib.request.Request(
            "https://api.github.com/repos/" + PUBLIC_REPOSITORY + path,
            data=None if data is None else json.dumps(data).encode("utf-8"), method=method,
            headers={"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
                     "Content-Type": "application/json", "User-Agent": "Jeemi-Android-release"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read(4 * 1024 * 1024 + 1)
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            raise ValueError(f"GitHub API failed (HTTP {error.code}); no authentication details were logged.") from None
        except urllib.error.URLError:
            raise ValueError("GitHub API could not be reached.") from None
        if len(payload) > 4 * 1024 * 1024:
            raise ValueError("Unexpectedly large GitHub API response.")
        return json.loads(payload)


def release_state(api, tag):
    return api.request("/releases/tags/" + urllib.parse.quote(tag, safe=""), missing=True)


def tag_commit(api, tag):
    ref = api.request("/git/ref/tags/" + urllib.parse.quote(tag, safe=""), missing=True)
    if ref is None:
        return None
    obj = ref["object"]
    for _ in range(5):
        if obj["type"] == "commit" and re.fullmatch(r"[0-9a-f]{40}", obj["sha"]):
            return obj["sha"]
        if obj["type"] != "tag" or not re.fullmatch(r"[0-9a-f]{40}", obj["sha"]):
            break
        obj = api.request("/git/tags/" + obj["sha"])["object"]
    raise ValueError("Release tag does not resolve to a commit.")


def check_state(api, tag: str, commit: str) -> bool:
    state = release_state(api, tag)
    if state is not None and not state["draft"]:
        print(f"{tag} is already published; skipping without replacing any assets.")
        return False
    target = tag_commit(api, tag)
    if target is not None and target != commit:
        raise ValueError("The version tag points to another commit; it will not be moved.")
    return True


def context() -> tuple[str, str, dict]:
    if os.environ.get("GITHUB_REPOSITORY") != PUBLIC_REPOSITORY:
        raise ValueError("Release operations only run in " + PUBLIC_REPOSITORY)
    metadata = version()
    tag = os.environ.get("RELEASE_TAG", metadata["tag"])
    commit = os.environ.get("RELEASE_COMMIT") or os.environ.get("GITHUB_SHA", "")
    if tag != metadata["tag"] or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Release version or source commit is invalid.")
    actual = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, check=True, text=True, capture_output=True).stdout.strip()
    if actual != commit:
        raise ValueError("The checkout does not match the prepared public source commit.")
    return tag, commit, metadata


def prepare() -> None:
    tag, commit, _ = context()
    event, ref = os.environ.get("GITHUB_EVENT_NAME"), os.environ.get("GITHUB_REF")
    if (event == "workflow_dispatch" and ref != "refs/heads/main") or (event == "push" and ref != "refs/tags/" + tag) or event not in {"push", "workflow_dispatch"}:
        raise ValueError("Release must be started manually from main or by pushing the matching version tag.")
    notes_for(ROOT, tag[1:])
    ready = check_state(GitHub(), tag, commit)
    github_outputs({"tag": tag, "sha": commit, "ready": str(ready).lower()})


def validate_bundle(directory: Path, metadata: dict, commit: str, certificate: str, *, verify=True) -> dict:
    directory = directory.resolve()
    names = [apk_name(metadata["tag"], architecture) for architecture in ARCHITECTURES]
    expected = {"release.json", "SHA256SUMS"} | {name + suffix for name in names for suffix in ("", ".sha256")}
    if not directory.is_dir() or {path.name for path in directory.iterdir()} != expected:
        raise ValueError("Release requires exactly two architecture APKs, two hashes, release.json and SHA256SUMS.")
    for name in expected:
        path = directory / name
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(directory):
            raise ValueError("Release bundle cannot contain links or directories.")
        if path.stat().st_size > (96 * 1024 * 1024 if name.endswith(".apk") else 64 * 1024):
            raise ValueError("Release asset exceeds its size limit.")
    manifest = json.loads((directory / "release.json").read_text(encoding="utf-8"))
    from scripts import dev
    expected_fields = {"schema": 1, **metadata, "applicationId": "io.jeemi.android", "sourceCommit": commit,
                       "certificateSha256": fingerprint(certificate), "minSdk": dev.SPEC["minSdk"], "targetSdk": dev.SPEC["targetSdk"]}
    if set(manifest) != set(expected_fields) | {"artifacts"} or any(manifest.get(key) != value for key, value in expected_fields.items()):
        raise ValueError("Release manifest version, commit, certificate or SDK does not match this release.")
    if not isinstance(manifest["artifacts"], list) or len(manifest["artifacts"]) != 2:
        raise ValueError("Release manifest must contain exactly two APKs.")
    sums = []
    for item, (architecture, abi) in zip(manifest["artifacts"], ARCHITECTURES.items()):
        name = apk_name(metadata["tag"], architecture)
        path = directory / name
        if item != {"architecture": architecture, "abi": abi, "file": name, "bytes": path.stat().st_size, "sha256": digest(path)}:
            raise ValueError("Release APK size, hash or architecture does not match its manifest.")
        line = digest(path) + "  " + name + "\n"
        if (directory / (name + ".sha256")).read_text(encoding="ascii") != line:
            raise ValueError("APK checksum file mismatch.")
        sums.append(line)
        if verify:
            verify_signature(path, certificate)
            verify_package(path, abi, metadata)
    sums.append(digest(directory / "release.json") + "  release.json\n")
    if (directory / "SHA256SUMS").read_text(encoding="ascii") != "".join(sums):
        raise ValueError("SHA256SUMS mismatch.")
    return manifest


def ensure_tag(api, tag, commit) -> None:
    actual = tag_commit(api, tag)
    if actual is None:
        try:
            api.request("/git/refs", method="POST", data={"ref": "refs/tags/" + tag, "sha": commit})
        except ValueError:
            # A concurrently-created matching tag is fine; never move a tag.
            if tag_commit(api, tag) != commit:
                raise
    if tag_commit(api, tag) != commit:
        raise ValueError("Release tag no longer matches the verified source commit.")


def publish(directory: Path) -> None:
    tag, commit, metadata = context()
    certificate = os.environ.get("ANDROID_SIGNING_CERT_SHA256", "")
    validate_bundle(directory, metadata, commit, certificate)
    notes = notes_for(ROOT, metadata["version"])
    api = GitHub()
    if not check_state(api, tag, commit):
        return
    ensure_tag(api, tag, commit)
    state = release_state(api, tag)
    if state is None:
        state = api.request("/releases", method="POST", data={"tag_name": tag, "target_commitish": commit,
                            "name": "Jeemi Android " + tag, "body": notes, "draft": True, "prerelease": False})
    if not state["draft"]:
        print("This version was already published; assets were not changed.")
        return
    files = sorted(path for path in directory.iterdir() if path.is_file())
    names = {path.name for path in files}
    if {asset["name"] for asset in state.get("assets", [])} - names:
        raise ValueError("The draft contains unexpected assets; review it before retrying.")
    # Only the publishing step receives GH_TOKEN; unsigned builds and PR checks do not.
    subprocess.run(["gh", "release", "upload", tag, *[str(path.resolve()) for path in files],
                    "--repo", PUBLIC_REPOSITORY, "--clobber"], check=True)
    state = release_state(api, tag)
    if state is None or not state["draft"] or tag_commit(api, tag) != commit:
        raise ValueError("Release state changed while uploading; publication was stopped.")
    uploaded = {asset["name"]: asset for asset in state.get("assets", [])}
    if set(uploaded) != names:
        raise ValueError("Draft upload is incomplete; the draft was retained for retry.")
    for path in files:
        asset = uploaded[path.name]
        if asset["state"] != "uploaded" or asset["size"] != path.stat().st_size or asset.get("digest") != "sha256:" + digest(path):
            raise ValueError("Uploaded asset digest/size mismatch; the draft was retained.")
    api.request("/releases/" + str(state["id"]), method="PATCH", data={"body": notes, "draft": False, "make_latest": "true"})
    print("Published " + PUBLIC_REPOSITORY + " " + tag)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["metadata", "check", "publish"])
    parser.add_argument("--assets", type=Path, default=Path("release-assets"))
    arguments = parser.parse_args()
    try:
        if arguments.command == "metadata":
            ci_metadata()
        elif arguments.command == "check":
            prepare()
        else:
            publish(arguments.assets)
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
