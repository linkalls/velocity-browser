#!/usr/bin/env python3
"""Preserve saved tabs while refusing automatic remote navigation on startup.

Only disposable emulator profiles are seeded/replaced. Baseline proves a saved
Google selection used to open automatically. Fixed build must show native home,
retain every URL, and return home on a launcher intent after explicit selection.
"""
import argparse
import html
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from page_startup_bench import adb, require_emulator, capture_screen

APP = 'dev.velocity.browser.chromium013'
ACTIVITY = APP + '/dev.velocity.chromium.ChromiumTestActivity'
PREF = 'shared_prefs/cefrium_tabs_v1.xml'
GOOGLE = 'https://www.google.com/'


def home_visible(source):
    root = ET.fromstring(source)
    editors = [n for n in root.iter('node') if n.get('class') == 'android.widget.EditText']
    return bool(editors) and any(n.get('text') == 'クイックアクセス' for n in root.iter('node')) and not any(
        'google.' in n.get('text', '').lower() for n in editors)


def google_visible(source):
    root = ET.fromstring(source)
    return any('google.' in n.get('text', '').lower() for n in root.iter('node')
               if n.get('class') == 'android.widget.EditText')


def hierarchy(dest, name):
    remote = '/sdcard/velocity-startup-home.xml'
    adb('shell', 'rm', '-f', remote)
    adb('shell', 'uiautomator', 'dump', remote, timeout=45)
    source = adb('shell', 'cat', remote)
    log = adb('logcat', '-d', '-s', 'VelocityCefStartup:I', '*:S')
    (dest / (name + '-logcat.txt')).write_text(log)
    if 'CEF surface created and page navigation started' not in log:
        raise RuntimeError('No actual Chromium Surface for the startup test')
    if not adb('shell', 'pidof', APP).strip():
        raise RuntimeError('Browser process exited')
    ET.fromstring(source)
    (dest / (name + '.xml')).write_text(source)
    capture_screen(dest / (name + '.png'))
    return source


def launch(cold=False):
    if cold:
        adb('logcat', '-c')
    return adb('shell', 'am', 'start', '-W', '-n', ACTIVITY,
               '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER')


def seed(urls):
    adb('shell', 'am', 'force-stop', APP)
    adb('shell', 'run-as', APP, 'mkdir', '-p', 'shared_prefs')
    adb('shell', 'run-as', APP, 'rm', '-f', PREF + '.bak')
    state = {'normal': [{'url': url, 'title': 'Saved Google' if i == 0 else 'Saved ' + str(i)}
                        for i, url in enumerate(urls)], 'selected': 0}
    source = '<map><string name="state">' + html.escape(json.dumps(state)) + '</string></map>'
    result = subprocess.run(['adb', 'shell', 'run-as', APP, 'sh', '-c', "'cat > " + PREF + "'"],
                            input=source.encode(), capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError('Could not seed disposable preferences: ' + result.stderr.decode(errors='replace'))
    # Verify the fixture itself before attributing navigation to the app.
    if snapshot()['normal'][0]['url'] != GOOGLE:
        raise RuntimeError('Selected Google fixture was not persisted')
    adb('logcat', '-c')


def snapshot():
    root = ET.fromstring(adb('shell', 'run-as', APP, 'cat', PREF))
    state = root.find("string[@name='state']")
    if state is None:
        raise RuntimeError('No saved tab snapshot')
    return json.loads(state.text)


def verify_preserved(urls, dest, name):
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(2)
    state = snapshot()
    (dest / (name + '-snapshot.json')).write_text(json.dumps(state, indent=2))
    saved = [tab['url'] for tab in state['normal']]
    if not all(url in saved for url in urls):
        raise RuntimeError('A saved page was lost')
    homes = [tab for tab in state['normal'] if tab.get('home') and tab['url'] == 'about:blank']
    if len(homes) != 1 or len(saved) != len(urls) + 1:
        raise RuntimeError('Home must be reusable without discarding or accumulating saved pages')
    return state


def tap_description(source, description):
    node = next((n for n in ET.fromstring(source).iter('node')
                 if n.get('content-desc') == description), None)
    if node is None:
        raise RuntimeError('Missing UI control: ' + description)
    x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', node.get('bounds', '')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1)


def install(apk):
    adb('shell', 'am', 'force-stop', APP)
    subprocess.run(['adb', 'uninstall', APP], capture_output=True, timeout=60)
    adb('install', str(apk), timeout=180)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--fixed', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    require_emulator()
    args.out.mkdir(parents=True, exist_ok=True)
    results = []
    try:
        install(args.baseline)
        seed([GOOGLE])
        launch(cold=True)
        time.sleep(6)
        control = hierarchy(args.out, 'baseline-selected-google')
        if not google_visible(control):
            raise RuntimeError('Baseline did not reproduce automatic saved-Google navigation')
        results.append({'case': 'baseline', 'saved_google_opened_automatically': True})
        install(args.fixed)
        for count in (1, 12):
            urls = [GOOGLE] + ['https://example.com/saved/' + str(i) for i in range(1, count)]
            seed(urls)
            launch(cold=True)
            time.sleep(5)
            source = hierarchy(args.out, 'fixed-' + str(count) + '-startup')
            if not home_visible(source):
                raise RuntimeError('Fixed startup did not show the offline home')
            verify_preserved(urls, args.out, 'fixed-' + str(count))
            adb('shell', 'am', 'force-stop', APP)
            launch(cold=True)
            time.sleep(4)
            source = hierarchy(args.out, 'fixed-' + str(count) + '-relaunch')
            if not home_visible(source):
                raise RuntimeError('A second cold launch reopened a saved page')
            verify_preserved(urls, args.out, 'fixed-' + str(count) + '-second')
            if count == 1:
                launch()
                time.sleep(2)
                source = hierarchy(args.out, 'before-explicit-selection')
                tap_description(source, 'タブ一覧')
                source = hierarchy(args.out, 'saved-tab-sheet')
                tap_description(source, 'タブを選択: Saved Google')
                time.sleep(5)
                source = hierarchy(args.out, 'explicitly-selected-google')
                if not google_visible(source):
                    raise RuntimeError('Explicit selection no longer opens the saved Google tab')
                # Same running Activity: manifest singleTop + launcher onNewIntent.
                launch()
                time.sleep(3)
                source = hierarchy(args.out, 'launcher-returns-home')
                if not home_visible(source):
                    raise RuntimeError('Launcher reopened the active Google tab instead of home')
            results.append({'case': 'fixed', 'saved_tab_count': count, 'startup_home': True,
                            'second_start_home': True, 'all_saved_urls_preserved': True,
                            'launcher_home_after_explicit_google': count == 1})
    except (OSError, RuntimeError, subprocess.SubprocessError, ET.ParseError, ValueError) as error:
        results.append({'status': 'failed', 'reason': str(error)})
        raise
    finally:
        (args.out / 'results.json').write_text(json.dumps(results, indent=2))
        (args.out / 'summary.md').write_text('# Startup home regression\n\n' +
            '\n'.join('- ' + json.dumps(record) for record in results) + '\n')


if __name__ == '__main__':
    main()
