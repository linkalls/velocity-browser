#!/usr/bin/env bash
set -euo pipefail

output='dist/browser-reference-ui'
mkdir -p "$output"
adb wait-for-device
adb shell getprop ro.build.fingerprint > "$output/emulator-fingerprint.txt"
adb shell pm list packages > "$output/installed-packages.txt"
# Record actual installed versions, not expected versions from release pages.
for package in com.android.chrome com.vivaldi.browser; do
  adb shell dumpsys package "$package" | grep -E 'versionName=|versionCode=' | head -4 \
    > "$output/${package}.package.txt" || true
done

if [[ -s "$RUNNER_TEMP/vivaldi-reference.apk" ]]; then
  if adb install -r "$RUNNER_TEMP/vivaldi-reference.apk" \
      > "$output/vivaldi-install.txt" 2>&1; then
    echo 'Vivaldi official x86_64 APK installed' >> "$output/vivaldi-install.txt"
  else
    echo 'Vivaldi official x86_64 APK could not be installed' >> "$output/vivaldi-install.txt"
  fi
else
  printf 'Vivaldi download unavailable; browser observation will be NOT TESTED.\n' \
    > "$output/vivaldi-install.txt"
fi

# Keep screenshot dimensions and app states reproducible.
adb shell settings put system font_scale 1.0
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell wm size 1080x2400
adb shell wm density 440
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true

REFERENCE_OUTPUT="$output" python3 docs/browser-ux-research/tools/observe_android_browsers.py \
  > "$output/runner.log" 2>&1 || {
    echo 'Reference UI observation incomplete; see log' >&2
    cat "$output/runner.log" >&2
  }
cat "$output/runner.log" || true
# Summarize whether screenshots were actually captured in the run summary.
if [[ -f "$output/observations.json" ]]; then
  python3 - <<'PY'
import json
from pathlib import Path
p = Path('dist/browser-reference-ui/observations.json')
d = json.loads(p.read_text())
with open(__import__('os').environ['GITHUB_STEP_SUMMARY'], 'a') as f:
    f.write('## Reference Android UI audit (screenshots; not a speed test)\n')
    for name, browser in d['browsers'].items():
        f.write(f"- **{name}**: actual package version `{browser.get('version')}`; installed={browser.get('installed')}\n")
        for observation in browser.get('observations', []):
            f.write(f"  - {observation['screen']}: {observation['screenshot']}\n")
PY
fi
