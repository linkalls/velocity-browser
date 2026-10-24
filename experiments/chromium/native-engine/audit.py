#!/usr/bin/env python3
"""Audit an AAR without enabling native mode or claiming runtime success."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ENTRY = "NativeChildProcessService_onCreate"
ANDROID = "{http://schemas.android.com/apk/res/android}"
SERVICE_PREFIX_KEY = "org.chromium.content.browser.SANDBOXED_SERVICES_NAME"
SERVICE_COUNT_KEY = "org.chromium.content.browser.NUM_SANDBOXED_SERVICES"
DEFAULT_SERVICE_PREFIX = "org.chromium.content.app.SandboxedProcessService"


def audit_manifest(manifest):
    """Follow the launcher's application metadata, then validate the declared pool.

    Cefrium 0.11+ uses its own Java class names. A name beginning with the old
    Chromium prefix is neither necessary nor sufficient to identify a child.
    This inspects source XML only, not Android's merged manifest or runtime UID.
    """
    application = manifest.find("application")
    if application is None:
        raise ValueError("AAR manifest has no application")
    metadata = {m.get(ANDROID + "name"): m.get(ANDROID + "value")
                for m in application.findall("meta-data")}
    package = manifest.get("package", "")

    def class_name(name):
        if name.startswith("."):
            return package + name
        return name if "." in name else package + "." + name

    # The launcher uses this metadata string literally. Android resolves
    # relative service android:name attributes, but not arbitrary metadata.
    prefix = metadata.get(SERVICE_PREFIX_KEY) or DEFAULT_SERVICE_PREFIX
    count_value = metadata.get(SERVICE_COUNT_KEY, "")
    count = int(count_value) if count_value and count_value.isdecimal() else None
    # Native services are identified by their actual Android attribute, not a
    # Java class name that could be missing its required nativeService flag.
    services = application.findall("service")
    native_services = [s for s in services if s.get(ANDROID + "nativeService") == "true"]
    pool = []
    for service in services:
        name = class_name(service.get(ANDROID + "name", ""))
        match = re.fullmatch(re.escape(prefix) + r"(0|[1-9][0-9]*)", name)
        if match and service.get(ANDROID + "nativeService") != "true":
            pool.append({
                "name": name, "slot": int(match.group(1)),
                "isolated": service.get(ANDROID + "isolatedProcess") == "true",
                "exported": service.get(ANDROID + "exported"),
                "external_service": service.get(ANDROID + "externalService"),
                "use_app_zygote": service.get(ANDROID + "useAppZygote") == "true"})
    slots = [s["slot"] for s in pool]
    # Compare a sorted sequence instead of allocating a set of arbitrary size
    # from the manifest count. Duplicate, absent and extra slots all fail.
    complete = (count is not None and count > 0 and len(slots) == count
                and all(slot == i for i, slot in enumerate(sorted(slots))))
    return {
        "sandboxed_services_base": prefix,
        "declared_sandboxed_service_count": count,
        "java_sandboxed_services": len(pool),
        "java_services": sorted(pool, key=lambda s: s["slot"]),
        "java_service_pool_gate": "PASS" if complete and all(
            s["isolated"] for s in pool) else "FAIL",
        "native_services": len(native_services),
        "scope": "AAR source manifest; not a merged APK audit"}


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
    if not abis:
        raise ValueError("At least one ABI must be audited")
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
        report["aar_manifest"] = audit_manifest(manifest)
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
    parser.add_argument("--require-java-pool", action="store_true",
                        help="Fail if Java child slots are missing, duplicated or not isolated")
    args = parser.parse_args()
    result = audit(args.aar, args.output, args.sha256, args.abi or ["arm64-v8a"])
    print(json.dumps(result, indent=2))
    failed = ((args.require_native_export and result["export_gate"] != "PASS")
              or (args.require_java_pool
                  and result["aar_manifest"]["java_service_pool_gate"] != "PASS"))
    return 2 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
