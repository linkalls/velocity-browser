#!/usr/bin/env python3
"""Audit an AAR without enabling native mode or claiming runtime success."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ENTRY = "NativeChildProcessService_onCreate"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def defined_entry(table):
    """Accept only an externally visible, defined function in .dynsym."""
    for line in table.splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].endswith(":"):
            continue
        name = fields[7]
        if name != ENTRY and not name.startswith(ENTRY + "@@"):
            continue
        if (fields[3] == "FUNC" and fields[4] in ("GLOBAL", "WEAK")
                and fields[5] in ("DEFAULT", "PROTECTED")
                and fields[6].isdigit() and int(fields[6]) > 0):
            return True
    return False


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def audit(aar, output, expected_sha256, abis):
    actual = sha256(aar)
    if actual != expected_sha256:
        raise ValueError(f"AAR checksum mismatch: {actual}")
    output.mkdir(parents=True, exist_ok=True)
    report = {"aar_sha256": actual, "entry": ENTRY, "abis": {},
              "runtime": "NOT TESTED", "apk": "NOT BUILT"}
    with zipfile.ZipFile(aar) as archive:
        manifest_bytes = archive.read("AndroidManifest.xml")
        manifest = ET.fromstring(manifest_bytes)
        (output / "aar-manifest.xml").write_bytes(manifest_bytes)
        names = [s.get(ANDROID + "name", "") for s in manifest.findall(".//service")]
        report["aar_manifest"] = {
            "java_sandboxed_services": sum(n.startswith(
                "org.chromium.content.app.SandboxedProcessService") for n in names),
            "native_services": sum(n.startswith(
                "org.chromium.content.app.NativeOnlySandboxedProcessService") for n in names),
            "scope": "AAR source manifest; not a merged APK audit"}
        for abi in abis:
            member = f"jni/{abi}/libcef.so"
            with tempfile.TemporaryDirectory() as tmp:
                library = Path(tmp) / "libcef.so"
                with archive.open(member) as src, library.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
                tables = {}
                for label, command in (
                        ("dyn-syms", ["readelf", "--dyn-syms", "-W"]),
                        ("nm-d", ["nm", "-D", "-C"]),
                        ("elf-header", ["readelf", "-h"]),
                        ("dynamic", ["readelf", "-d"])):
                    tables[label] = subprocess.check_output(
                        command + [str(library)], text=True)
                    (output / f"{abi}-{label}.txt").write_text(tables[label])
                report["abis"][abi] = {
                    "member": member, "sha256": sha256(library),
                    "entry_defined_and_exported": defined_entry(tables["dyn-syms"])}
    report["export_gate"] = "PASS" if all(
        a["entry_defined_and_exported"] for a in report["abis"].values()) else "FAIL"
    report["native_renderer_ready"] = False  # Export alone cannot prove initialization/IPC.
    (output / "audit.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--abi", action="append", choices=("arm64-v8a", "x86_64"))
    parser.add_argument("--require-native-export", action="store_true",
                        help="Fail if the defined dynamic entry is missing; not a runtime test")
    args = parser.parse_args()
    result = audit(args.aar, args.output, args.sha256, args.abi or ["arm64-v8a"])
    print(json.dumps(result, indent=2))
    return 2 if args.require_native_export and result["export_gate"] != "PASS" else 0


if __name__ == "__main__":
    raise SystemExit(main())
