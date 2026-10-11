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
      adb shell am start -W -a android.intent.action.VIEW -d "http://127.0.0.1:8181/$scene" dev.velocity.browser/.MainActivity > "$prefix-launch.txt"
      # Await the actual fixture, not a fixed loading-delay guess.
      ready=0
      for attempt in $(seq 1 20); do
        adb shell uiautomator dump /data/local/tmp/velocity-fixture.xml >/dev/null
        if adb shell cat /data/local/tmp/velocity-fixture.xml | rg -q 'Fixture row'; then ready=1; break; fi
        sleep 1
      done
      if [ "$ready" != 1 ]; then echo 'Fixture did not render; comparison is unverified' >&2; exit 1; fi
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
      python3 tools/frame-report.py "$prefix.perfetto-trace" /tmp/velocity-trace-processor "$prefix-frames.json"
    done
  done
done
python3 tools/profile-summary.py dist/profile
