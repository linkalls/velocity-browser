#!/usr/bin/env python3
"""Real Android audio-focus regression with a separate playing AudioTrack app.

Run only on an ephemeral emulator: the browser and probe packages are removed.
A separate UID requesting MAY_DUCK is the positive control for loss detection.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from page_startup_bench import adb, require_emulator

BROWSER = 'dev.velocity.browser.chromium013'
PLAYER = 'dev.velocity.audiofocusprobe.player'
DUCKER = 'dev.velocity.audiofocusprobe.ducker'
BROWSER_ACTIVITY = BROWSER + '/dev.velocity.chromium.ChromiumTestActivity'
PROBE_ACTIVITY = '/dev.velocity.audiofocusprobe.ProbeActivity'


def audio_losses(log):
    return [int(value) for value in re.findall(r'player focus_change=(-?\d+)', log)
            if int(value) < 0]


def collect():
    return adb('logcat', '-d', '-s', 'VelocityAudioProbe:I', 'VelocityCefStartup:I', '*:S')


def run_case(name, apk, out):
    dest = out / name
    dest.mkdir(parents=True, exist_ok=True)
    for package in (BROWSER, DUCKER, PLAYER):
        adb('shell', 'am', 'force-stop', package)
    # The packages are test-only, and require_emulator() has run first.
    subprocess.run(['adb', 'uninstall', BROWSER], capture_output=True, timeout=60)
    adb('install', str(apk), timeout=180)
    adb('logcat', '-c')
    adb('shell', 'am', 'start', '-W', '-n', PLAYER + PROBE_ACTIVITY)
    time.sleep(2)
    ready = collect()
    if 'player ready focus=granted play_state=3' not in ready:
        (dest / 'setup-logcat.txt').write_text(ready)
        raise RuntimeError('Background music probe did not obtain focus and start playback')
    # Quiet native home: no remote media page or autoplay is required.
    launch = adb('shell', 'am', 'start', '-W', '-n', BROWSER_ACTIVITY)
    (dest / 'activity.txt').write_text(launch)
    time.sleep(10)
    opening = collect()
    (dest / 'opening-logcat.txt').write_text(opening)
    if 'CEF surface created and page navigation started' not in opening:
        raise RuntimeError('Browser did not create its actual Chromium Surface')
    if not adb('shell', 'pidof', PLAYER).strip():
        raise RuntimeError('Background music app died; cannot validate focus retention')
    # Foreground the browser again, exercising Activity.onResume as well as
    # the first-tab activation path, while the other app keeps playing.
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    adb('shell', 'am', 'start', '-W', '-n', BROWSER_ACTIVITY)
    time.sleep(3)
    returning = collect()
    (dest / 'returning-logcat.txt').write_text(returning)
    (dest / 'audio-service.txt').write_text(adb('shell', 'dumpsys', 'audio'))
    (dest / 'audio-flinger.txt').write_text(adb('shell', 'dumpsys', 'media.audio_flinger'))
    result = {'case': name, 'opening_focus_losses': audio_losses(opening),
              'all_focus_losses_before_control': audio_losses(returning),
              'browser_surface_created': True, 'other_player_alive': True}
    # Prove the observer can detect ducking instead of merely seeing no logs.
    adb('shell', 'am', 'force-stop', BROWSER)
    adb('shell', 'am', 'force-stop', PLAYER)
    adb('logcat', '-c')
    adb('shell', 'am', 'start', '-W', '-n', PLAYER + PROBE_ACTIVITY)
    time.sleep(2)
    if 'player ready focus=granted play_state=3' not in collect():
        raise RuntimeError('Positive-control player did not start')
    adb('shell', 'am', 'start', '-W', '-n', DUCKER + PROBE_ACTIVITY)
    time.sleep(2)
    control = collect()
    (dest / 'positive-control-logcat.txt').write_text(control)
    result['positive_control_detected_loss'] = bool(audio_losses(control))
    if 'ducker ready focus=granted' not in control or not result['positive_control_detected_loss']:
        raise RuntimeError('MAY_DUCK positive control failed; no trustworthy focus result')
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--fixed', type=Path, required=True)
    parser.add_argument('--player', type=Path, required=True)
    parser.add_argument('--ducker', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    require_emulator()
    args.out.mkdir(parents=True, exist_ok=True)
    adb('install', '-r', str(args.player))
    adb('install', '-r', str(args.ducker))
    results = []
    failed = False
    for name in ('baseline', 'fixed'):
        try:
            record = run_case(name, getattr(args, name), args.out)
            record['status'] = 'measured'
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            record = {'case': name, 'status': 'failed', 'reason': str(error)}
            failed = True
        results.append(record)
        print(json.dumps(record), flush=True)
        (args.out / 'results.json').write_text(json.dumps(results, indent=2))
    fixed = next(r for r in results if r['case'] == 'fixed')
    if fixed.get('all_focus_losses_before_control'):
        failed = True
    lines = ['# Quiet-home audio-focus regression', '',
             'Separate Android app playing a looping AudioTrack with granted music focus.',
             'Negative changes indicate focus loss/ducking; a separate MAY_DUCK UID is the positive control.', '',
             '| Build | Open losses | Open + return losses | Positive control | Status |',
             '|---|---|---|---|---|']
    for record in results:
        lines.append(f'| {record["case"]} | {record.get("opening_focus_losses", "unavailable")} | '
                     f'{record.get("all_focus_losses_before_control", "unavailable")} | '
                     f'{record.get("positive_control_detected_loss", "unavailable")} | {record["status"]} |')
    (args.out / 'summary.md').write_text('\n'.join(lines) + '\n')
    if failed:
        raise SystemExit('Audio-focus test failed or could not validate the fixture')


if __name__ == '__main__':
    main()
