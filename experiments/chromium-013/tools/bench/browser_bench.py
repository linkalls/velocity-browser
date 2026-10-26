#!/usr/bin/env python3
"""Reproducible Android Activity cold-start/PSS measurements.

This intentionally does not pretend to measure first page paint or Speedometer.
Uninstalled/blocked competitors are reported as unavailable, never zero.
"""
import argparse
import datetime as dt
import json
import re
import statistics
import subprocess
import time
from pathlib import Path

BROWSERS = {
    "Velocity": "dev.velocity.browser.chromium013",
    "Chrome": "com.android.chrome",
    "Brave": "com.brave.browser",
    "Vivaldi": "com.vivaldi.browser",
}


def run_adb(*args, timeout=30):
    cp = subprocess.run(["adb", *args], capture_output=True, text=True,
                        timeout=timeout, check=False)
    return cp.returncode, cp.stdout + "\n" + cp.stderr


def parse_launch(output, package):
    """`am start -W` measures Activity launch, not completed page rendering."""
    status = re.search(r"^Status:\s*(\w+)", output, re.M)
    elapsed = re.search(r"^TotalTime:\s*(\d+)", output, re.M)
    activity = re.search(r"^Activity:\s*(\S+)", output, re.M)
    if not status or status.group(1).lower() != "ok" or not elapsed:
        return None
    if activity and not activity.group(1).startswith(package + "/"):
        return None
    return int(elapsed.group(1))


def parse_launch_state(output):
    match = re.search(r"^LaunchState:\s*(\w+)", output, re.M)
    return match.group(1).upper() if match else None


def screenshot(directory, name):
    """Capture the actual last displayed screen to audit onboarding/blank UI."""
    try:
        result = subprocess.run(["adb", "exec-out", "screencap", "-p"],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=20, check=False)
        if result.returncode == 0 and result.stdout.startswith(b"\x89PNG"):
            (directory / (name.lower() + "-last-screen.png")).write_bytes(result.stdout)
    except (OSError, subprocess.TimeoutExpired):
        pass


def parse_pss(output):
    """Prefer aggregate TOTAL PSS. Return KiB, never guess from Java heap."""
    # Standard Android dumpsys `TOTAL PSS: 123456` format.
    totals = re.findall(r"^\s*TOTAL PSS:\s*([\d,]+)", output, re.M)
    if totals:
        return int(totals[-1].replace(",", ""))
    # Some versions print an aggregate TOTAL line instead.
    totals = re.findall(r"^\s*TOTAL\s+([\d,]+)\s+", output, re.M)
    if totals:
        return int(totals[-1].replace(",", ""))
    return None


def parse_velocity_timing(log):
    """Parse numeric-only phase timings emitted by the Cefrium startup path."""
    match = re.search(
        r"CEFStartupTiming provider_ms=(-?\d+) ui_build_ms=(\d+) ui_wait_ms=(\d+) "
        r"ui_ms=(\d+) pool_ms=(\d+) rules_ms=(\d+) "
        r"create_ms=(\d+) total_ms=(\d+)", log
    )
    if match is None:
        return None
    return {key: int(value) for key, value in zip(
        ("provider_ms", "ui_build_ms", "ui_wait_ms", "ui_ms", "pool_ms",
         "rules_ms", "create_ms", "total_ms"),
        match.groups()
    )}


def resolve_launcher(package):
    rc, output = run_adb("shell", "cmd", "package", "resolve-activity", "--brief",
                         "-a", "android.intent.action.MAIN", "-c",
                         "android.intent.category.LAUNCHER", "-p", package)
    if rc:
        return None
    for line in reversed(output.splitlines()):
        name = line.strip()
        if name.startswith(package + "/") and " " not in name:
            return name
    return None


def installed_version(package):
    rc, output = run_adb("shell", "dumpsys", "package", package)
    if rc or "Unable to find package" in output:
        return None
    found = re.search(r"\bversionName=([^\s]+)", output)
    return found.group(1) if found else "unknown"


def sample_browser(package, launcher, repetitions, settle_seconds, output_dir=None, name=None):
    samples = []
    for idx in range(repetitions):
        run_adb("shell", "am", "force-stop", package)
        if package == BROWSERS["Velocity"]:
            run_adb("logcat", "-c")
        time.sleep(1.0)
        rc, launch = run_adb("shell", "am", "start", "-W", "-n", launcher)
        launch_ms = parse_launch(launch, package) if rc == 0 else None
        if launch_ms is None:
            return {"status": "unavailable", "reason": "am start -W failed",
                    "diagnostic": launch[-300:]}
        time.sleep(settle_seconds)
        _, meminfo = run_adb("shell", "dumpsys", "meminfo", package)
        pss = parse_pss(meminfo)
        entry = {"iteration": idx + 1, "activity_launch_ms": launch_ms,
                 "launch_state": parse_launch_state(launch),
                 "pss_kib_after_settle": pss}
        if package == BROWSERS["Velocity"]:
            _, log = run_adb("logcat", "-d", "-s", "VelocityCefStartup:I", "*:S")
            entry["cefrium_startup_phases_ms"] = parse_velocity_timing(log)
        samples.append(entry)
    if output_dir is not None and name is not None:
        screenshot(output_dir, name)
    pss_samples = [x["pss_kib_after_settle"] for x in samples if x["pss_kib_after_settle"] is not None]
    return {
        "status": "measured", "samples": samples,
        "median_activity_launch_ms": statistics.median(x["activity_launch_ms"] for x in samples),
        "median_pss_kib": statistics.median(pss_samples) if pss_samples else None,
        "pss_samples": len(pss_samples),
    }


def markdown_report(data):
    lines = ["# Android browser baseline", "",
        "Cold Activity launch and settled package PSS only — **NOT first paint, Speedometer, scrolling or JS throughput**.",
        "", "| Browser | Version | Activity launch median (ms) | Settled PSS median (MiB) | Samples | Status |",
        "|---|---|---:|---:|---:|---|"]
    for name, record in data["browsers"].items():
        def num(key, factor=1):
            v = record.get(key)
            return "—" if v is None else str(round(v / factor, 1))
        lines.append(f"| {name} | {record.get('version') or '—'} | "
                     f"{num('median_activity_launch_ms')} | {num('median_pss_kib', 1024)} | "
                     f"{len(record.get('samples', []))} | {record['status']} |")
    velocity = data["browsers"].get("Velocity", {})
    phase_samples = [x.get("cefrium_startup_phases_ms")
                     for x in velocity.get("samples", [])
                     if x.get("cefrium_startup_phases_ms")]
    if phase_samples:
        lines += ["", "## Velocity native startup breakdown (median, ms)", "",
                  "These phase times are instrumented inside Velocity only; "
                  "they do not represent first paint.", ""]
        for phase in ("provider_ms", "ui_build_ms", "ui_wait_ms", "ui_ms",
                      "pool_ms", "rules_ms", "create_ms", "total_ms"):
            values = [x[phase] for x in phase_samples if x.get(phase, -1) >= 0]
            lines.append(f"- {phase}: {statistics.median(values):.0f} ms" if values
                         else f"- {phase}: unavailable")
    lines += ["", "## Measurement limitations", "",
        "- Same x86_64 Android emulator instance, one foreground browser at a time, forced stop before each start.",
        "- Startup time is Android Activity TotalTime; it **does not** ensure a rendered web page.",
        "- PSS is Android package `dumpsys meminfo` after a fixed settle delay; service-process attribution can vary.",
        "- All four are release-mode binaries, but Chrome is the emulator's preinstalled (possibly older) build.",
        "- Startup screenshots of all four browsers are saved to detect onboarding, permission prompts or blank rendering.",
        "- First-run onboarding, installed app versions, network, cache and emulator performance affect comparability.",
        "- Unavailable packages remain unavailable; missing memory samples are not zero.",
        "- No competitor-specific anti-tracking comparisons or Speedometer claims are made here.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path("dist/browser-bench"))
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--settle-seconds", type=float, default=6)
    args = parser.parse_args()
    if not 1 <= args.repetitions <= 10 or not 0 <= args.settle_seconds <= 120:
        parser.error("repetitions or settle time outside allowed bounds")
    args.out.mkdir(parents=True, exist_ok=True)
    _, device = run_adb("shell", "getprop", "ro.build.fingerprint")
    _, abi = run_adb("shell", "getprop", "ro.product.cpu.abi")
    data = {"timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "device_fingerprint": device.strip(), "abi": abi.strip(),
            "repetitions": args.repetitions, "settle_seconds": args.settle_seconds,
            "velocity_build_variant": "release (not debuggable), ephemeral CI signature",
            "browsers": {}}
    for name, package in BROWSERS.items():
        version = installed_version(package)
        if version is None:
            record = {"status": "unavailable", "reason": "package not installed"}
        else:
            launcher = resolve_launcher(package)
            record = (sample_browser(package, launcher, args.repetitions, args.settle_seconds,
                                     args.out, name)
                      if launcher else {"status": "unavailable", "reason": "no resolvable launcher"})
            record["version"] = version
        record["package"] = package
        data["browsers"][name] = record
        print(name, record["status"], record.get("median_activity_launch_ms"),
              record.get("median_pss_kib"), flush=True)
    (args.out / "results.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    (args.out / "summary.md").write_text(markdown_report(data) + "\n")
    print(markdown_report(data))


if __name__ == "__main__":
    main()
