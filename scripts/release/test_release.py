"""Release invariants, with synthetic assets and API responses; never publish."""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from scripts import dev
from scripts.release import common, signing, workflow

COMMIT = "a" * 40
CERTIFICATE = "b" * 64
METADATA = {"version": "1.2.3", "versionCode": 42, "tag": "v1.2.3"}


class TemporaryTests(unittest.TestCase):
    def setUp(self):
        parent = common.ROOT / "temp/release-tests"
        parent.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="case-", dir=parent)
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def bundle(self):
        manifest = {"schema": 1, **METADATA, "applicationId": "io.jeemi.android", "sourceCommit": COMMIT,
                    "certificateSha256": CERTIFICATE, "minSdk": dev.SPEC["minSdk"], "targetSdk": dev.SPEC["targetSdk"], "artifacts": []}
        sums = []
        for architecture, abi in common.ARCHITECTURES.items():
            path = self.directory / common.apk_name(METADATA["tag"], architecture)
            path.write_bytes(b"synthetic apk " + architecture.encode())
            manifest["artifacts"].append({"architecture": architecture, "abi": abi, "file": path.name,
                                          "bytes": path.stat().st_size, "sha256": common.digest(path)})
            line = common.digest(path) + "  " + path.name + "\n"
            sums.append(line)
            Path(str(path) + ".sha256").write_text(line, encoding="ascii", newline="\n")
        (self.directory / "release.json").write_text(json.dumps(manifest) + "\n", encoding="utf-8", newline="\n")
        sums.append(common.digest(self.directory / "release.json") + "  release.json\n")
        (self.directory / "SHA256SUMS").write_text("".join(sums), encoding="ascii", newline="\n")
        return manifest


class SigningTests(TemporaryTests):
    def values(self):
        return dict(zip(signing.SECRET_NAMES, [base64.b64encode(b"x" * 100).decode(), "store-test", "alias-test", "key-test", CERTIFICATE]))

    def test_ci_requires_all_secrets_without_private_file_fallback(self):
        path = self.directory / "github-secrets.json"
        path.write_text(json.dumps(self.values()), encoding="utf-8")
        self.assertEqual(signing.signing_values({}, path), self.values())
        for environment in ({"GITHUB_ACTIONS": "true"}, {"ANDROID_KEY_ALIAS": "partial"}):
            with self.subTest(environment=environment), self.assertRaisesRegex(ValueError, "Missing"):
                signing.signing_values(environment, path)

    def test_local_release_build_failure_cannot_sign_previous_apks(self):
        with patch.object(signing, "signing_values", return_value=self.values()), \
                patch.object(signing.dev, "run", side_effect=subprocess.CalledProcessError(1, "build-release")), \
                patch.object(signing, "sign_release") as signer:
            with self.assertRaises(subprocess.CalledProcessError):
                signing.build_signed_release()
        signer.assert_not_called()

    def test_local_release_requires_identity_before_building(self):
        with patch.object(signing, "signing_values", side_effect=ValueError("missing identity")), \
                patch.object(signing.dev, "run") as build, patch.object(signing, "sign_release") as signer:
            with self.assertRaises(ValueError):
                signing.build_signed_release()
        build.assert_not_called()
        signer.assert_not_called()

    def test_release_rejects_embedded_private_git_metadata(self):
        apk = self.directory / "with-vcs.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("META-INF/version-control-info.textproto", "repositories { revision: 'private-commit' }")
        with patch.object(signing.dev, "verify_apk"), patch.object(signing.dev, "run") as aapt:
            with self.assertRaisesRegex(ValueError, "embedded Git metadata"):
                signing.verify_package(apk, "arm64-v8a", METADATA)
        aapt.assert_not_called()

    def test_invalid_secret_inputs_fail_before_starting_tools(self):
        for name, value in [("ANDROID_KEYSTORE_BASE64", "invalid!"), ("ANDROID_KEYSTORE_BASE64", "x" * (48 * 1024 + 1)),
                            ("ANDROID_KEY_ALIAS", "alias\nline"), ("ANDROID_SIGNING_CERT_SHA256", "c" * 63)]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                signing.signing_values({**self.values(), name: value})

    def test_initialization_never_overwrites_existing_identity(self):
        path = self.directory / "existing.jks"
        path.write_bytes(b"existing key")
        with self.assertRaisesRegex(ValueError, "never be overwritten"), patch.object(signing, "secret_process") as tool:
            signing.initialize(self.directory)
        tool.assert_not_called()
        self.assertEqual(path.read_bytes(), b"existing key")

    def test_tool_failure_does_not_echo_its_output_or_password(self):
        with patch.object(signing.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"private stdout", b"private stderr")):
            with self.assertRaises(ValueError) as error:
                signing.secret_process(["keytool"], {"PASSWORD": "private password"})
        self.assertNotIn("private", str(error.exception))

    def test_signature_requires_matching_single_certificate_and_both_schemes(self):
        valid = (f"Signer #1 certificate SHA-256 digest: {CERTIFICATE.upper()}\r\n"
                 "Verified using v2 scheme (APK Signature Scheme v2): true\r\n"
                 "Verified using v3 scheme (APK Signature Scheme v3): true\r\n")
        with patch.object(signing, "apksigner", return_value=["unused"]), patch.object(signing, "secret_process", return_value=valid):
            signing.verify_signature(Path("unused.apk"), CERTIFICATE)
        for invalid in (valid.replace(CERTIFICATE.upper(), "a" * 64), valid.replace("v3 scheme", "v1 scheme"),
                        valid + f"Signer #2 certificate SHA-256 digest: {CERTIFICATE}\r\n"):
            with patch.object(signing, "apksigner", return_value=["unused"]), patch.object(signing, "secret_process", return_value=invalid):
                with self.assertRaises(ValueError):
                    signing.verify_signature(Path("unused.apk"), CERTIFICATE)


class BundleTests(TemporaryTests):
    def test_complete_bundle_checks_both_apks_and_version(self):
        manifest = self.bundle()
        with patch.object(workflow, "verify_signature") as signatures, patch.object(workflow, "verify_package") as packages:
            self.assertEqual(workflow.validate_bundle(self.directory, METADATA, COMMIT, CERTIFICATE), manifest)
        self.assertEqual(signatures.call_count, 2)
        self.assertEqual([call.args[1] for call in packages.call_args_list], ["arm64-v8a", "x86_64"])

    def test_missing_extra_tampered_and_wrong_identity_are_rejected(self):
        self.bundle()
        for metadata, commit, certificate in ((METADATA, "c" * 40, CERTIFICATE), (METADATA, COMMIT, "c" * 64),
                                               ({**METADATA, "versionCode": 43}, COMMIT, CERTIFICATE)):
            with self.assertRaises(ValueError):
                workflow.validate_bundle(self.directory, metadata, commit, certificate, verify=False)
        extra = self.directory / "release.jks"
        extra.write_bytes(b"must not upload")
        with self.assertRaises(ValueError):
            workflow.validate_bundle(self.directory, METADATA, COMMIT, CERTIFICATE, verify=False)
        extra.unlink()
        apk = self.directory / common.apk_name(METADATA["tag"], "arm64")
        apk.write_bytes(b"changed content")
        with self.assertRaisesRegex(ValueError, "hash"):
            workflow.validate_bundle(self.directory, METADATA, COMMIT, CERTIFICATE, verify=False)
        apk.unlink()
        with self.assertRaises(ValueError):
            workflow.validate_bundle(self.directory, METADATA, COMMIT, CERTIFICATE, verify=False)

    def test_checksum_and_architecture_manifest_tampering_are_rejected(self):
        manifest = self.bundle()
        (self.directory / "SHA256SUMS").write_bytes(b"incorrect")
        with self.assertRaisesRegex(ValueError, "SHA256SUMS"):
            workflow.validate_bundle(self.directory, METADATA, COMMIT, CERTIFICATE, verify=False)
        self.bundle()
        manifest["artifacts"].reverse()
        (self.directory / "release.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "architecture"):
            workflow.validate_bundle(self.directory, METADATA, COMMIT, CERTIFICATE, verify=False)


class FakeGitHub:
    def __init__(self, state=None, commit=None):
        self.state = state
        self.commit = commit
        self.writes = []

    def request(self, path, *, method="GET", data=None, missing=False):
        if method != "GET":
            self.writes.append((path, method, data))
        if path.startswith("/releases/tags/"):
            return self.state
        if path.startswith("/git/ref/tags/"):
            return {"object": {"type": "commit", "sha": self.commit}} if self.commit else None
        if path == "/git/refs" and method == "POST":
            self.commit = data["sha"]
            return {}
        if path == "/releases" and method == "POST":
            self.state = {"id": 1, "draft": True, "assets": [], **data}
            return self.state
        if path == "/releases/1" and method == "PATCH":
            self.state.update(data)
            return self.state
        raise AssertionError((path, method))


class PublicationTests(TemporaryTests):
    def test_published_version_is_skipped_and_existing_tag_never_moves(self):
        api = FakeGitHub({"draft": False}, COMMIT)
        self.assertFalse(workflow.check_state(api, METADATA["tag"], "c" * 40))
        self.assertEqual(api.writes, [])
        api = FakeGitHub(commit="c" * 40)
        with self.assertRaisesRegex(ValueError, "another commit"):
            workflow.check_state(api, METADATA["tag"], COMMIT)
        with self.assertRaises(ValueError):
            workflow.ensure_tag(api, METADATA["tag"], COMMIT)
        self.assertEqual(api.writes, [])
        api = FakeGitHub()
        workflow.ensure_tag(api, METADATA["tag"], COMMIT)
        self.assertEqual(api.writes, [("/git/refs", "POST", {"ref": "refs/tags/v1.2.3", "sha": COMMIT})])

    def publish_with(self, api, upload):
        with patch.object(workflow, "context", return_value=(METADATA["tag"], COMMIT, METADATA)), \
                patch.object(workflow, "GitHub", return_value=api), patch.object(workflow, "notes_for", return_value="Notes\n"), \
                patch.object(workflow, "verify_signature"), patch.object(workflow, "verify_package"), \
                patch.dict(os.environ, {"ANDROID_SIGNING_CERT_SHA256": CERTIFICATE}), \
                patch.object(workflow.subprocess, "run", side_effect=upload):
            workflow.publish(self.directory)

    def test_publish_waits_for_all_uploaded_digests_and_retains_failed_draft(self):
        self.bundle()
        api = FakeGitHub()
        def upload(command, **kwargs):
            self.assertEqual(command[:3], ["gh", "release", "upload"])
            api.state["assets"] = [{"name": path.name, "size": path.stat().st_size, "state": "uploaded",
                                     "digest": "sha256:" + common.digest(path)} for path in self.directory.iterdir()]
        self.publish_with(api, upload)
        self.assertFalse(api.state["draft"])
        self.assertEqual(api.writes[-1][1], "PATCH")
        for failure in ("missing", "digest", "upload"):
            api = FakeGitHub()
            def broken(command, **kwargs):
                if failure == "upload":
                    raise subprocess.CalledProcessError(1, command)
                upload(command, **kwargs)
                if failure == "missing":
                    api.state["assets"].pop()
                else:
                    api.state["assets"][0]["digest"] = "sha256:" + "0" * 64
            with self.subTest(failure=failure), self.assertRaises((ValueError, subprocess.CalledProcessError)):
                self.publish_with(api, broken)
            self.assertTrue(api.state["draft"])
            self.assertFalse(any(method == "PATCH" for _, method, _ in api.writes))

    def test_invalid_bundle_or_published_release_does_not_mutate_remote(self):
        self.bundle()
        api = FakeGitHub({"draft": False}, COMMIT)
        self.publish_with(api, lambda *args, **kwargs: self.fail("unexpected upload"))
        self.assertEqual(api.writes, [])
        (self.directory / "private.json").write_bytes(b"secret")
        api = FakeGitHub()
        with self.assertRaises(ValueError):
            self.publish_with(api, lambda *args, **kwargs: self.fail("unexpected upload"))
        self.assertEqual(api.writes, [])

    def test_release_prepare_accepts_only_main_dispatch_or_matching_tag(self):
        for event, ref, accepted in [("workflow_dispatch", "refs/heads/main", True), ("push", "refs/tags/v1.2.3", True),
                                      ("workflow_dispatch", "refs/heads/other", False), ("push", "refs/tags/v1.2.4", False),
                                      ("pull_request", "refs/pull/1/merge", False)]:
            with self.subTest(event=event, ref=ref), patch.object(workflow, "context", return_value=(METADATA["tag"], COMMIT, METADATA)), \
                    patch.object(workflow, "GitHub", return_value=FakeGitHub()), patch.object(workflow, "notes_for"), \
                    patch.object(workflow, "github_outputs"), patch.dict(os.environ, {"GITHUB_EVENT_NAME": event, "GITHUB_REF": ref}):
                if accepted:
                    workflow.prepare()
                else:
                    with self.assertRaises(ValueError):
                        workflow.prepare()


class VersionTests(TemporaryTests):
    GRADLE = ('plugins {}\n\nval appVersionName = "1.2.3" // Display version\nval appVersionCode = 42\n'
              'android {\n    defaultConfig {\n        versionName = appVersionName\n'
              '        versionCode = appVersionCode\n    }\n}\n')

    def test_version_reads_header_literals_for_local_and_exported_sources(self):
        directory = self.directory / "app"
        directory.mkdir()
        path = directory / "build.gradle.kts"
        path.write_text(self.GRADLE, encoding="utf-8")
        self.assertEqual(common.version(self.directory), METADATA)
        self.assertEqual(common.parse_version(self.GRADLE.replace('\n', '\r\n')), METADATA)
        self.assertEqual(common.parse_version(self.GRADLE.replace('"1.2.3"', '"1.2.4"').replace('= 42', '= 43')),
                         {"version": "1.2.4", "versionCode": 43, "tag": "v1.2.4"})

    def test_version_rejects_invalid_headers_or_android_binding_drift(self):
        invalid = [self.GRADLE.replace('= 42', '= 0'), self.GRADLE.replace('= 42', '= 2100000001'),
                   self.GRADLE.replace('"1.2.3"', '"1.2"'), self.GRADLE + 'val appVersionCode = 43\n',
                   self.GRADLE + 'val appVersionName = otherVersion\n',
                   self.GRADLE.replace('versionName = appVersionName', 'versionName = "1.2.4"'),
                   self.GRADLE.replace('versionCode = appVersionCode', 'versionCode = 43'),
                   self.GRADLE.replace('        versionCode = appVersionCode\n', '')]
        for content in invalid:
            with self.subTest(content=content), self.assertRaises(ValueError):
                common.parse_version(content)

    def test_changelog_requires_content_for_exact_version(self):
        self.assertEqual(common.release_notes("## 1.2.3 - 2026-09-11\n\nChanges\n## 1.2.2\nOld", "1.2.3"), "Changes\n")
        for text in ["## 1.2.30\nwrong", "## 1.2.3\n\n## 1.2.2\nold", "## 1.2.3\n\n"]:
            with self.assertRaises(ValueError):
                common.release_notes(text, "1.2.3")


if __name__ == "__main__":
    unittest.main()
