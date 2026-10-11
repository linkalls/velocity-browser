#!/usr/bin/env bash
# The emulator action treats each script input line as an independent shell.
# Keep the test exit status and crash diagnostics in one process.
set -uo pipefail
result=0
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=dev.velocity.browser.RequiresExternalFixture || result=$?
if [ "$result" -ne 0 ]; then
  adb logcat -d -v brief AndroidRuntime:E DEBUG:E '*:F' | tail -160 || true
fi
exit "$result"
