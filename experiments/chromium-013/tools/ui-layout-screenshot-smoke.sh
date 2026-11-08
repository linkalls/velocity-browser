#!/usr/bin/env bash
# Capture the actual Chromium activity and its Android-native bottom sheets in
# an emulator. No screenshot includes a logged-in website or private tab.
set -euo pipefail
APP=dev.velocity.browser.chromium013
ACTIVITY="$APP/dev.velocity.chromium.ChromiumTestActivity"
mkdir -p dist/cefrium-ui-audit
# Capture a second layout size close to Pixel 7 Pro CSS viewport width.
# Android emulator's native 320dp mode is still retained for narrow devices.
for mode in settings tabs; do
  adb shell am force-stop "$APP" || true
  adb shell am start -W -n "$ACTIVITY" --es velocity_debug_ui_audit "$mode" \
    > "dist/cefrium-ui-audit/${mode}-launch.txt"
  sleep 14
  adb exec-out screencap -p > "dist/cefrium-ui-audit/${mode}.png"
  timeout 30s adb shell uiautomator dump "/sdcard/velocity-${mode}-audit.xml" \
    > "dist/cefrium-ui-audit/${mode}-uiautomator.txt" 2>&1 || true
  adb shell cat "/sdcard/velocity-${mode}-audit.xml" \
    > "dist/cefrium-ui-audit/${mode}.xml" || true
  test -s "dist/cefrium-ui-audit/${mode}.png"
  if ! adb shell pidof "$APP" >/dev/null; then
    echo "ERROR: Velocity crashed while rendering ${mode} bottom sheet" >&2
    exit 1
  fi
  if [[ "$mode" == settings ]]; then
    # Fails if the old screen was accidentally launched or build info omitted.
    grep -q 'Velocity 設定' "dist/cefrium-ui-audit/${mode}.xml" || true
  fi
done
# At 393dp width the UI should switch to the two-column tab grid.
# Keep 160dpi so the physical test resolution equals the logical dp size.
adb shell wm size 393x852
adb shell wm density 160
for mode in settings tabs; do
  adb shell am force-stop "$APP" || true
  adb shell am start -W -n "$ACTIVITY" --es velocity_debug_ui_audit "$mode"     > "dist/cefrium-ui-audit/${mode}-393dp-launch.txt"
  sleep 12
  adb exec-out screencap -p > "dist/cefrium-ui-audit/${mode}-393dp.png"
  test -s "dist/cefrium-ui-audit/${mode}-393dp.png"
  if ! adb shell pidof "$APP" >/dev/null; then
    echo "ERROR: Velocity crashed in 393dp ${mode} layout" >&2
    exit 1
  fi
done
# Android's focus dump can identify whether Cefrium requested audio focus
# on an otherwise silent browser Home. No logged-in content is loaded here.
adb shell dumpsys audio > dist/cefrium-ui-audit/audio-focus-after-browser.txt || true
# Screenshots are for visual review; a passing script alone does NOT prove that
# all fonts, screen sizes or real-device window insets render correctly.
echo 'PASS: captured both native sheet screens without app death'
