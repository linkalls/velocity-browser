#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist/page-startup
# Root restarts adbd; API 37 sometimes reports "closed" during a successful
# restart. Only proceed once the reconnected daemon actually reports uid 0.
for attempt in 1 2 3; do
  adb root >> dist/page-startup/adb-root.txt 2>&1 || true
  timeout 30s adb wait-for-device
  uid_now="$(adb shell id -u | tr -d '\r')"
  if [[ "$uid_now" == 0 ]]; then break; fi
  sleep 1
done
[[ "$uid_now" == 0 ]] || { echo 'Root unavailable: cannot claim cache-reset trials' >&2; exit 1; }
bench='experiments/chromium-013/tools/bench/page_startup_bench.py'
python3 "$bench" --baseline dist/page-apks/baseline.apk --candidate dist/page-apks/candidate.apk --eager dist/page-apks/eager.apk \
  --out dist/page-startup/cold --cold-cache
python3 "$bench" --baseline dist/page-apks/baseline.apk --candidate dist/page-apks/candidate.apk --eager dist/page-apks/eager.apk \
  --out dist/page-startup/wikipedia --cold-cache --external-url https://www.wikipedia.org/
python3 "$bench" --baseline dist/page-apks/baseline.apk --candidate dist/page-apks/candidate.apk --eager dist/page-apks/eager.apk \
  --out dist/page-startup/mdn --cold-cache --external-url https://developer.mozilla.org/en-US/docs/Web/JavaScript
