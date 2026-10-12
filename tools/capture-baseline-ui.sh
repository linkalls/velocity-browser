#!/usr/bin/env bash
# Disposable CI emulator only. Existing user profiles/devices must not be used.
set -euo pipefail
baseline=16be2395f1ea863144bd695012c56b60af1ab125
./gradlew --max-workers=1 :app:assembleDebugAndroidTest
checkout=$(mktemp -d)
git fetch --depth=1 origin "$baseline"
git worktree add --detach "$checkout" "$baseline"
trap 'git worktree remove --force "$checkout"' EXIT
(cd "$checkout" && ./gradlew --max-workers=1 :app:assembleDebug)
adb install -r -t "$checkout/app/build/outputs/apk/debug/app-debug.apk"
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p dist/screenshots
adb shell am instrument -w -e class dev.velocity.browser.V04UiIntegrationTest#captureMajorScreensInBothThemes -e screenshot_label before dev.velocity.browser.test/androidx.test.runner.AndroidJUnitRunner | tee dist/screenshots/baseline-instrumentation.txt
python3 - <<'PY'
from pathlib import Path
text=Path('dist/screenshots/baseline-instrumentation.txt').read_text()
if 'OK (1 test)' not in text or 'FAILURES' in text:
    raise SystemExit('Baseline screenshot instrumentation failed')
PY
adb pull /sdcard/Android/data/dev.velocity.browser/files/screenshots/before dist/screenshots/before
