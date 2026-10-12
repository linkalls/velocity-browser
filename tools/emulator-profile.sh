#!/usr/bin/env bash
set -euo pipefail
# Clearing application data is restricted to this disposable emulator workflow.
qemu=$(adb shell getprop ro.kernel.qemu | tr -d '\r')
if [ "$qemu" != 1 ]; then echo 'Refusing profile isolation on a physical device' >&2; exit 2; fi
adb root
adb wait-for-device
mkdir -p dist/profile
curl -fsSL https://get.perfetto.dev/trace_processor -o /tmp/velocity-trace-processor
chmod +x /tmp/velocity-trace-processor
bun tools/bench-runner/src/scroll-fixture-server.ts 8181 > /tmp/velocity-fixtures.log 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT
adb reverse tcp:8181 tcp:8181
wm=$(adb shell wm size)
read -r width height < <(python3 -c 'import re,sys; m=re.findall(r"(\d+)x(\d+)",sys.argv[1]);print(*m[-1])' "$wm")
for round in 1 2 3 4 5; do
  if (( round % 2 )); then order='baseline current'; else order='current baseline'; fi
  for version in $order; do
    if [ "$version" = baseline ]; then apk=../velocity-baseline/app/build/outputs/apk/benchmark/app-benchmark.apk; else apk=app/build/outputs/apk/benchmark/app-benchmark.apk; fi
    for scene in static dynamic; do
      prefix="dist/profile/$version-$scene-$round"
      adb uninstall dev.velocity.browser >/dev/null 2>&1 || true
      adb install -t "$apk" >/dev/null
      adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
      adb shell rm -f /data/local/tmp/velocity-fixture-ready
      adb shell am instrument -w -r -e class dev.velocity.browser.MeasurementFixtureDriver -e scene "$scene" dev.velocity.browser.test/androidx.test.runner.AndroidJUnitRunner > "$prefix-driver.txt" 2>&1 &
      driver=$!
      ready=0
      for attempt in $(seq 1 40); do
        if adb shell test -f /data/local/tmp/velocity-fixture-ready; then ready=1; break; fi
        sleep 1
      done
      if [ "$ready" != 1 ]; then cat "$prefix-driver.txt";adb logcat -d -v brief AndroidRuntime:E DEBUG:E '*:F' | tail -160;adb shell dumpsys activity exit-info dev.velocity.browser;echo 'Fixture did not render; comparison is unverified' >&2; exit 1; fi
      adb pull /data/data/dev.velocity.browser/cache/velocity-profile-launch.txt "$prefix-launch.txt" >/dev/null
      bash tools/scroll-trace.sh "$scene" "$prefix.perfetto-trace" &
      recorder=$!
      (cd tools/bench-runner && bun src/cli.ts cpu dev.velocity.browser 15000 "../../$prefix-cpu.json") &
      cpu=$!
      for gesture in $(seq 1 10); do
        if (( gesture % 2 )); then adb shell input swipe "$((width/2))" "$((height*3/4))" "$((width/2))" "$((height/4))" 600; else adb shell input swipe "$((width/2))" "$((height/4))" "$((width/2))" "$((height*3/4))" 600; fi
        sleep 0.5
      done
      wait "$recorder"
      wait "$cpu" || echo 'CPU sampling failed; retain its unavailable status'
      (cd tools/bench-runner && bun src/cli.ts memory dev.velocity.browser "../../$prefix-memory.json") || echo 'PSS sampling unavailable'
      wait "$driver"
      if ! rg -q 'OK \(1 test\)' "$prefix-driver.txt"; then cat "$prefix-driver.txt";exit 1;fi
      python3 tools/frame-report.py "$prefix.perfetto-trace" /tmp/velocity-trace-processor "$prefix-frames.json"
    done
  done
done
python3 tools/profile-summary.py dist/profile
