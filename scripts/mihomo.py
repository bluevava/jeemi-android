"""Restore or verify the pinned Android cores, without updating versions implicitly."""
import argparse
import gzip
import hashlib
import json
import pathlib
import struct
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = pathlib.Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "resources/mihomo"
LOCK = RESOURCES / "manifest.json"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify_elf(data, abi):
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != machine:
        raise RuntimeError(f"Unexpected Android ELF for {abi}")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    loads = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size) for i in range(count)]
    if not any(p[0] == 1 for p in loads) or any(p[7] < 16384 for p in loads if p[0] == 1):
        raise RuntimeError(f"Core is not ELF 16 KB aligned: {abi}")


def core_path(item):
    return RESOURCES / "jniLibs" / item["abi"] / "libmihomo_exec.so"


def verify():
    spec = json.loads(LOCK.read_text(encoding="utf-8"))
    if {item["abi"] for item in spec["cores"]} != {"arm64-v8a", "x86_64"} or len(spec["cores"]) != 2:
        raise RuntimeError("The core manifest must contain ARM64 and AMD64 exactly once.")
    expected = {core_path(item).resolve() for item in spec["cores"]}
    actual = {path.resolve() for path in (RESOURCES / "jniLibs").rglob("*.so")}
    if actual != expected:
        raise RuntimeError("Unexpected or missing ABI core resources.")
    notice = ROOT / "resources/notices/mihomo.json"
    if json.loads(notice.read_text(encoding="utf-8")) != spec:
        raise RuntimeError("Packaged source notice does not match the core manifest.")
    if spec.get("variant") == "jeemi-vpn-fd":
        for field in ("sourceLock", "patch"):
            path = RESOURCES / spec[field]
            if digest(path.read_bytes()) != spec[field + "Sha256"]:
                raise RuntimeError("The core source or patch no longer matches its lock.")
        source = json.loads((RESOURCES / spec["sourceLock"]).read_text(encoding="utf-8"))
        if digest((RESOURCES / source["archive"]).read_bytes()) != source["sha256"]:
            raise RuntimeError("Mihomo source archive checksum mismatch.")
        for field, name in (("sourceLock", "mihomo-source.json"), ("patch", "mihomo-vpn-fd-patch.json")):
            if digest((ROOT / "resources/notices" / name).read_bytes()) != spec[field + "Sha256"]:
                raise RuntimeError("Packaged core source or patch notice differs from the lock.")
    for item in spec["cores"]:
        data = core_path(item).read_bytes()
        if len(data) != item["bytes"] or digest(data) != item["sha256"]:
            raise RuntimeError(f"Bundled core checksum mismatch: {item['abi']}")
        verify_elf(data, item["abi"])
    print(f"Verified mihomo {spec['version']}: arm64-v8a + x86_64, ELF 16 KB")


def fetch_one(item):
    request = urllib.request.Request(item["url"], headers={"User-Agent": "Jeemi-Android-build"})
    with urllib.request.urlopen(request, timeout=90) as response:
        archive = response.read(32 * 1024 * 1024 + 1)
    if len(archive) > 32 * 1024 * 1024 or digest(archive) != item["archiveSha256"]:
        raise RuntimeError(f"Official archive checksum mismatch: {item['abi']}")
    data = gzip.decompress(archive)
    verify_elf(data, item["abi"])
    if item.get("sha256") and digest(data) != item["sha256"]:
        raise RuntimeError(f"Uncompressed core checksum mismatch: {item['abi']}")
    destination = core_path(item)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".download")
    temporary.write_bytes(data)
    temporary.replace(destination)
    return {**item, "sha256": digest(data), "bytes": len(data)}


def fetch():
    spec = json.loads(LOCK.read_text(encoding="utf-8"))
    if spec.get("variant") == "jeemi-vpn-fd":
        import mihomo_build
        mihomo_build.build()
        return
    with ThreadPoolExecutor(max_workers=2) as pool:
        spec["cores"] = list(pool.map(fetch_one, spec["cores"]))
    LOCK.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    notice = ROOT / "resources/notices/mihomo.json"
    notice.parent.mkdir(parents=True, exist_ok=True)
    notice.write_bytes(LOCK.read_bytes())
    verify()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["fetch", "verify"])
    args = parser.parse_args()
    fetch() if args.action == "fetch" else verify()
