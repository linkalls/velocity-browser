#!/usr/bin/env python3
"""Run a documented, best-effort native Android browser UI observation.

Uses ADB + uiautomator against *real installed browser APKs*. No simulated
images, fake UI hierarchies, credentials, user accounts, or app instrumentation.
Every step carries evidence + status, including failure and unknown state.
Chrome is used only if it exists in the official Google Play emulator image.
Vivaldi is installed by the separate workflow from Vivaldi's official archive.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

OUT = Path(os.environ.get("REFERENCE_OUTPUT", "dist/browser-reference-ui"))
OUT.mkdir(parents=True, exist_ok=True)

BROWSERS = {
    "chrome": "com.android.chrome",
    "vivaldi": "com.vivaldi.browser",
}


def call(args: list[str], timeout: int = 35, binary: bool = False):
    return subprocess.run(
        args,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
        text=not binary,
    )


def adb(*args: str, timeout: int = 35, binary: bool = False):
    return call(["adb", *args], timeout=timeout, binary=binary)


def device_package(name: str) -> dict[str, Any]:
    output = adb("shell", "dumpsys", "package", name).stdout
    version = re.search(r"\bversionName=([^\s]+)", output)
    return {
        "package": name,
        "installed": bool(version),
        "version": version.group(1) if version else None,
    }


def dump_ui(prefix: Path) -> list[dict[str, Any]]:
    path = "/sdcard/browser-reference-ui.xml"
    command = adb("shell", "uiautomator", "dump", path, timeout=40)
    data = adb("shell", "cat", path, timeout=25)
    if command.returncode != 0 or data.returncode != 0 or "<hierarchy" not in data.stdout:
        prefix.with_suffix(".ui-error.txt").write_text(
            "dump result=" + str(command.returncode) + "\n"
            + command.stdout + command.stderr + "\n" + data.stdout + data.stderr
        )
        return []
    prefix.with_suffix(".xml").write_text(data.stdout)
    root = ET.fromstring(data.stdout[data.stdout.find("<?xml") :] if "<?xml" in data.stdout else data.stdout)
    nodes: list[dict[str, Any]] = []
    for node in root.iter("node"):
        b = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if b is None:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        nodes.append(
            {
                "text": node.get("text", ""),
                "description": node.get("content-desc", ""),
                "class": node.get("class", ""),
                "clickable": node.get("clickable") == "true",
                "bounds": [x1, y1, x2, y2],
            }
        )
    return nodes


def screenshot(prefix: Path, observations: list[dict[str, Any]], note: str) -> list[dict[str, Any]]:
    snap = adb("exec-out", "screencap", "-p", binary=True, timeout=25)
    status = "captured" if snap.returncode == 0 and snap.stdout.startswith(b"\x89PNG") else "failed"
    if status == "captured":
        prefix.with_suffix(".png").write_bytes(snap.stdout)
    nodes = dump_ui(prefix)
    observations.append(
        {
            "screen": prefix.stem,
            "screenshot": status,
            "xml_nodes": len(nodes),
            "note": note,
            "sample_labels": [n["text"] or n["description"] for n in nodes if n["text"] or n["description"]][:45],
        }
    )
    return nodes


def choose(nodes: list[dict[str, Any]], patterns: list[str]) -> dict[str, Any] | None:
    """Select an explicit accessible UI node rather than guessing pixel coords."""
    for pattern in patterns:
        for node in nodes:
            label = (node["text"] + " " + node["description"]).lower()
            if re.search(pattern, label, re.IGNORECASE):
                return node
    return None


def tap(node: dict[str, Any]):
    x1, y1, x2, y2 = node["bounds"]
    return adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def click_labels(nodes: list[dict[str, Any]], labels: list[str], records: list[dict[str, Any]], action: str):
    selected = choose(nodes, labels)
    if selected is None:
        records.append({"action": action, "result": "NOT_OBSERVED", "patterns": labels})
        return False
    result = tap(selected)
    records.append(
        {
            "action": action,
            "result": "TAPPED" if result.returncode == 0 else "FAILED",
            "label": selected["text"] or selected["description"],
            "bounds": selected["bounds"],
        }
    )
    time.sleep(2)
    return result.returncode == 0


def back():
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)


def dismiss_onboarding(nodes: list[dict[str, Any]], prefix: Path, steps: list[dict[str, Any]]):
    patterns = [
        r"^accept & continue$", r"^agree and continue$", r"^accept$",
        r"^use without an account$", r"^use without account$", r"^no thanks$",
        r"^not now$", r"^skip$", r"^skip for now$", r"^got it$",
        r"^continue without.*$", r"^continue as guest$", r"^start browsing$",
        r"^done$", r"^continue$", r"^next$",
    ]
    for index in range(6):
        selected = choose(nodes, patterns)
        if selected is None:
            break
        label = selected["text"] or selected["description"]
        tap(selected)
        steps.append({"action": "onboarding", "label": label, "result": "TAPPED"})
        time.sleep(2.5)
        nodes = dump_ui(prefix.with_name(prefix.stem + f"-onboard-{index}"))
    return nodes


def browser_audit(name: str, package: str) -> dict[str, Any]:
    folder = OUT / name
    folder.mkdir(exist_ok=True)
    info = device_package(package)
    info.update(
        {
            "capture_time_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "platform": adb("shell", "getprop", "ro.build.version.release").stdout.strip(),
            "sdk": adb("shell", "getprop", "ro.build.version.sdk").stdout.strip(),
            "observations": [],
            "actions": [],
            "limits": ["UI observation only; no account signed in", "No animation/FPS measurement"],
        }
    )
    if not info["installed"]:
        info["limits"].append("NOT TESTED: package not installed; no screenshot attributed to this browser")
        return info

    observations = info["observations"]
    actions = info["actions"]
    launch = adb("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1", timeout=25)
    actions.append({"action": "launch", "result": "OK" if launch.returncode == 0 else "FAILED"})
    time.sleep(9)
    nodes = screenshot(folder / "01-first-run", observations, "First frame; possible onboarding")
    nodes = dismiss_onboarding(nodes, folder / "onboarding", actions)
    nodes = screenshot(folder / "02-home", observations, "After best-effort onboarding; may show start page")

    # Add a deterministic public URL. No user accounts or private information.
    launched = adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "https://example.com/", "-p", package)
    actions.append({"action": "navigate public example.com", "result": "OK" if launched.returncode == 0 else "FAILED"})
    time.sleep(8)
    nodes = screenshot(folder / "03-page", observations, "Public example.com (network may be unavailable)")

    # Native package-specific semantics are determined by accessibility labels.
    clicked = click_labels(nodes, [
        r"switch tabs", r"tab switcher", r"show tabs", r"tab overview",
        r"open tabs", r"\b\d+ tabs\b", r"tab count", r"tabs button",
    ], actions, "open tab switcher")
    if clicked:
        nodes = screenshot(folder / "04-tabs", observations, "Tab switcher reached through accessibility label")
        click_labels(nodes, [r"new tab", r"add tab", r"create tab", r"^\+$"], actions, "new tab from switcher")
        screenshot(folder / "05-new-tab", observations, "After attempted new tab action")
        back()
    else:
        observations.append({"screen": "04-tabs", "screenshot": "NOT_TESTED", "note": "No matching accessible tab-switcher control"})

    # Ensure menu step begins from an active regular page.
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "https://example.com/", "-p", package)
    time.sleep(5)
    nodes = dump_ui(folder / "before-menu")
    opened = click_labels(nodes, [
        r"more options", r"\bmore\b", r"vivaldi menu", r"browser menu",
        r"menu button", r"customize and control", r"^menu$",
    ], actions, "open browser menu")
    if opened:
        nodes = screenshot(folder / "06-menu", observations, "Native browser menu")
        if click_labels(nodes, [r"^settings$", r"^preferences$"], actions, "open settings"):
            screenshot(folder / "07-settings", observations, "Native browser settings")
            back()
        else:
            observations.append({"screen": "07-settings", "screenshot": "NOT_TESTED", "note": "Settings control not found"})
    else:
        observations.append({"screen": "06-menu", "screenshot": "NOT_TESTED", "note": "No matching accessible menu control"})

    # Report identifiers from direct package-manager evidence; never claim
    # version 157 or 8.3 until package metadata actually reports that version.
    return info


def main():
    device = adb("devices").stdout
    if "\tdevice" not in device:
        raise SystemExit("No connected Android emulator. No fake captures generated.")
    full = {
        "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "method": "native emulator screenshots + uiautomator dump; no APK decompilation",
        "device": adb("shell", "getprop", "ro.product.model").stdout.strip(),
        "build": adb("shell", "getprop", "ro.build.fingerprint").stdout.strip(),
        "browsers": {},
    }
    for name, package in BROWSERS.items():
        try:
            full["browsers"][name] = browser_audit(name, package)
        except Exception as exc:
            # Do not silently promote partial browser data to completed test.
            full["browsers"][name] = {
                "package": package,
                "result": "INCOMPLETE",
                "error": type(exc).__name__ + ": " + str(exc),
            }
        finally:
            (OUT / "observations.json").write_text(json.dumps(full, ensure_ascii=False, indent=2) + "\n")
    for name, data in full["browsers"].items():
        print(name, data.get("version", "not installed"),
              [(o.get("screen"), o.get("screenshot")) for o in data.get("observations", [])])


if __name__ == "__main__":
    main()
