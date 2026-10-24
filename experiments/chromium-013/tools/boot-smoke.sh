#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist
APP=dev.velocity.browser.chromium013
COMPONENT="$APP/dev.velocity.chromium.StartupDiagnosticActivity"
APK="experiments/chromium-013/app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
adb install -r "$APK"
adb logcat -c
adb shell am force-stop "$APP" || true
adb shell am start -W -n "$COMPONENT" --ez smoke_test true | tee dist/cefrium-activity-start.txt
sleep 12
adb shell pidof "$APP" | tee dist/cefrium-pid.txt || true
adb shell dumpsys activity activities > dist/cefrium-activities.txt || true
adb logcat -d -v threadtime > dist/cefrium-logcat-full.txt || true
grep -E 'VelocityCefStartup|AndroidRuntime|FATAL EXCEPTION|Fatal signal|DEBUG *:|libcef|com.cefrium|Cefrium' \
    dist/cefrium-logcat-full.txt > dist/cefrium-logcat-filtered.txt || true
if ! test -s dist/cefrium-pid.txt; then
  echo "FAIL: Chromium browser process died within 12s" >&2
  tail -n 150 dist/cefrium-logcat-filtered.txt || true
  exit 1
fi
if grep -E 'FATAL EXCEPTION|Fatal signal' dist/cefrium-logcat-filtered.txt; then
  echo "FAIL: fatal crash reported by Android" >&2
  exit 1
fi
if ! grep -E 'ResumedActivity|mResumedActivity|topResumedActivity' dist/cefrium-activities.txt | grep -qF "$APP"; then
  echo "WARN: activity was not the foreground resumed activity" >&2
  grep -E 'ResumedActivity|mResumedActivity|topResumedActivity' dist/cefrium-activities.txt || true
fi
if grep -F 'CEF Surface startup failure' dist/cefrium-logcat-filtered.txt; then
  echo "FAIL: Cefrium failed to initialize natively" >&2
  exit 1
fi
if ! grep -F 'CEF surface created and page navigation started' dist/cefrium-logcat-filtered.txt; then
  echo "FAIL: Chromium surface startup did not complete" >&2
  exit 1
fi
if ! grep -F 'Verified Java renderer pool: 40 isolated services' dist/cefrium-logcat-filtered.txt; then
  echo "FAIL: Java renderer service selection was not verified" >&2
  exit 1
fi
echo "PASS: Chromium main process survived foreground launch (emulator only)"
