#!/usr/bin/env python3
"""Reproducible Android Activity cold-start/PSS measurements.

This intentionally does not pretend to measure first page paint or Speedometer.
Uninstalled/blocked competitors are reported as unavailable, never zero.
"""
import argparse
import datetime as dt
import json
import os
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


RESOURCE_FIELDS = ("wall_ms", "cpu_ms", "minor_faults", "major_faults",
                   "read_bytes", "written_bytes")


def parse_native_prefetch(log):
    modes = re.findall(
        r"CEFNativePrefetch requested_mib=(\d+)(?: mode=(mmap|read))?", log)
    statuses = re.findall(r"CEFNativePrefetch status=([a-z_]+)", log)
    return {"requested_mib": int(modes[-1][0]) if modes else None,
            "mode": modes[-1][1] or None if modes else None,
            "status": statuses[-1] if statuses else None}


def parse_velocity_resources(log):
    """Read-only /proc/self counters captured around provider and Activity.

    Counters are missing (-1) if Android blocks procfs, not "zero I/O".
    """
    logs = re.findall(r"CEFStartupResource\s+([^\r\n]+)", log)
    if not logs:
        return None
    matched = {name: int(value) for name, value in
               re.findall(r"([a-z_]+)=(-?\d+)", logs[-1])}
    keys = tuple(f"{period}_{field}" for period in ("provider", "activity", "total")
                 for field in RESOURCE_FIELDS)
    return {key: matched.get(key, -1) for key in keys} if all(
        key in matched for key in keys) else None


def parse_velocity_strategy(log):
    """Read the actual startup path, rather than assuming the build flag worked."""
    strategies = re.findall(r"CEFStartupStrategy strategy=(post|attach)", log)
    return strategies[-1] if strategies else None


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
            entry["cefrium_startup_resources"] = parse_velocity_resources(log)
            entry["cefrium_native_prefetch"] = parse_native_prefetch(log)
            entry["cefrium_start_strategy"] = parse_velocity_strategy(log)
            entry["cefrium_surface_created"] = (
                "CEF surface created and page navigation started" in log)
            _, alive = run_adb("shell", "pidof", package)
            entry["process_alive_after_settle"] = any(
                x.isdigit() for x in alive.strip().split())
        samples.append(entry)
    if output_dir is not None and name is not None:
        screenshot(output_dir, name)
    pss_samples = [x["pss_kib_after_settle"] for x in samples if x["pss_kib_after_settle"] is not None]
    ready_values = [s["cefrium_startup_phases_ms"]["provider_ms"]
                    + s["cefrium_startup_phases_ms"]["total_ms"]
                    for s in samples if s.get("cefrium_startup_phases_ms") and
                    s["cefrium_startup_phases_ms"].get("provider_ms", -1) >= 0]
    return {
        "status": "measured", "samples": samples,
        "median_activity_launch_ms": statistics.median(x["activity_launch_ms"] for x in samples),
        "median_pss_kib": statistics.median(pss_samples) if pss_samples else None,
        "median_cefrium_surface_ready_ms": (
            statistics.median(ready_values) if len(ready_values) == repetitions else None),
        "pss_samples": len(pss_samples),
    }


def rotated_trials(names, repetitions):
    names = list(names)
    if not names:
        return []
    return [names[i % len(names):] + names[:i % len(names)]
            for i in range(repetitions)]


def aggregate(samples):
    if not samples:
        return {"status": "unavailable", "reason": "No successful trials"}
    pss = [s["pss_kib_after_settle"] for s in samples
           if s.get("pss_kib_after_settle") is not None]
    phases = [s["cefrium_startup_phases_ms"] for s in samples
              if s.get("cefrium_startup_phases_ms")]
    ready = [p["provider_ms"] + p["total_ms"] for p in phases
             if p.get("provider_ms", -1) >= 0]
    launches = [s["activity_launch_ms"] for s in samples]
    return {
        "status": "measured", "samples": samples,
        "median_activity_launch_ms": statistics.median(launches),
        "min_activity_launch_ms": min(launches),
        "max_activity_launch_ms": max(launches),
        "startup_variability_ratio": (
            round(max(launches) / min(launches), 2) if min(launches) > 0 else None),
        "median_pss_kib": statistics.median(pss) if pss else None,
        "median_cefrium_surface_ready_ms": (
            statistics.median(ready) if len(ready) == len(samples) else None),
        "pss_samples": len(pss),
    }


def measure_interleaved(active, repetitions, settle_seconds, output_dir):
    result = {}
    eligible = {}
    # Consistent unmeasured warmup for all available browsers.
    for name, record in active.items():
        if record["status"] == "unavailable":
            result[name] = record.copy()
            continue
        pkg, launcher = record["package"], record["launcher"]
        run_adb("shell", "am", "force-stop", pkg)
        rc, output = run_adb("shell", "am", "start", "-W", "-n", launcher)
        if rc or parse_launch(output, pkg) is None:
            result[name] = {
                "package": pkg, "version": record["version"],
                "status": "unavailable", "reason": "Warmup launch failed",
                "diagnostic": output[-300:],
            }
            continue
        time.sleep(2)
        run_adb("shell", "am", "force-stop", pkg)
        eligible[name] = record
        result[name] = {
            "package": pkg, "version": record["version"],
            "status": "pending", "warmup_completed": True,
            "warmup_activity_launch_ms": parse_launch(output, pkg),
            "warmup_launch_state": parse_launch_state(output),
            "samples": [],
        }

    order = rotated_trials(eligible, repetitions)
    for round_index, names in enumerate(order):
        for position, name in enumerate(names):
            # No other comparison browser remains running in the background.
            for other in eligible.values():
                run_adb("shell", "am", "force-stop", other["package"])
            record = eligible[name]
            measured = sample_browser(record["package"], record["launcher"], 1,
                settle_seconds,
                output_dir if round_index == repetitions - 1 else None,
                name if round_index == repetitions - 1 else None)
            if measured["status"] != "measured":
                result[name].update(measured)
                result[name]["reason"] = "A cold trial failed: " + measured.get("reason", "")
                continue
            if result[name]["status"] != "pending":
                continue
            trial = measured["samples"][0]
            trial["iteration"] = round_index + 1
            trial["order_position"] = position + 1
            result[name]["samples"].append(trial)
            print("trial", round_index + 1, "position", position + 1,
                  name, trial["activity_launch_ms"], "ms", flush=True)

    for record in result.values():
        if record["status"] == "pending":
            if len(record["samples"]) == repetitions:
                record.update(aggregate(record["samples"]))
            else:
                record["status"] = "unavailable"
                record["reason"] = "Incomplete trial count"
    return result, order


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
        native_ready = velocity.get("median_cefrium_surface_ready_ms")
        if native_ready is not None:
            lines.append(f"- end-to-end Cefrium Surface creation: {native_ready:.0f} ms")
        strategies = sorted({x.get("cefrium_start_strategy") for x in
                             velocity.get("samples", []) if x.get("cefrium_start_strategy")})
        lines.append("- Verified startup strategies: " + (
            ", ".join(strategies) if strategies else "unavailable"))
        for phase in ("provider_ms", "ui_build_ms", "ui_wait_ms", "ui_ms",
                      "pool_ms", "rules_ms", "create_ms", "total_ms"):
            values = [x[phase] for x in phase_samples if x.get(phase, -1) >= 0]
            lines.append(f"- {phase}: {statistics.median(values):.0f} ms" if values
                         else f"- {phase}: unavailable")
    resource_samples = [x.get("cefrium_startup_resources")
                        for x in velocity.get("samples", [])
                        if x.get("cefrium_startup_resources")]
    if resource_samples:
        lines += ["", "## Velocity startup CPU, page faults and storage I/O", "",
                  "Process-wide resource deltas across Cefrium provider, Java Activity,",
                  "and the complete native Surface creation interval. -1 means unavailable;",
                  "read_bytes counts actual disk reads, NOT all cached file reads.", "",
                  "| Phase | Wall (ms) | CPU (ms) | CPU/wall | Minor faults | Major faults | Physical read (KiB) |",
                  "|---|---:|---:|---:|---:|---:|---:|"]
        for phase in ("provider", "activity", "total"):
            def median_value(field):
                values = [r[f"{phase}_{field}"] for r in resource_samples
                          if r.get(f"{phase}_{field}", -1) >= 0]
                return statistics.median(values) if values else None
            wall = median_value("wall_ms")
            cpu = median_value("cpu_ms")
            minflt = median_value("minor_faults")
            majflt = median_value("major_faults")
            disk = median_value("read_bytes")
            ratio = (cpu / wall if wall and cpu is not None else None)
            def formatted(value, digits=0):
                return "—" if value is None else f"{value:.{digits}f}"
            lines.append(f"| {phase} | {formatted(wall)} | {formatted(cpu)} | "
                         f"{formatted(ratio, 2)} | {formatted(minflt)} | "
                         f"{formatted(majflt)} | "
                         f"{formatted(disk / 1024 if disk is not None else None)} |")
        lines += ["", "Low CPU/wall suggests blocked or descheduled work but does not",
                  "identify I/O by itself. A major page fault suggests a disk-backed",
                  "page must be brought in; page faults and read_bytes may be unavailable",
                  "on some Android versions. Full native CPU call stacks and off-CPU",
                  "time still require a Perfetto/simpleperf trace.", ""]
    lines += ["", "## Startup variability (same emulator)", "",
              "The distribution matters more than a single fastest run.", "",
              "| Browser | Unmeasured warmup (ms) | Min (ms) | Median (ms) | Max (ms) | Max/min |",
              "|---|---:|---:|---:|---:|---:|"]
    for name, rec in data["browsers"].items():
        def fmt(value):
            return "—" if value is None else str(value)
        lines.append(f"| {name} | {fmt(rec.get('warmup_activity_launch_ms'))} | "
                     f"{fmt(rec.get('min_activity_launch_ms'))} | "
                     f"{fmt(rec.get('median_activity_launch_ms'))} | "
                     f"{fmt(rec.get('max_activity_launch_ms'))} | "
                     f"{fmt(rec.get('startup_variability_ratio'))} |")
    lines += ["", "Warmup is not first-install startup. Chrome may have an existing",
              "preinstalled profile. Large max/min ratios indicate filesystem, ART",
              "and CPU/GPU cache states affect these startup samples. Do not",
              "claim browser or page-loading superiority from the minimum sample.",
              ""]
    lines += ["", "## Measurement limitations", "",
        "- Same emulator; an unmeasured warmup runs once for each installed browser.",
        "- Rotated interleaved rounds balance execution order; all competitors are force-stopped before each trial.",
        "- With four available browsers and four rounds, each app occupies every execution position once.",
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
    parser.add_argument("--repetitions", type=int, default=4)
    parser.add_argument("--only", choices=tuple(BROWSERS))
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
            "velocity_build_variant": os.environ.get(
                "VELOCITY_BENCH_VARIANT", "release-unminified"),
            "browsers": {}}
    prepared = {}
    for name, package in BROWSERS.items():
        if args.only and name != args.only:
            continue
        version = installed_version(package)
        launcher = resolve_launcher(package) if version is not None else None
        if version is None or launcher is None:
            prepared[name] = {"package": package, "version": version,
                "status": "unavailable", "reason":
                    "package not installed" if version is None else "no resolvable launcher"}
        else:
            prepared[name] = {"package": package, "version": version,
                              "launcher": launcher, "status": "ready"}
    results, order = measure_interleaved(
        prepared, args.repetitions, args.settle_seconds, args.out)
    data["trial_order"] = order
    data["trial_design"] = "unmeasured warmup plus rotated interleaved trials"
    data["browsers"] = results
    for name, record in results.items():
        print(name, record["status"], record.get("median_activity_launch_ms"),
              record.get("median_pss_kib"), flush=True)
    (args.out / "results.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    (args.out / "summary.md").write_text(markdown_report(data) + "\n")
    print(markdown_report(data))


if __name__ == "__main__":
    main()
