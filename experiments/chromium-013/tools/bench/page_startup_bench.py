#!/usr/bin/env python3
"""Same-page paired startup experiment. Host readiness includes adb/HTTP overhead.

A measured sample must paint content, run the page's button handler, report back
with a unique trial token, retain a live browser and produce a real screenshot.
This is a local fixture, not a real-site or Speedometer benchmark.
"""
import argparse
import hashlib
import json
import math
import statistics
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

PACKAGE = 'dev.velocity.browser.chromium013'
ACTIVITY = PACKAGE + '/dev.velocity.chromium.ChromiumTestActivity'
ORDER = ('baseline', 'candidate', 'candidate', 'baseline') * 2
FIXTURE = b'''<!doctype html><html><head><meta name="viewport" content="width=device-width">
<title>Velocity page benchmark</title><style>
body{font:18px system-ui;background:#101820;color:#eef5f7;padding:24px}
article{border:1px solid #567;padding:12px;margin:12px 0}button{padding:16px}
</style></head><body><h1>Velocity page benchmark</h1><button id="action">Run interaction</button>
<p id="status">Loading</p><main id="feed"></main><script>
const trial=new URLSearchParams(location.search).get('trial');
for(let i=0;i<80;i++){const a=document.createElement('article');
a.textContent='Article '+i+' - deterministic local browser rendering workload.';
document.getElementById('feed').appendChild(a)}
let handled=false;document.getElementById('action').onclick=()=>{
 document.getElementById('status').textContent='PAGE INTERACTION OK';handled=true};
let sent=false;
const observer=new PerformanceObserver(list=>{for(const e of list.getEntries()){
 if(e.name==='first-contentful-paint'&&!sent){sent=true;
 requestAnimationFrame(()=>{document.getElementById('action').click();
 requestAnimationFrame(()=>fetch('/ready?trial='+encodeURIComponent(trial),{
 method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({
 fcp_ms:e.startTime,dom_ms:performance.getEntriesByType('navigation')[0].domContentLoadedEventEnd,
 ready_ms:performance.now(),interaction_ok:handled&&
 document.getElementById('status').textContent==='PAGE INTERACTION OK'})}));});}}});
observer.observe({type:'paint',buffered:true});
</script></body></html>'''


def adb(*args, timeout=60):
    result = subprocess.run(['adb', *args], capture_output=True, timeout=timeout)
    output = (result.stdout + result.stderr).decode(errors='replace')
    if result.returncode:
        raise RuntimeError('adb failed: ' + output[-500:])
    return output


def validate_payload(data):
    for key in ('fcp_ms', 'ready_ms', 'dom_ms'):
        value = data.get(key)
        if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not 0 < value < 60000:
            raise ValueError('Invalid page timing: ' + key)
    if data.get('interaction_ok') is not True or data['ready_ms'] < data['fcp_ms']:
        raise ValueError('Paint/interaction gate failed')
    return data


class FixtureServer(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(('127.0.0.1', 8765), Handler)
        self.trials = {}
        self.lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        parsed = urlparse(self.path)
        token = parse_qs(parsed.query).get('trial', [''])[0]
        if parsed.path != '/fixture' or token not in self.server.trials:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(FIXTURE)

    def do_POST(self):
        parsed = urlparse(self.path)
        token = parse_qs(parsed.query).get('trial', [''])[0]
        if parsed.path != '/ready' or token not in self.server.trials:
            self.send_error(404)
            return
        try:
            length = int(self.headers.get('Content-Length', 0))
            if not 0 < length <= 2048:
                raise ValueError('Oversized report')
            data = validate_payload(json.loads(self.rfile.read(length)))
            with self.server.lock:
                record = self.server.trials[token]
                if 'page' not in record:
                    record['page'] = data
                    record['host_ready_ms'] = (time.monotonic() - record['started']) * 1000
                    record['event'].set()
            self.send_response(204)
            self.end_headers()
        except (ValueError, TypeError):
            self.send_error(400)


def one_trial(server, variant, apk, out, number, cold):
    dest = out / f'{number:02d}-{variant}'
    dest.mkdir(parents=True)
    adb('install', '-r', str(apk), timeout=180)
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('reverse', 'tcp:8765', 'tcp:8765')
    adb('logcat', '-c')
    if cold:
        # A refusal invalidates the experiment; never silently use a warm cache.
        adb('shell', 'sh', '-c', '"sync && echo 3 > /proc/sys/vm/drop_caches"')
    token = uuid.uuid4().hex
    record = {'event': threading.Event(), 'started': time.monotonic()}
    server.trials[token] = record
    launch = adb('shell', 'am', 'start', '-W', '-n', ACTIVITY, '--es',
                 'velocity_benchmark_url', 'http://127.0.0.1:8765/fixture?trial=' + token)
    (dest / 'activity.txt').write_text(launch)
    if not record['event'].wait(30):
        raise RuntimeError('No painted, interactive page report within 30 seconds')
    if not adb('shell', 'pidof', PACKAGE).strip():
        raise RuntimeError('Browser process exited')
    log = adb('logcat', '-d', '-s', 'VelocityCefStartup:I', '*:S')
    (dest / 'logcat.txt').write_text(log)
    if 'CEF surface created and page navigation started' not in log:
        raise RuntimeError('Missing native Surface')
    screen = subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True, timeout=25, check=True).stdout
    if not screen.startswith(b'\x89PNG'):
        raise RuntimeError('No screenshot')
    (dest / 'screen.png').write_bytes(screen)
    time.sleep(3)
    mem = adb('shell', 'dumpsys', 'meminfo', PACKAGE)
    (dest / 'meminfo.txt').write_text(mem)
    return {'variant': variant, 'trial': number, 'status': 'measured',
            'host_ready_ms': round(record['host_ready_ms'], 1), **record['page']}


def summary(records):
    lines = ['# Same-page startup experiment', '',
             'Local HTTP fixture over adb reverse, real FCP + DOM button handler.',
             'Host ready includes process launch, adb and HTTP callback overhead.', '',
             '| Variant | Valid trials | Host ready median / max (ms) | Page FCP median (ms) |',
             '|---|---:|---:|---:|']
    for variant in ('baseline', 'candidate'):
        selected = [r for r in records if r['variant'] == variant and r['status'] == 'measured']
        if len(selected) == ORDER.count(variant):
            ready = [r['host_ready_ms'] for r in selected]
            lines.append(f'| {variant} | {len(selected)} | {statistics.median(ready):.1f} / {max(ready):.1f} | {statistics.median(r["fcp_ms"] for r in selected):.1f} |')
        else:
            lines.append(f'| {variant} | {len(selected)} | INCOMPLETE | INCOMPLETE |')
    lines += ['', 'No real-site, physical-device, energy or cross-browser superiority claim.',
              'Eight balanced trials do not establish a population tail percentile.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--cold-cache', action='store_true')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    server = FixtureServer()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    records = []
    try:
        for number, variant in enumerate(ORDER, 1):
            try:
                record = one_trial(server, variant, getattr(args, variant), args.out, number, args.cold_cache)
            except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                record = {'variant': variant, 'trial': number, 'status': 'failed', 'reason': str(error)}
            records.append(record)
            print(json.dumps(record), flush=True)
            (args.out / 'results.json').write_text(json.dumps({'cold_cache_requested': args.cold_cache,
                'fixture_sha256': hashlib.sha256(FIXTURE).hexdigest(),
                'apk_sha256': {v: hashlib.sha256(getattr(args, v).read_bytes()).hexdigest() for v in ('baseline', 'candidate')},
                'records': records}, indent=2))
            (args.out / 'summary.md').write_text(summary(records))
    finally:
        server.shutdown()
        server.server_close()
    if any(r['status'] != 'measured' for r in records):
        raise SystemExit('At least one page trial failed; comparison invalid')


if __name__ == '__main__':
    main()
