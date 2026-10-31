#!/usr/bin/env python3
"""Capture a real Android startup system trace of the synthetic CEF fixture.

No private websites are opened. Trace data may still contain process/package
metadata and must not be used to profile a person's logged-in browser.
"""
import json
import subprocess
import time
from pathlib import Path

from browser_bench import BROWSERS, parse_launch, parse_velocity_resources, \
    parse_velocity_timing, run_adb

APP = BROWSERS["Velocity"]
DIAGNOSTIC = APP + "/dev.velocity.chromium.StartupDiagnosticActivity"
TRACE_PATH = "/data/misc/perfetto-traces/velocity-cef-startup.pftrace"


def perfetto_command():
    return ["shell", "perfetto", "-D", "-o", TRACE_PATH,
            "-t", "14s", "-b", "24mb", "-s", "32mb", "-a", APP,
            "sched", "freq", "am", "wm", "gfx", "view"]


def capture(output_dir):
    output_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "status": "unavailable", "device_trace": TRACE_PATH,
        "synthetic_fixture_only": True,
        "rendered": False,
    }
    run_adb("shell", "am", "force-stop", APP)
    run_adb("logcat", "-c")
    rc, output = run_adb(*perfetto_command(), timeout=45)
    (output_dir / "trace-launch.txt").write_text(output[-3000:])
    if rc:
        report["reason"] = "Perfetto trace session refused"
    else:
        rc, launch = run_adb("shell", "am", "start", "-W", "-n", DIAGNOSTIC,
                              "--ez", "smoke_test", "true", timeout=40)
        (output_dir / "activity-launch.txt").write_text(launch[-3000:])
        report["diagnostic_activity_launch_ms"] = (
            parse_launch(launch, APP) if rc == 0 else None)
        # The Perfetto process owns its bounded 14-second capture. Wait for
        # the trace writer to finish before adb pull, not just until am returns.
        time.sleep(16)
        _, log = run_adb("logcat", "-d", "-s", "VelocityCefStartup:I", "*:S")
        # Safe diagnostic-only log: numeric metrics and startup status.
        loglines = [
            x for x in log.splitlines() if
            ("CEFStartupTiming" in x or "CEFStartupResource" in x
             or "CEFPageTiming" in x
             or "CEF synthetic document title observed" in x)
        ]
        (output_dir / "startup-phases.txt").write_text("\n".join(loglines) + "\n")
        report["phases"] = parse_velocity_timing(log)
        report["resources"] = parse_velocity_resources(log)
        report["rendered"] = (
            "CEF synthetic document title observed" in log
            and "CEFPageTiming synthetic_dom_ready_ms=" in log)
        dest = output_dir / "velocity-cef-startup.pftrace"
        copied = subprocess.run(
            ["adb", "pull", TRACE_PATH, str(dest)],
            capture_output=True, text=True, timeout=80, check=False)
        report["trace_bytes"] = dest.stat().st_size if dest.exists() else 0
        if copied.returncode == 0 and report["trace_bytes"] > 4096:
            report["status"] = "captured" if report["rendered"] else "trace_only"
        else:
            report["reason"] = "ADB could not retrieve a nonempty trace"
        run_adb("shell", "rm", "-f", TRACE_PATH)
    (output_dir / "metadata.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Perfetto native startup:", report["status"],
          "bytes=", report.get("trace_bytes", 0), flush=True)


if __name__ == "__main__":
    capture(Path("dist/cefrium-perfetto-startup"))
