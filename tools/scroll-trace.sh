#!/usr/bin/env bash
# Record a real Android FrameTimeline/scheduling trace. No synthetic performance values.
set -euo pipefail
if [ "$#" -ne 2 ] || [[ "$1" != static && "$1" != dynamic ]]; then
  echo 'Usage: bash tools/scroll-trace.sh static|dynamic output.perfetto-trace' >&2
  exit 2
fi
scenario="$1"
output="$2"
command -v adb >/dev/null
adb get-state >/dev/null
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$api" -lt 31 ]; then echo 'FrameTimeline requires API 31 or newer' >&2; exit 2; fi
config=$(mktemp)
trap 'rm -f "$config"' EXIT
cat > "$config" <<'CONFIG'
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
duration_ms: 15000
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_wakeup"
  ftrace_events: "power/cpu_frequency"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "input"
  atrace_categories: "wm"
  atrace_apps: "dev.velocity.browser"
} } }
CONFIG
# Feed config through stdin: shell-created /data/local/tmp files have an SELinux
# label that perfetto cannot read on API36, including rooted emulator images.
adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/velocity-scroll.trace < "$config"
adb pull /data/misc/perfetto-traces/velocity-scroll.trace "$output" >/dev/null
adb shell rm -f /data/misc/perfetto-traces/velocity-scroll.trace
python3 - "$scenario" "$output" <<'PY'
import json,sys,hashlib
from pathlib import Path
p=Path(sys.argv[2]);assert p.stat().st_size>0,'No trace recorded; measurement is unverified'
Path(str(p)+'.json').write_text(json.dumps({'scenario':sys.argv[1],'trace':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'metrics':'NOT_ANALYZED','deviceComparison':'NOT_MEASURED'},indent=2)+'\n')
PY
