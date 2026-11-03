#!/usr/bin/env python3
"""Exploratory paired ART DEX compilation trial on the same Velocity APK.

This does NOT modify release signing, APK content, or native libcef.so.
Four ABBA blocks reduce (but do not eliminate) time/order confounding.
"""
import argparse
import json
import statistics
import subprocess
import sys
from pathlib import Path

MODES = ("verify", "speed", "speed", "verify")
PACKAGE = "dev.velocity.browser.chromium013"


def summarize(records):
    by_mode = {"verify": [], "speed": []}
    for rec in records:
        if rec.get("status") == "measured" and rec.get("launch_median_ms") is not None:
            by_mode[rec["art_mode"]].append(rec["launch_median_ms"])
    medians = {mode: statistics.median(samples) if samples else None
               for mode, samples in by_mode.items()}
    lines = [
        "# Android Runtime ART compilation crossover (exploratory)",
        "",
        "One same-commit, same-signature Velocity APK. Four blocks in verify/speed/speed/verify order.",
        "ART verify and ART speed are different DEX compilation modes; native CEF loading is unchanged.",
        "",
        "| Block | ART mode | Median Activity start (ms) | Result |",
        "|---:|---|---:|---|",
    ]
    for rec in records:
        value = rec.get("launch_median_ms")
        lines.append(f"| {rec['block']} | {rec['art_mode']} | "
                     f"{value if value is not None else '—'} | {rec['status']} |")
    lines += ["", "## Combined medians (per-block)", ""]
    for mode in MODES[:2]:
        value = medians[mode]
        lines.append(f"- {mode}: {value:.1f} ms" if value is not None
                     else f"- {mode}: unavailable")
    lines += [
        "",
        "**Do not claim faster-than-Brave:** This crossover tests only Velocity.",
        "Verification and speed profiles may interact with system caches, CPU thermal state,",
        "emulator variability, and the kernel/native library's independent startup cost.",
        "No first-contentful-paint or JS throughput is measured.",
        "",
    ]
    return "\n".join(lines), medians


def run_one_block(number, mode, out, samples, settle_seconds):
    dest = out / f"block-{number}-{mode}"
    dest.mkdir(parents=True, exist_ok=True)
    command = ["adb", "shell", "cmd", "package", "compile", "-f", "-m", mode, PACKAGE]
    compiled = subprocess.run(command, capture_output=True, text=True, timeout=240)
    diagnostics = compiled.stdout + "\n" + compiled.stderr
    (dest / "dexopt.txt").write_text(diagnostics)
    record = {"block": number, "art_mode": mode, "status": "unavailable",
              "compile_command": command, "compile_output": diagnostics[-500:]}
    if compiled.returncode or "Success" not in diagnostics:
        record["reason"] = "ART compilation did not report Success"
        return record
    bench_cmd = [
        sys.executable, str(Path(__file__).with_name("browser_bench.py")),
        "--only", "Velocity", "--repetitions", str(samples),
        "--settle-seconds", str(settle_seconds), "--out", str(dest),
    ]
    measured = subprocess.run(bench_cmd, capture_output=True, text=True, timeout=450)
    (dest / "bench-stdout.txt").write_text(
        measured.stdout + "\n" + measured.stderr)
    if measured.returncode:
        record["reason"] = f"Benchmark exited with {measured.returncode}"
        return record
    raw = dest / "results.json"
    if not raw.exists():
        record["reason"] = "Benchmark did not produce results.json"
        return record
    result = json.loads(raw.read_text())
    velocity = result.get("browsers", {}).get("Velocity", {})
    samples_list = velocity.get("samples", [])
    if (velocity.get("status") != "measured"
            or len(samples_list) != samples
            or any(not sample.get("cefrium_surface_created") or
                   not sample.get("process_alive_after_settle")
                   for sample in samples_list)):
        record["reason"] = "No validated live Cefrium Surface across all trials"
        return record
    record["status"] = "measured"
    record["launch_median_ms"] = velocity.get("median_activity_launch_ms")
    record["pss_median_kib"] = velocity.get("median_pss_kib")
    record["surface_ready_ms"] = velocity.get("median_cefrium_surface_ready_ms")
    return record


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path,
                   default=Path("dist/browser-bench/art-crossover"))
    p.add_argument("--repetitions", type=int, default=3)
    p.add_argument("--settle-seconds", type=float, default=4)
    args = p.parse_args()
    if not 1 <= args.repetitions <= 8:
        p.error("repetitions must be between 1 and 8")
    args.out.mkdir(parents=True, exist_ok=True)
    blocks = []
    for i, mode in enumerate(MODES, start=1):
        try:
            outcome = run_one_block(i, mode, args.out,
                                    args.repetitions, args.settle_seconds)
        except (OSError, subprocess.TimeoutExpired, ValueError) as e:
            outcome = {"block": i, "art_mode": mode, "status": "unavailable",
                       "reason": type(e).__name__ + ": " + str(e)}
        blocks.append(outcome)
        print("ART block", i, mode, outcome["status"],
              outcome.get("launch_median_ms"), flush=True)
    summary, medians = summarize(blocks)
    (args.out / "crossover.json").write_text(
        json.dumps({"type": "exploratory_abba", "blocks": blocks,
                    "median_by_mode_ms": medians}, indent=2) + "\n")
    (args.out / "summary.md").write_text(summary)
    print(summary)


if __name__ == "__main__":
    main()
