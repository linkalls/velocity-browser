#!/usr/bin/env bash
# The emulator action treats each script input line as an independent shell.
# Keep the test exit status and crash diagnostics in one process.
set -uo pipefail
if [ "${VELOCITY_CAPTURE_BASELINE:-0}" = 1 ] && [ "${CI:-false}" = true ] && [ "${GITHUB_REF:-}" = refs/heads/codex/scroll-ui-overhaul ]; then
  bash tools/capture-baseline-ui.sh || exit $?
fi
result=0
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=dev.velocity.browser.RequiresExternalFixture || result=$?
if [ "$result" -ne 0 ]; then
  adb logcat -d -v brief AndroidRuntime:E DEBUG:E '*:F' | tail -160 || true
fi
mkdir -p dist/screenshots
adb pull /sdcard/Download/Velocity-validation/. dist/screenshots/ || true
if [ "$result" -eq 0 ]; then
  python3 - <<'PY'
from pathlib import Path
files=list(Path('dist/screenshots/after').glob('*.png'))
if len(files)!=10:
    raise SystemExit('Expected ten post-change screenshots; capture is incomplete')
PY
  capture_result=$?
  if [ "$capture_result" -ne 0 ]; then result=$capture_result; fi
fi
exit "$result"
