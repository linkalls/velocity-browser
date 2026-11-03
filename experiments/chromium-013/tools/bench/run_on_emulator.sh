#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist/browser-bench
adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

# Install a non-debuggable x86_64 release APK with disposable CI signature.
# This must never be mixed with the permanent arm64 Preview signing identity.
velocity_apk='dist/browser-bench/velocity-release.apk'
test -s "$velocity_apk"
adb install -r "$velocity_apk" > dist/browser-bench/velocity-install.txt 2>&1 || {
  cat dist/browser-bench/velocity-install.txt
  exit 1
}

# Competitor APKs come only from official hosts. An unavailable source is
# explicitly recorded; this harness must not silently use a third-party APK.
for name in brave vivaldi; do
  apk="dist/browser-bench/apks/$name.apk"
  if [[ -s "$apk" ]]; then
    if ! adb install -r "$apk" > "dist/browser-bench/$name-install.txt" 2>&1; then
      printf 'Installation unavailable for %s; see installation log\n' "$name"
      rm -f "$apk"
    fi
  else
    printf 'Official %s x86_64 APK unavailable\n' "$name"
  fi
done

python3 experiments/chromium-013/tools/bench/browser_bench.py \
  --out dist/browser-bench --repetitions 4 --settle-seconds 5

# Exploratory ABBA crossover: alternate ART verify/speed modes on the
# identical APK, not just a speed-only trial after cache warmup.
python3 experiments/chromium-013/tools/bench/art_crossover.py   --out dist/browser-bench/art-crossover   --repetitions 3 --settle-seconds 4

adb exec-out screencap -p > dist/browser-bench/last-screen.png || true
