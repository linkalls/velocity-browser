#!/usr/bin/env python3
"""Refuse to present a broken Cefrium Activity stub as a faster browser."""
import argparse
import json
import sys
from pathlib import Path


def validate(report, expected_variant):
    problems = []
    if report.get("velocity_build_variant") != expected_variant:
        problems.append("Unexpected Velocity build variant")
    rec = report.get("browsers", {}).get("Velocity", {})
    if rec.get("status") != "measured":
        problems.append("Velocity did not complete the launch benchmark")
    samples = rec.get("samples", [])
    if len(samples) != report.get("repetitions"):
        problems.append("Velocity trial count does not match requested repetitions")
    for i, sample in enumerate(samples, start=1):
        if not sample.get("cefrium_surface_created"):
            problems.append(f"trial {i}: Cefrium native Surface was not created")
        if not sample.get("process_alive_after_settle"):
            problems.append(f"trial {i}: Velocity process exited within settle interval")
        if not sample.get("cefrium_startup_phases_ms"):
            problems.append(f"trial {i}: Cefrium startup phase instrumentation missing")
        if sample.get("pss_kib_after_settle") is None:
            problems.append(f"trial {i}: process PSS not measured")
    return problems


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("file", type=Path)
    parser.add_argument("--variant", required=True)
    args = parser.parse_args()
    errors = validate(json.loads(args.file.read_text()), args.variant)
    if errors:
        for error in errors:
            print("FAIL:", error, file=sys.stderr)
        raise SystemExit(1)
    print("PASS: All Velocity trials instantiated Chromium, retained a live process,"
          " emitted native phase metrics and returned package PSS.")


if __name__ == "__main__":
    main()
