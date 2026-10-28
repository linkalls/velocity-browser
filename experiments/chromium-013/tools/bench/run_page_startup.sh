#!/usr/bin/env bash
set -euo pipefail
adb root
adb wait-for-device
bench='experiments/chromium-013/tools/bench/page_startup_bench.py'
python3 "$bench" --baseline dist/page-apks/baseline.apk --candidate dist/page-apks/candidate.apk --eager dist/page-apks/eager.apk \
  --out dist/page-startup/cold --cold-cache
python3 "$bench" --baseline dist/page-apks/baseline.apk --candidate dist/page-apks/candidate.apk --eager dist/page-apks/eager.apk \
  --out dist/page-startup/wikipedia --cold-cache --external-url https://www.wikipedia.org/
python3 "$bench" --baseline dist/page-apks/baseline.apk --candidate dist/page-apks/candidate.apk --eager dist/page-apks/eager.apk \
  --out dist/page-startup/mdn --cold-cache --external-url https://developer.mozilla.org/en-US/docs/Web/JavaScript
