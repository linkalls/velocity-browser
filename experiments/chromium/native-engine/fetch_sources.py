#!/usr/bin/env python3
"""Fetch only locked source files for Phase A; this is not a Chromium checkout."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads(Path(__file__).with_name("upstream-lock.json").read_text())

    def fetch(item):
        with urllib.request.urlopen(item["url"], timeout=60) as response:
            data = response.read()
        if item["encoding"] == "base64":
            data = base64.b64decode(data)
        actual = hashlib.sha256(data).hexdigest()
        if actual != item["sha256"]:
            raise ValueError(f"Source hash mismatch: {item['path']} ({actual})")
        path = args.output / item["path"]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return {"path": item["path"], "sha256": actual, "url": item["url"]}

    with ThreadPoolExecutor(max_workers=4) as pool:
        evidence = list(pool.map(fetch, lock["sources"]))
    (args.output / "verified-sources.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(f"Verified {len(evidence)} pinned source files (not a complete build tree)")


if __name__ == "__main__":
    main()
