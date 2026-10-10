"""Exercise adapted-core provenance checks with isolated, synthetic resources."""
import contextlib
import io
import json
from pathlib import Path
import struct
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile

from scripts import dev
import mihomo


class AdaptedCoreTest(unittest.TestCase):
    def setUp(self):
        temporary_root = dev.ROOT / "temp"
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="core-lock-test-", dir=temporary_root)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.resources = self.root / "resources/mihomo"
        self.notices = self.root / "resources/notices"
        self.resources.mkdir(parents=True)
        self.notices.mkdir(parents=True)
        self.lock = self.resources / "manifest.json"
        self.spec = {"version": "test-jeemi.2", "variant": "jeemi-vpn-fd-android-owner",
                     "sourceLock": "source.json", "patch": "patch.json", "cores": []}
        (self.resources / "source.tar.gz").write_bytes(b"synthetic source archive")
        self.source = {"archive": "source.tar.gz", "sha256": mihomo.digest(b"synthetic source archive")}
        (self.resources / "source.json").write_text(json.dumps(self.source), encoding="utf-8")
        (self.resources / "patch.json").write_text('{"patches":[]}', encoding="utf-8")
        for field, name in (("sourceLock", "mihomo-source.json"), ("patch", "mihomo-vpn-fd-patch.json")):
            data = (self.resources / self.spec[field]).read_bytes()
            self.spec[field + "Sha256"] = mihomo.digest(data)
            (self.notices / name).write_bytes(data)
        for abi, machine in (("arm64-v8a", 183), ("x86_64", 62)):
            data = bytearray(120)
            data[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", data, 18, machine)
            struct.pack_into("<Q", data, 32, 64)
            struct.pack_into("<HH", data, 54, 56, 1)
            struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 120, 120, 16384)
            path = self.resources / "jniLibs" / abi / "libmihomo_exec.so"
            path.parent.mkdir(parents=True)
            path.write_bytes(data)
            self.spec["cores"].append({"abi": abi, "bytes": len(data), "sha256": mihomo.digest(data)})
        self.save_manifest()
        for module, name, value in ((mihomo, "ROOT", self.root), (mihomo, "RESOURCES", self.resources),
                                    (mihomo, "LOCK", self.lock), (dev, "ROOT", self.root)):
            patcher = mock.patch.object(module, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def save_manifest(self):
        data = json.dumps(self.spec).encode()
        self.lock.write_bytes(data)
        (self.notices / "mihomo.json").write_bytes(data)

    def test_source_archive_patch_and_packaged_notices_are_all_required(self):
        for variant in ("jeemi-vpn-fd", "jeemi-vpn-fd-android-owner"):
            self.spec["variant"] = variant
            self.save_manifest()
            with contextlib.redirect_stdout(io.StringIO()):
                mihomo.verify()
            for path in (self.resources / "source.json", self.resources / "patch.json",
                         self.resources / "source.tar.gz", self.notices / "mihomo-source.json",
                         self.notices / "mihomo-vpn-fd-patch.json"):
                with self.subTest(variant=variant, file=path.name):
                    original = path.read_bytes()
                    try:
                        path.write_bytes(original + b"tampered")
                        with self.assertRaises(RuntimeError):
                            mihomo.verify()
                    finally:
                        path.write_bytes(original)

    def test_fetch_rebuilds_both_adaptations_instead_of_fetching_unpatched_cli(self):
        builder = SimpleNamespace(build=mock.Mock())
        with mock.patch.dict(sys.modules, {"mihomo_build": builder}), \
                mock.patch.object(mihomo, "fetch_one", side_effect=AssertionError("Fetched upstream CLI")):
            for variant in ("jeemi-vpn-fd", "jeemi-vpn-fd-android-owner"):
                self.spec["variant"] = variant
                self.save_manifest()
                builder.build.reset_mock()
                mihomo.fetch()
                builder.build.assert_called_once_with()

    def test_rebuilding_starts_from_the_archive_without_old_patches_or_extra_files(self):
        import mihomo_build
        archive = self.resources / "source.tar.gz"
        with tarfile.open(archive, "w:gz") as bundle:
            data = b"package original\n"
            entry = tarfile.TarInfo("core/main.go")
            entry.size = len(data)
            bundle.addfile(entry, io.BytesIO(data))
        source = {"archive": archive.name, "directory": "core", "sha256": mihomo.digest(archive.read_bytes())}
        (self.resources / "source.json").write_text(json.dumps(source), encoding="utf-8")
        patch = {"patches": [{"file": "main.go", "before": "package original", "after": "package patched"}],
                 "files": {"adaptation.go": "package patched\n"}}
        (self.resources / "vpn-fd-patch.json").write_text(json.dumps(patch), encoding="utf-8")
        with mock.patch.object(mihomo_build, "ROOT", self.root), \
                mock.patch.object(mihomo_build, "RES", self.resources), \
                mock.patch.object(mihomo_build, "SOURCE", self.resources / "source.json"):
            first = mihomo_build.source_tree()
            (first / "unexpected.go").write_text("package patched\n", encoding="utf-8")
            second = mihomo_build.source_tree()
        self.assertNotEqual(first, second)
        self.assertFalse((second / "unexpected.go").exists())
        self.assertEqual((second / "main.go").read_text(encoding="utf-8"), "package patched\n")
        self.assertEqual((second / "adaptation.go").read_text(encoding="utf-8"), "package patched\n")

    def test_apk_cannot_ship_a_different_source_or_patch_notice(self):
        geo = self.root / "resources/geodata/manifest.json"
        geo.parent.mkdir(parents=True)
        geo.write_text('{"files":[]}', encoding="utf-8")
        apk = self.root / "test.apk"
        entries = {"assets/" + p.name: p.read_bytes() for p in self.notices.iterdir()}
        entries["assets/geodata/manifest.json"] = geo.read_bytes()
        entries["lib/x86_64/libmihomo_exec.so"] = (self.resources / "jniLibs/x86_64/libmihomo_exec.so").read_bytes()
        metadata = "zh-rCN sdkVersion:'26' targetSdkVersion:'36'"
        with mock.patch.object(dev, "sdk_path", return_value=Path("sdk")), \
                mock.patch.object(dev, "run", return_value=SimpleNamespace(stdout=metadata)), \
                contextlib.redirect_stdout(io.StringIO()):
            for changed in (None, "assets/mihomo-source.json", "assets/mihomo-vpn-fd-patch.json"):
                with self.subTest(changed=changed):
                    with zipfile.ZipFile(apk, "w") as archive:
                        for name, data in entries.items():
                            archive.writestr(name, data + b"tampered" if name == changed else data)
                    if changed is None:
                        dev.verify_apk(apk, "x86_64")
                    else:
                        with self.assertRaisesRegex(RuntimeError, "pinned core source or patch notice"):
                            dev.verify_apk(apk, "x86_64")


if __name__ == "__main__":
    unittest.main()
