#!/usr/bin/env python3
"""Verify whether mmap-friendly APK JNI packaging improves Cefrium startup.

Runs separate APKs with the same version, package and disposable CI signature
on one emulator. This is experimental: order effects and APK reinstalling can
still bias startup. Fails closed for broken Chromium or JavaScript rendering.
"""
import argparse
import importlib.util
import json
import statistics
import subprocess
import time
import zipfile
from pathlib import Path

BENCH_PATH = Path(__file__).with_name("browser_bench.py")
SPEC = importlib.util.spec_from_file_location("browser_bench", BENCH_PATH)
bench = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bench)

PACKAGE = bench.BROWSERS["Velocity"]
DIAGNOSTIC_ACTIVITY = PACKAGE + "/dev.velocity.chromium.StartupDiagnosticActivity"
BLOCKS = ("legacy", "mmap", "mmap", "legacy")
NATIVE_SO = "lib/x86_64/libcef.so"


def packaging_info(path):
    with zipfile.ZipFile(path) as apk:
        info = apk.getinfo(NATIVE_SO)
    return {"apk": str(path),
            "native_library": NATIVE_SO,
            "compression": "STORED" if info.compress_type == zipfile.ZIP_STORED
                           else "DEFLATED" if info.compress_type == zipfile.ZIP_DEFLATED
                           else f"unknown-{info.compress_type}",
            "lib_size": info.file_size, "lib_compressed_size": info.compress_size}


def summarize(blocks, metadata):
    records = {"legacy": [], "mmap": []}
    lines = [
        "# Cefrium native library packaging ABBA experiment", "",
        "Identical source revision, same package/version, same disposable CI key,",
        "x86_64 R8 release; **fast attach enabled for BOTH variants**.",
        "A/B design: legacy, mmap, mmap, legacy. All samples force-stop the browser.",
        "Each variant must load a native CEF Surface and execute JS after DOMContentLoaded.",
        "",
        "| Variant | JNI library ZIP method | ELF file size |",
        "|---|---|---:|",
    ]
    for name in ("legacy", "mmap"):
        info = metadata[name]
        lines.append(f"| {name} | {info['compression']} | {info['lib_size']} |")
    lines += ["", "| Block | Packaging | Launch median (ms) | Provider median (ms) | PSS MiB | Real renderer/JS |",
              "|---:|---|---:|---:|---:|---|"]
    for x in blocks:
        if x["status"] == "measured":
            records[x["variant"]].append(x["activity_median_ms"])
        pss = x.get("pss_median_kib")
        memory = f"{pss/1024:.1f}" if pss is not None else "—"
        numeric = lambda value: str(value) if value is not None else "—"
        lines.append(f"| {x['block']} | {x['variant']} | "
                     f"{numeric(x.get('activity_median_ms'))} | "
                     f"{numeric(x.get('provider_median_ms'))} | {memory} | {x['status']} |")
    lines += ["", "## All-block medians", ""]
    medians = {}
    for mode in ("legacy", "mmap"):
        medians[mode] = (statistics.median(records[mode])
                         if len(records[mode]) == BLOCKS.count(mode) else None)
        result = medians[mode]
        lines.append(f"- {mode}: {result:.1f} ms" if result is not None
                     else f"- {mode}: **unavailable / incomplete**, no win claim")
    lines += [
        "",
        "**Not a Brave comparison:** this isolates JNI packaging on one emulator.",
        "Reinstalls retain the same app profile; CPU cache/thermal state, renderer",
        "processes, native SO page cache and execution order can still bias results.",
        "A mode that cannot run the renderer/DOM+JS fixture is rejected.",
        "Do not change production legacy packaging without successful Android 16/17",
        "and physical-device correctness plus replicated startup improvement.",
        "",
    ]
    return "\n".join(lines), medians


def synthetic_js_smoke(timeout_seconds=22):
    bench.run_adb("shell", "am", "force-stop", PACKAGE)
    bench.run_adb("logcat", "-c")
    rc, launched = bench.run_adb(
        "shell", "am", "start", "-W", "-n", DIAGNOSTIC_ACTIVITY,
        "--ez", "smoke_test", "true", timeout=35)
    if rc != 0 or bench.parse_launch(launched, PACKAGE) is None:
        return False, "Diagnostic Activity failed to launch"
    elapsed = 0
    while elapsed < timeout_seconds:
        time.sleep(2)
        elapsed += 2
        _, log = bench.run_adb("logcat", "-d", "-s", "VelocityCefStartup:I", "*:S")
        if "CEF synthetic document title observed" in log and \
                "CEFPageTiming synthetic_dom_ready_ms=" in log:
            _, alive = bench.run_adb("shell", "pidof", PACKAGE)
            return bool(any(part.isdigit() for part in alive.split())), log[-1600:]
    return False, "No DOM+JavaScript completion: " + log[-1600:]


def run_block(number, variant, path, directory, repetitions, settle):
    result = {"block": number, "variant": variant, "status": "unavailable"}
    rc, output = bench.run_adb("install", "-r", str(path), timeout=180)
    (directory / f"block-{number}-{variant}-install.txt").write_text(output)
    if rc != 0 or "Success" not in output:
        result["reason"] = "Package install failed: " + output[-500:]
        return result
    version = bench.installed_version(PACKAGE)
    launcher = bench.resolve_launcher(PACKAGE)
    if not version or not launcher:
        result["reason"] = "Installed package/launcher missing"
        return result

    # Unmeasured launch after reinstall to account for Android package dexopt.
    bench.run_adb("shell", "am", "force-stop", PACKAGE)
    rc, warmed = bench.run_adb("shell", "am", "start", "-W", "-n", launcher)
    result["warmup_ms"] = bench.parse_launch(warmed, PACKAGE) if rc == 0 else None
    if result["warmup_ms"] is None:
        result["reason"] = "Warmup Activity did not start"
        return result
    time.sleep(2)
    bench.run_adb("shell", "am", "force-stop", PACKAGE)

    readings = bench.sample_browser(PACKAGE, launcher, repetitions, settle)
    result["samples"] = readings.get("samples", [])
    if readings.get("status") != "measured" or len(result["samples"]) != repetitions:
        result["reason"] = readings.get("reason", "Incomplete Activity trials")
        return result
    if not all(x.get("cefrium_surface_created")
               and x.get("process_alive_after_settle")
               and x.get("cefrium_start_strategy") == "attach"
               and x.get("cefrium_startup_phases_ms")
               and x.get("pss_kib_after_settle") is not None
               for x in result["samples"]):
        result["reason"] = "Actual native CEF startup/memory/attach verification failed"
        return result

    js_ok, detail = synthetic_js_smoke()
    (directory / f"block-{number}-{variant}-js-log.txt").write_text(detail)
    if not js_ok:
        result["reason"] = "Real renderer JS smoke failed: " + detail[-400:]
        return result

    result["status"] = "measured"
    result["activity_median_ms"] = readings["median_activity_launch_ms"]
    result["pss_median_kib"] = readings["median_pss_kib"]
    phases = [x["cefrium_startup_phases_ms"]["provider_ms"]
              for x in result["samples"] if x["cefrium_startup_phases_ms"]["provider_ms"] >= 0]
    result["provider_median_ms"] = statistics.median(phases) if len(phases) == repetitions else None
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy", type=Path, required=True)
    parser.add_argument("--mmap", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("dist/native-packaging/results"))
    parser.add_argument("--repetitions", type=int, default=2)
    parser.add_argument("--settle-seconds", type=float, default=4.0)
    args = parser.parse_args()
    if not 1 <= args.repetitions <= 6:
        parser.error("repetitions must be 1-6")
    args.out.mkdir(parents=True, exist_ok=True)
    metadata = {name: packaging_info(path) for name, path in (
        ("legacy", args.legacy), ("mmap", args.mmap))}
    data = {"type": "legacy-vs-mmap-native-ABBA", "order": BLOCKS,
            "packaging": metadata, "blocks": []}
    for i, variant in enumerate(BLOCKS, 1):
        path = args.legacy if variant == "legacy" else args.mmap
        try:
            block = run_block(i, variant, path, args.out, args.repetitions,
                              args.settle_seconds)
        except (OSError, subprocess.TimeoutExpired, ValueError, KeyError) as error:
            block = {"block": i, "variant": variant, "status": "unavailable",
                     "reason": type(error).__name__ + ": " + str(error)}
        data["blocks"].append(block)
        print(f"native packaging block {i} {variant}: {block['status']} "
              f"{block.get('activity_median_ms')}ms", flush=True)
        (args.out / "results.json").write_text(json.dumps(data, indent=2) + "\n")
    summary, medians = summarize(data["blocks"], metadata)
    data["median_by_mode_ms"] = medians
    (args.out / "results.json").write_text(json.dumps(data, indent=2) + "\n")
    (args.out / "summary.md").write_text(summary)
    print(summary)
    # The job must fail when the mmap candidate cannot launch a real renderer.
    if any(rec["status"] != "measured" for rec in data["blocks"]):
        raise SystemExit("One or more packaging variants failed validated native browser startup")


if __name__ == "__main__":
    main()
