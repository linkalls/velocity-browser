#!/usr/bin/env python3
"""Validate apkanalyzer's decoded, packaged Manifest for the 0.13 Java app."""
import importlib.util
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parents[2] / 'chromium/native-engine/audit.py'
spec = importlib.util.spec_from_file_location('cefrium_aar_audit', BASE)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def validate(manifest):
    result = audit.audit_manifest(manifest)
    result['scope'] = 'Decoded packaged APK manifest; not runtime sandbox proof'
    errors = []
    if manifest.get('package') != 'dev.velocity.browser.chromium013':
        errors.append('Unexpected applicationId')
    if result['sandboxed_services_base'] != 'com.cefrium.ChildServices$Sandboxed':
        errors.append('Unexpected Java service prefix')
    if result['java_service_pool_gate'] != 'PASS' or result['java_sandboxed_services'] != 40:
        errors.append('Expected all 40 isolated Java services')
    if any(s['exported'] != 'false' for s in result['java_services']):
        errors.append('Renderer services must not be exported')
    if not any(s['slot'] == 0 and s['use_app_zygote'] for s in result['java_services']):
        errors.append('Service 0 must use the app zygote')
    if result['native_services']:
        errors.append('Native services must not be enabled by this Java experiment')
    app = manifest.find('application')
    if app is not None:
        for service in app.findall('service'):
            if service.get(audit.ANDROID + 'enabled') == 'false' and service.get(
                    audit.ANDROID + 'name', '').startswith('com.cefrium.ChildServices$Sandboxed'):
                errors.append('Renderer service is disabled')
    result['errors'] = errors
    return result


if __name__ == '__main__':
    report = validate(ET.parse(sys.argv[1]).getroot())
    print(json.dumps(report, indent=2))
    raise SystemExit(2 if report['errors'] else 0)
