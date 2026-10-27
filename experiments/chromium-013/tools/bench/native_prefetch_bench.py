#!/usr/bin/env python3
"""Isolated ABI-safe native libcef ELF-page prefetch crossover benchmark."""
import argparse
import json
import statistics
from pathlib import Path

from native_packaging_bench import run_block

ORDER = ("off", "prefetch16", "read16", "read16", "prefetch16", "off")
EXPECTED = {"off": 0, "prefetch16": 16, "read16": 16}
MODES = {"off": "mmap", "prefetch16": "mmap", "read16": "read"}


def summarize(records, cold_cache=False):
    groups = {mode: [] for mode in EXPECTED}
    rows = [
        "# CEF native executable pages: bounded three-way palindrome experiment",
        "",
        ("Rooted AOSP emulator: sync + drop_caches=3 before EVERY measured"
         " launch." if cold_cache else
         "No kernel cache reset; results can be affected by ELF cache warming."),
        "Same Android emulator, source, native library extraction, fast attach,",
        "R8, package identity and disposable CI signing key for all three variants.",
        "OFF versus mmap(16 MiB) versus buffered read(16 MiB); every mode",
        "appears twice in symmetrical order to reduce linear warmup bias.",
        "",
        "| Block | Mode | Activity median (ms) | Provider median (ms) | PSS median (MiB) | Major faults (per trial) | Status / reason |",
        "|---:|---|---:|---:|---:|---|---|",
    ]
    for rec in records:
        if rec.get("status") == "measured":
            groups[rec["variant"]].append(rec["activity_median_ms"])
        faults = ",".join(str(x.get("cefrium_startup_resources", {})
                                .get("provider_major_faults", "—"))
                          for x in rec.get("samples", [])) or "—"
        pss = rec.get("pss_median_kib")
        pss_text = f"{pss / 1024:.1f}" if pss is not None else "—"
        # Keep unavailable experiments visible, including the actual reason.
        # Never silently turn a failed native renderer into a fast data point.
        status = rec["status"]
        if rec.get("reason"):
            status += ": " + str(rec["reason"]).replace("|", "/").replace("\n", " ")[:180]
        rows.append(f"| {rec['block']} | {rec['variant']} | "
                    f"{rec.get('activity_median_ms', '—')} | "
                    f"{rec.get('provider_median_ms', '—')} | "
                    f"{pss_text} | {faults} | {status} |")
    medians = {}
    for mode in EXPECTED:
        medians[mode] = (statistics.median(groups[mode])
                         if len(groups[mode]) == ORDER.count(mode) else None)
        result = medians[mode]
        rows.append(f"- {mode} block median: {result:.1f} ms"
                    if result is not None else f"- {mode}: incomplete, no win claim")
    rows += ["", "These are *not* necessarily fresh-device cold starts.",
             "Linux may evict or reuse pages independently of these hints.",
             "Both workers use CPU and I/O; mmap may also increase PSS.",
             "This is NOT a user-facing default, Brave win or first paint result.",
             "Never adopt prefetch unless it improves replicated cold-start",
             "medians without damaging Android 16/17 JS rendering, power or PSS.", ""]
    return "\n".join(rows), medians


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--off", required=True, type=Path)
    parser.add_argument("--prefetch16", required=True, type=Path)
    parser.add_argument("--read16", required=True, type=Path)
    parser.add_argument("--out", default=Path("dist/native-prefetch/results"), type=Path)
    parser.add_argument("--repetitions", type=int, default=2)
    parser.add_argument("--cold-cache", action="store_true",
                        help="Require successful kernel page cache reset per launch on rooted AOSP emulator")
    parser.add_argument("--settle-seconds", type=float, default=4.0,
                        help="Time to allow native CEF and memory to settle before each PSS reading")
    args = parser.parse_args(argv)
    if args.repetitions < 1 or args.repetitions > 6:
        parser.error("repetitions out of range")
    if not 1 <= args.settle_seconds <= 60:
        parser.error("settle-seconds must be in [1, 60]")
    args.out.mkdir(parents=True, exist_ok=True)
    paths = {"off": args.off, "prefetch16": args.prefetch16,
             "read16": args.read16}
    records = []
    for i, mode in enumerate(ORDER, 1):
        try:
            if args.cold_cache:
                rec = run_block(i, mode, paths[mode], args.out, args.repetitions,
                                args.settle_seconds, cold_cache=True)
            else:
                rec = run_block(i, mode, paths[mode], args.out, args.repetitions,
                                args.settle_seconds)
            if rec.get("status") == "measured":
                requested = EXPECTED[mode]
                if any(s.get("cefrium_native_prefetch", {}).get("requested_mib")
                       != requested for s in rec.get("samples", [])):
                    rec["status"] = "unavailable"
                    rec["reason"] = "Runtime logs do not prove configured prefetch setting"
                if any(s.get("cefrium_native_prefetch", {}).get("mode")
                       != MODES[mode] for s in rec.get("samples", [])):
                    rec["status"] = "unavailable"
                    rec["reason"] = "Runtime log does not prove the requested prefetch method"
                if requested and any(
                        s.get("cefrium_native_prefetch", {}).get("status") != "completed"
                        for s in rec.get("samples", [])):
                    rec["status"] = "unavailable"
                    rec["reason"] = "Kernel prefetch window did not complete in every sample"
        except Exception as e:
            rec = {"block": i, "variant": mode, "status": "unavailable",
                   "reason": f"{type(e).__name__}: {e}"}
        records.append(rec)
        print("prefetch block", i, mode, rec["status"],
              rec.get("activity_median_ms"), flush=True)
        (args.out / "results.json").write_text(json.dumps(records, indent=2) + "\n")
    summary, medians = summarize(records, cold_cache=args.cold_cache)
    (args.out / "summary.md").write_text(summary)
    (args.out / "medians.json").write_text(json.dumps(medians, indent=2) + "\n")
    print(summary)
    if any(rec["status"] != "measured" for rec in records):
        raise SystemExit("Prefetch experiment failed rendering or runtime-mode validation")


if __name__ == "__main__":
    main()
