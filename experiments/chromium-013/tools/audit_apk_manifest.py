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
    # SDK 0.14+ declares native-only Android 17 renderers while keeping all forty
    # Java slots for older Android releases. Validate *both* pools, rather
    # than rejecting the very services Android 17 needs to load pages.
    native_names = {'com.cefrium.ChildServices$NativeSandboxed0',
                    'com.cefrium.ChildServices$NativeSandboxed1'}
    app = manifest.find('application')
    meta = ({m.get(audit.ANDROID + 'name'): m.get(audit.ANDROID + 'value')
             for m in app.findall('meta-data')} if app is not None else {})
    native_mode = meta.get('com.cefrium.JAVALESS_RENDERERS') == 'true'
    native_services = ([svc for svc in app.findall('service')
                        if svc.get(audit.ANDROID + 'nativeService') == 'true']
                       if app is not None else [])
    result['native_renderer_manifest_mode'] = native_mode
    result['native_renderer_declared'] = sorted(
        svc.get(audit.ANDROID + 'name', '') for svc in native_services)
    if native_mode:
        if result['native_services'] != 2 or result['native_renderer_declared'] != sorted(native_names):
            errors.append('Android 17 native renderer pool must declare exactly two services')
        if meta.get('org.chromium.content.browser.NATIVE_SANDBOXED_SERVICES_NAME') != (
                'com.cefrium.ChildServices$NativeSandboxed'):
            errors.append('Native renderer service prefix metadata is missing')
        if app.get(audit.ANDROID + 'zygotePreloadNativeLib') != 'libcef.so':
            errors.append('Native renderer zygote preload library is missing')
        for svc in native_services:
            name = svc.get(audit.ANDROID + 'name')
            props = {x.get(audit.ANDROID + 'name'): x.get(audit.ANDROID + 'value')
                     for x in svc.findall('property')}
            if (svc.get(audit.ANDROID + 'isolatedProcess') != 'true'
                    or svc.get(audit.ANDROID + 'exported') != 'false'
                    or svc.get(audit.ANDROID + 'enabled') == 'false'
                    or svc.get(audit.ANDROID + 'externalService') == 'true'):
                errors.append('Native renderer must be isolated, private and enabled: ' + name)
            if props.get('android.app.PROPERTY_NATIVE_SERVICE_LIBRARY_NAME') != 'libcef.so':
                errors.append('Native renderer missing libcef.so property: ' + name)
            if props.get('android.app.PROPERTY_NATIVE_SERVICE_FUNCTION_NAME') != (
                    'NativeChildProcessService_onCreate'):
                errors.append('Native renderer missing entry function property: ' + name)
            if name and name.endswith('0') and svc.get(audit.ANDROID + 'useAppZygote') != 'true':
                errors.append('Native renderer slot zero must use app zygote')
    elif result['native_services']:
        errors.append('Unmarked native-only renderer services are unsafe')
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
