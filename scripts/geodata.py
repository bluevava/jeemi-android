"""Fetch and verify the pinned GEO snapshot shared by both Android APKs."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / "resources/geodata"
MANIFEST = DIRECTORY / "manifest.json"
ASSETS = DIRECTORY / "assets/geodata"


def spec():
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    names = {item["file"] for item in data["files"]}
    if names != {"geoip.metadb", "GeoSite.dat", "ASN.mmdb"} or len(data["files"]) != 3:
        raise ValueError("Unexpected GEO snapshot contents")
    return data


def checked(item, contents):
    if len(contents) != item["bytes"] or hashlib.sha256(contents).hexdigest() != item["sha256"]:
        raise ValueError(f"GEO snapshot checksum mismatch: {item['file']}")
    return contents


def verify():
    data = spec()
    for item in data["files"]:
        checked(item, (ASSETS / item["file"]).read_bytes())
    if json.loads((ASSETS / "manifest.json").read_text(encoding="utf-8")) != data:
        raise ValueError("Packaged GEO manifest differs from the lock")
    print(f"Verified GEO snapshot {data['snapshot']}: MetaDB + GeoSite + ASN")


def fetch_one(item):
    # The asset ID identifies the captured release object. The digest rejects
    # any drift if GitHub no longer serves that object and only latest is live.
    failures = []
    for url in [item["assetUrl"], item["url"]]:
        try:
            request = urllib.request.Request(url, headers={
                "User-Agent": "Jeemi-Android-build", "Accept": "application/octet-stream",
            })
            with urllib.request.urlopen(request, timeout=60) as response:
                contents = response.read(item["bytes"] + 1)
            checked(item, contents)
            ASSETS.mkdir(parents=True, exist_ok=True)
            stage = ASSETS / (item["file"] + ".download")
            stage.write_bytes(contents)
            stage.replace(ASSETS / item["file"])
            return
        except Exception as failure:
            failures.append(type(failure).__name__)
    raise RuntimeError(f"Unable to fetch locked GEO asset {item['file']}: {failures}")


def fetch():
    data = spec()
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(fetch_one, data["files"]))
    (ASSETS / "manifest.json").write_bytes(MANIFEST.read_bytes())
    verify()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["fetch", "verify"])
    args = parser.parse_args()
    fetch() if args.action == "fetch" else verify()
