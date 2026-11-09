#!/usr/bin/env python3
"""Fail CI if a native Vivaldi-style tab screen silently regresses.

Uses Android uiautomator hierarchy, not mocked views or screenshot labels.
Only synthetic public/blank tabs may be used by this audit.
"""
import argparse
import re
from pathlib import Path
from xml.etree import ElementTree as ET


def verify(path: Path, mode: str) -> None:
    root = ET.parse(path).getroot()
    nodes = list(root.iter("node"))
    texts = [n.get("text", "") for n in nodes]
    descriptions = [n.get("content-desc", "") for n in nodes]
    all_labels = "\n".join(texts + descriptions)

    # The full-screen overlay has proper navigation, a close affordance,
    # and an intentionally implemented (not fake) recently-closed section.
    for label in ("タブ一覧を閉じる", "通常タブ画面", "非公開タブ画面",
                  "グループタブ画面", "最近閉じたタブ画面"):
        assert label in descriptions, f"Missing {label!r} from switcher UI"
    if mode == "tabs-private":
        assert "非公開タブ (1)" in texts, "Private page title/counter missing"
        assert "開発グループ" not in all_labels, "Regular tab group leaked into private mode"
        assert "新しい非公開タブ" in descriptions, "Private new-tab action missing"
    elif mode in ("tabs", "tabs-groups"):
        assert "開発グループ" in all_labels, "Group card or label missing"
        assert "新しいタブ" in descriptions, "Floating add-tab action missing"
        groups = [x for x in descriptions if x == "グループプレビュー"]
        assert len(groups) >= 4, f"Expected four real 2x2 mini previews, got {len(groups)}"
        if mode == "tabs":
            assert any(re.fullmatch(r"タブ \(\d+\)", x) for x in texts), \
                "Normal tab count missing"
        else:
            assert any(x.startswith("グループ (") for x in texts), \
                "Group overview heading missing"
    else:
        raise ValueError(f"Unknown test mode: {mode}")
    # Enough of the screen must be interactable: an inert screenshot doesn't pass.
    clickable = sum(n.get("clickable") == "true" for n in nodes)
    assert clickable >= 6, f"Switcher has only {clickable} interactive controls"
    print(f"PASS: fullscreen tab switcher {mode}: nodes={len(nodes)}, "
          f"interactive={clickable}, group thumbnails="
          f"{sum(x == 'グループプレビュー' for x in descriptions)}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("hierarchy", type=Path)
    ap.add_argument("mode", choices=("tabs", "tabs-private", "tabs-groups"))
    args = ap.parse_args()
    verify(args.hierarchy, args.mode)
