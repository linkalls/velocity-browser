#!/usr/bin/env bash
# One executable command for the emulator-runner's /bin/sh -c entry point.
# Android 16 also exercises native UI and an actual offline Chromium JPEG.
# API 37 retains startup/JS tests only until its ActivityManager restart
# instability is resolved separately.
set -euo pipefail
api="${1:?Android API level required}"
bash experiments/chromium-013/tools/boot-smoke.sh
if [[ "$api" == '36' ]]; then
  bash experiments/chromium-013/tools/ui-layout-screenshot-smoke.sh
else
  echo 'API37 screenshot loop not validated due to emulator ActivityManager instability' >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
fi
