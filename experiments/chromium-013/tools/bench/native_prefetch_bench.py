#!/usr/bin/env python3
"""Isolated ABI-safe native libcef ELF-page prefetch crossover benchmark."""
import argparse
import json
import statistics
from pathlib import Path

from native_packaging_bench import run_block

ORDER = ("off", "prefetch16", "prefetch16", "off")
EXPECTED = {"off": 0, "prefetch16": 16}


def summarize(records):
    groups = {"off": [], "prefetch16": []}
    rows = [
        "# CEF native executable pages: bounded prefetch A/B",
        "",
        "Same Android emulator, source, native library extraction, fast attach,",
        "R8, package identity and disposable CI signing key for both variants.",
        "Only difference is the experimental, bounded async 16-MiB ELF hint.",
        "",
        "| Block | Mode | Activity median | Provider median | Major faults (per trial) | Status |",
        "|---:|---|---:|---:|---|---|",
    ]
    for rec in records:
        if rec.get("status") == "measured":
            groups[rec["variant"]].append(rec["activity_median_ms"])
        faults = ",".join(str(x.get("cefrium_startup_resources", {})
                                .get("provider_major_faults", "—"))
                          for x in rec.get("samples", [])) or "—"
        rows.append(f"| {rec['block']} | {rec['variant']} | "
                    f"{rec.get('activity_median_ms', '—')} | "
                    f"{rec.get('provider_median_ms', '—')} | "
                    f"{faults} | {rec['status']} |")
    medians = {}
    for mode in ("off", "prefetch16"):
        medians[mode] = (statistics.median(groups[mode])
                         if len(groups[mode]) == ORDER.count(mode) else None)
        result = medians[mode]
        rows.append(f"- {mode} block median: {result:.1f} ms"
                    if result is not None else f"- {mode}: incomplete, no win claim")
    rows += ["", "These are *not* necessarily fresh-device cold starts. ",
             "Linux may ignore load hints and cached pages may be evicted; the",
             "extra mapping has its own CPU, page-cache and memory costs.",
             "This is NOT a user-facing default, Brave win or first paint result.",
             "Never adopt prefetch unless it improves replicated cold-start",
             "medians without damaging Android 16/17 JS rendering, power or PSS.", ""]
    return "\n".join(rows), medians


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--off", required=True, type=Path)
    parser.add_argument("--prefetch16", required=True, type=Path)
    parser.add_argument("--out", default=Path("dist/native-prefetch/results"), type=Path)
    parser.add_argument("--repetitions", type=int, default=2)
    args = parser.parse_args()
    if args.repetitions < 1 or args.repetitions > 6:
        parser.error("repetitions out of range")
    args.out.mkdir(parents=True, exist_ok=True)
    paths = {"off": args.off, "prefetch16": args.prefetch16}
    records = []
    for i, mode in enumerate(ORDER, 1):
        try:
            rec = run_block(i, mode, paths[mode], args.out, args.repetitions, 4)
            if rec.get("status") == "measured":
                requested = EXPECTED[mode]
                if any(s.get("cefrium_native_prefetch", {}).get("requested_mib")
                       != requested for s in rec.get("samples", [])):
                    rec["status"] = "unavailable"
                    rec["reason"] = "Runtime logs do not prove configured prefetch setting"
                if requested and any(
                        s.get("cefrium_native_prefetch", {}).get("status") != "completed"
                        for s in rec.get("samples", [])):
                    rec["status"] = "unavailable"
                    rec["reason"] = "Kernel prefetch advice did not complete in every sample"
        except Exception as e:
            rec = {"block": i, "variant": mode, "status": "unavailable",
                   "reason": f"{type(e).__name__}: {e}"}
        records.append(rec)
        print("prefetch block", i, mode, rec["status"],
              rec.get("activity_median_ms"), flush=True)
        (args.out / "results.json").write_text(json.dumps(records, indent=2) + "\n")
    summary, medians = summarize(records)
    (args.out / "summary.md").write_text(summary)
    (args.out / "medians.json").write_text(json.dumps(medians, indent=2) + "\n")
    print(summary)
    if any(rec["status"] != "measured" for rec in records):
        raise SystemExit("Prefetch experiment failed rendering or runtime-mode validation")


if __name__ == "__main__":
    main()
