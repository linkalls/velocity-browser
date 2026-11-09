#!/usr/bin/env bash
# Capture the actual Chromium activity and native browser surfaces in an
# emulator, including the home, menu, settings and tab switcher. No screenshot includes a logged-in website or private tab.
set -euo pipefail
APP=dev.velocity.browser.chromium013
ACTIVITY="$APP/dev.velocity.chromium.ChromiumTestActivity"
mkdir -p dist/cefrium-ui-audit
# Capture a second layout size close to Pixel 7 Pro CSS viewport width.
# Android emulator's native 320dp mode is still retained for narrow devices.
# API 37 may transiently reject a subsequent Activity start immediately after
# framework boot with "Too early to start activity". Retry ONLY that precise
# platform error, preserving every other failure as a CI failure.
start_audit_activity() {
  local mode="$1" launch_log="$2" attempt
  for attempt in 1 2 3 4; do
    if adb shell am start -W -n "$ACTIVITY" --es velocity_debug_ui_audit "$mode" \
        > "$launch_log" 2>&1; then
      if grep -q 'Status: ok' "$launch_log"; then return 0; fi
    fi
    if ! grep -q 'Too early to start activity' "$launch_log"; then break; fi
    echo "Android ActivityManager not ready yet (attempt $attempt), retrying" >&2
    sleep 12
    adb wait-for-device
  done
  adb logcat -d -v threadtime > "${launch_log%.txt}-failure-logcat.txt" || true
  cat "$launch_log" >&2
  return 1
}

for mode in settings settings-tabs settings-media home menu top-bar tabs tabs-private tabs-groups tabs-search; do
  adb shell am force-stop "$APP" || true
  # Closing Chromium may briefly leave native child-process teardown active.
  # Give Android's ActivityManager time before a cold restart.
  sleep 4
  adb wait-for-device
  if ! start_audit_activity "$mode" "dist/cefrium-ui-audit/${mode}-launch.txt"; then
    echo "ERROR: ${mode} UI Activity failed to start" >&2
    exit 1
  fi
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
  if [[ "$mode" == settings* || "$mode" == home || "$mode" == menu || "$mode" == top-bar ]]; then
    python3 experiments/chromium-013/tools/verify_browser_shell_ui.py \
      "dist/cefrium-ui-audit/${mode}.xml" "$mode"
  fi
  if [[ "$mode" == tabs* ]]; then
    python3 experiments/chromium-013/tools/verify_tab_switcher_ui.py \
      "dist/cefrium-ui-audit/${mode}.xml" "$mode"
  fi
done
# Real offline Chromium screenshot regression. The test page has distinct
# orange, green and blue bands; a generic icon or fabricated bitmap FAILS.
# This is a separate debug-only Activity intent, never user web content.
adb shell am force-stop "$APP" || true
# Remove prior synthetic screenshots from the disposable emulator app to
# avoid passing with the previous boot-smoke page's JPEG.
adb shell run-as "$APP" rm -r files/tab_previews 2>/dev/null || true
sleep 5
adb wait-for-device
if ! start_audit_activity preview "dist/cefrium-ui-audit/preview-launch.txt"; then
  echo 'ERROR: offline Chromium thumbnail test failed to start' >&2
  exit 1
fi
sleep 17
adb logcat -d -v brief > dist/cefrium-ui-audit/preview-logcat.txt || true
if ! grep -q 'TABSHOT fixture orange and green pixels verified' \
    dist/cefrium-ui-audit/preview-logcat.txt; then
  echo 'ERROR: Chromium did NOT capture the real colored offline fixture' >&2
  grep 'TABSHOT' dist/cefrium-ui-audit/preview-logcat.txt | tail -25 || true
  exit 1
fi
# Exercise the full pipeline: Chromium compositor -> in-memory cache ->
# ImageView inside the visible tab overview, not merely a file on disk.
# The debug fixture shows the native sheet after its first verified capture.
adb exec-out screencap -p > dist/cefrium-ui-audit/preview-tab-sheet.png
timeout 30s adb shell uiautomator dump /sdcard/velocity-preview-card.xml \
  > dist/cefrium-ui-audit/preview-card-uiautomator.txt 2>&1
adb shell cat /sdcard/velocity-preview-card.xml \
  > dist/cefrium-ui-audit/preview-tab-sheet.xml
python3 experiments/chromium-013/tools/verify_preview_card.py \
  dist/cefrium-ui-audit/preview-tab-sheet.png \
  dist/cefrium-ui-audit/preview-tab-sheet.xml
# A SUCCESSFUL real capture is persisted to the app's private directory.
# Do not pass a test just because its UI displays a site icon.
if ! adb shell run-as "$APP" ls files/tab_previews/ \
    > dist/cefrium-ui-audit/preview-files.txt 2>&1; then
  echo 'ERROR: no private thumbnail cache directory exists' >&2
  exit 1
fi
if ! grep -qE '\.jpg$' dist/cefrium-ui-audit/preview-files.txt; then
  echo 'ERROR: Chromium generated no JPEG screenshot' >&2
  grep 'TABSHOT' dist/cefrium-ui-audit/preview-logcat.txt || true
  exit 1
fi
# Verify at least one JPEG actually contains encoded image bytes.
file_name=$(grep -E '\.jpg$' dist/cefrium-ui-audit/preview-files.txt | head -1 | tr -d '\r')
adb exec-out run-as "$APP" cat "files/tab_previews/$file_name" \
  > dist/cefrium-ui-audit/offline-real-chromium-thumbnail.jpg
python3 - <<'CHECK_JPEG'
from pathlib import Path
p=Path('dist/cefrium-ui-audit/offline-real-chromium-thumbnail.jpg')
data=p.read_bytes()
assert len(data) > 1000, 'Thumbnail JPEG unexpectedly short'
assert data.startswith(b'\xff\xd8') and data.endswith(b'\xff\xd9'), 'Invalid JPEG'
print('PASS: captured a REAL Chromium JPEG, bytes=',len(data))
CHECK_JPEG
# Record whether screenshot originated from CDP or a fallback.
grep 'TABSHOT' dist/cefrium-ui-audit/preview-logcat.txt | tail -25 || true

# At 393dp width the UI should switch to the two-column tab grid.
# Keep 160dpi so the physical test resolution equals the logical dp size.
adb shell wm size 393x852
adb shell wm density 160
for mode in settings home menu tabs; do
  adb shell am force-stop "$APP" || true
  sleep 4
  adb wait-for-device
  if ! start_audit_activity "$mode" "dist/cefrium-ui-audit/${mode}-393dp-launch.txt"; then
    echo "ERROR: ${mode} UI Activity failed to start at 393dp" >&2
    exit 1
  fi
  sleep 12
  adb exec-out screencap -p > "dist/cefrium-ui-audit/${mode}-393dp.png"
  test -s "dist/cefrium-ui-audit/${mode}-393dp.png"
  if ! adb shell pidof "$APP" >/dev/null; then
    echo "ERROR: Velocity crashed in 393dp ${mode} layout" >&2
    exit 1
  fi
done
# Best-effort REAL-WEBSITE diagnosis on an isolated, empty emulator.
# This is *not* the pass/fail synthetic screenshot fixture above: public Yahoo
# News may be unavailable in CI due to captive networks / CDN differences.
# Preserve the screenshot and capture-stage log so a user-visible failure can
# be distinguished from the JPEG-only fixture succeeding.
adb shell am force-stop "$APP" || true
sleep 4
adb wait-for-device
if start_audit_activity real-site "dist/cefrium-ui-audit/real-site-launch.txt"; then
  sleep 24
  adb exec-out screencap -p > dist/cefrium-ui-audit/real-site-tabs.png || true
  timeout 30s adb shell uiautomator dump "/sdcard/velocity-real-site.xml" \
    > dist/cefrium-ui-audit/real-site-uiautomator.txt 2>&1 || true
  adb shell cat "/sdcard/velocity-real-site.xml" \
    > dist/cefrium-ui-audit/real-site-tabs.xml 2>/dev/null || true
  adb logcat -d -v brief | grep -E 'TABSHOT|VelocityTabPreview' \
    | tail -120 > dist/cefrium-ui-audit/real-site-preview-log.txt || true
  if grep -q 'TABSHOT CDP JPEG decoded' dist/cefrium-ui-audit/real-site-preview-log.txt; then
    echo 'Real website produced compositor pixels (native tab card still needs device review)'
  else
    echo 'WARNING: no real-site CDP JPEG observed; see real-site-preview-log.txt'
  fi
else
  echo 'WARNING: optional real-site screenshot could not be started' >&2
fi

# Android's focus dump can identify whether Cefrium requested audio focus
# on an otherwise silent browser Home. No logged-in content is loaded here.
adb shell dumpsys audio > dist/cefrium-ui-audit/audio-focus-after-browser.txt || true
# Screenshots are for visual review; a passing script alone does NOT prove that
# all fonts, screen sizes or real-device window insets render correctly.
echo 'PASS: captured both native sheet screens without app death'
