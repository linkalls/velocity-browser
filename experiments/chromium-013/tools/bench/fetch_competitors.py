#!/usr/bin/env python3
"""Fetch only official Brave/Vivaldi emulator APKs, with provenance log.

Missing upstream assets are represented as unavailable, never silently
replaced with an unofficial APK mirror.
"""
import hashlib
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

OUT = Path('dist/browser-bench/apks')
OUT.mkdir(parents=True, exist_ok=True)
USER_AGENT = 'Velocity-Benchmark-CI/1.0 (+https://github.com/linkalls/velocity-browser)'


def request(url, maximum=1_000_000):
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT,
        'Accept': 'application/vnd.github+json' if 'api.github.com' in url else '*/*'})
    with urllib.request.urlopen(req, timeout=60) as response:
        assert response.geturl().startswith('https://'), 'Redirected outside HTTPS'
        data = response.read(maximum + 1)
        if len(data) > maximum:
            raise ValueError('Response exceeded limit')
        return data


def fetch_apk(url, target, expected_sha=None):
    assert url.startswith('https://'), 'No insecure download URLs'
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    digest = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(req, timeout=120) as response, target.open('wb') as dest:
        assert response.geturl().startswith('https://')
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            if total > 550_000_000:
                raise ValueError('Unusually large APK')
            dest.write(chunk)
            digest.update(chunk)
    if total < 1_000_000:
        raise ValueError('APK unexpectedly small')
    sha = digest.hexdigest()
    if expected_sha and sha.lower() != expected_sha.lower():
        target.unlink(missing_ok=True)
        raise ValueError('Official Brave SHA256 mismatch')
    return {'status': 'downloaded', 'url': url, 'sha256': sha, 'size_bytes': total,
            'sha256_verified': bool(expected_sha)}


def brave():
    j = json.loads(request('https://api.github.com/repos/brave/brave-browser/releases/latest'))
    assets = {x['name']: x for x in j['assets']}
    filename = 'BraveMonox64.apk'
    if filename not in assets:
        raise ValueError('Official stable release has no x86_64 APK')
    asset = assets[filename]
    url = asset['browser_download_url']
    assert url.startswith('https://github.com/brave/brave-browser/releases/download/')
    expected_sha = asset.get('digest', '').removeprefix('sha256:')
    if len(expected_sha) != 64:
        checksum_asset = assets.get(filename + '.sha256')
        if checksum_asset is None:
            raise ValueError('Brave release has no checksum')
        checksum_text = request(checksum_asset['browser_download_url'], 4096).decode('ascii')
        matched = re.search(r'\b([a-fA-F0-9]{64})\b', checksum_text)
        if matched is None:
            raise ValueError('Could not parse official Brave checksum')
        expected_sha = matched.group(1)
    result = fetch_apk(url, OUT / 'brave.apk', expected_sha)
    result['release'] = j['tag_name']
    return result


def vivaldi():
    html = request('https://vivaldi.com/android/', 1_500_000).decode('utf-8')
    matches = re.findall(r'https://downloads\.vivaldi\.com/stable/Vivaldi\.[A-Za-z0-9._-]+_x86-64\.apk', html)
    if not matches:
        raise ValueError('No official Vivaldi Intel 64-bit APK URL found')
    # HTTPS authenticity only: Vivaldi did not provide a pinned checksum here.
    result = fetch_apk(matches[0], OUT / 'vivaldi.apk')
    result['sha256_verified'] = False
    return result


def main():
    results = {}
    for name, getter in [('brave', brave), ('vivaldi', vivaldi)]:
        try:
            results[name] = getter()
            print(name, 'downloaded', results[name]['size_bytes'], flush=True)
        except Exception as e:
            (OUT / (name + '.apk')).unlink(missing_ok=True)
            results[name] = {'status': 'unavailable', 'reason': type(e).__name__ + ': ' + str(e)}
            print(name, 'unavailable', str(e), flush=True)
    (OUT.parent / 'downloads.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
