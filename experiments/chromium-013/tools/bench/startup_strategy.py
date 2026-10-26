#!/usr/bin/env python3
"""Android emulator-only control experiment: start on attach vs View.post.

No change in production startup mode unless both correctness and performance
show a consistent improvement. Never bypass Cefrium provider or renderer pool.
"""
import json
import re
import statistics
import subprocess
import time
from pathlib import Path

APP = "dev.velocity.browser.chromium013"
ACTIVITY = APP + "/dev.velocity.chromium.StartupDiagnosticActivity"
ORDER = ("post", "attach", "attach", "post")


def parse_log(log):
    timing = re.findall(r"CEFStartupTiming\s+([^\r\n]+)", log)
    match = re.search(r"CEFPageTiming synthetic_dom_ready_ms=(\d+)", log)
    result = {"strategy": None, "renderer_ready": False,
              "dom_js_ready_ms": int(match.group(1)) if match else None}
    if not timing:
        return result
    parts = dict((key, int(value)) for key, value in
                 re.findall(r"(\w+_ms)=(-?\d+)", timing[-1]))
    result["phases_ms"] = parts
    result["strategy"] = ("attach" if "CEFStartupStrategy strategy=attach" in log
                          else "post" if "CEFStartupStrategy strategy=post" in log
                          else None)
    result["renderer_ready"] = "CEF surface created and page navigation started" in log
    provider_ms = parts.get("provider_ms", -1)
    total_ms = parts.get("total_ms", -1)
    result["surface_ready_ms"] = (
        provider_ms + total_ms if provider_ms >= 0 and total_ms >= 0 else None)
    return result


def summarize(records):
    by_mode = {mode: [] for mode in set(ORDER)}
    lines = ["# Cefrium renderer startup scheduling experiment", "",
             "Local emulator only. Both paths retain the Cefrium provider, sandbox checks,",
             "native browser creation and a DOM+JavaScript fixture. Experimental",
             "attach path is NOT enabled for normal browsing.", "",
             "| Trial | Mode | UI wait (ms) | Native ready (ms) | DOM+JS (ms) | Result |",
             "|---:|---|---:|---:|---:|---|"]
    for rec in records:
        phases = rec.get("phases_ms") or {}
        num = lambda value: str(value) if value is not None else "—"
        lines.append(f"| {rec['iteration']} | {rec['mode']} | "
                     f"{num(phases.get('ui_wait_ms'))} | "
                     f"{num(rec.get('surface_ready_ms'))} | "
                     f"{num(rec.get('dom_js_ready_ms'))} | {rec['status']} |")
        if rec["status"] == "measured" and "ui_wait_ms" in phases:
            by_mode[rec["mode"]].append(phases["ui_wait_ms"])
    for mode in ("post", "attach"):
        if len(by_mode[mode]) == 2:
            lines.append(f"- {mode} UI wait median: {statistics.median(by_mode[mode]):.0f} ms")
        else:
            lines.append(f"- {mode}: incomplete or failed; cannot compare")
    lines += ["", "View attachment readiness, emulator scheduling and cache state vary.",
              "This measures a narrow phase of startup and a local DOM+JS response.",
              "It is NOT first paint, first website usable time, or cross-browser speed.", ""]
    return "\n".join(lines)


def adb(*args, timeout=35):
    p = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout + p.stderr


def measure(dest):
    dest.mkdir(parents=True, exist_ok=True)
    records = []
    for iteration, mode in enumerate(ORDER, start=1):
        adb("shell", "am", "force-stop", APP)
        adb("logcat", "-c")
        rc, launch = adb("shell", "am", "start", "-W", "-n", ACTIVITY,
                         "--ez", "smoke_test", "true",
                         "--es", "startup_strategy", mode)
        time.sleep(10)
        _, log = adb("logcat", "-d", "-s", "VelocityCefStartup:I", "*:S")
        _, pid = adb("shell", "pidof", APP)
        parsed = parse_log(log)
        correct = parsed.get("strategy") == mode
        ok = (rc == 0 and correct and parsed["renderer_ready"]
              and parsed["dom_js_ready_ms"] is not None
              and parsed["surface_ready_ms"] is not None
              and any(word.isdigit() for word in pid.split()))
        parsed.update({"iteration": iteration, "mode": mode,
                       "status": "measured" if ok else "failed"})
        (dest / f"trial-{iteration}-{mode}-logcat.txt").write_text(log)
        (dest / f"trial-{iteration}-{mode}-activity.txt").write_text(launch)
        # Retain visual evidence: a working JS title does not prove a valid
        # drawn Surface. Pixel screenshot review remains a separate gate.
        try:
            screenshot = subprocess.run(
                ["adb", "exec-out", "screencap", "-p"],
                capture_output=True, timeout=25)
            if screenshot.returncode == 0 and screenshot.stdout.startswith(b"\x89PNG"):
                (dest / f"trial-{iteration}-{mode}.png").write_bytes(screenshot.stdout)
                parsed["screenshot_captured"] = True
            else:
                parsed["screenshot_captured"] = False
        except (subprocess.TimeoutExpired, OSError):
            parsed["screenshot_captured"] = False
        records.append(parsed)
        print(f"strategy trial {iteration} {mode} {parsed['status']}: "
              f"{parsed.get('surface_ready_ms')}", flush=True)
    (dest / "results.json").write_text(json.dumps(records, indent=2) + "\n")
    (dest / "summary.md").write_text(summarize(records) + "\n")


if __name__ == "__main__":
    measure(Path("dist/cefrium-startup-strategy"))
