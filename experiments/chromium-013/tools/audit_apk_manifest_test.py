import unittest
import xml.etree.ElementTree as ET
from audit_apk_manifest import validate, audit

A = audit.ANDROID


def manifest():
    root = ET.Element('manifest', package='dev.velocity.browser.chromium013')
    app = ET.SubElement(root, 'application')
    for key, value in ((audit.SERVICE_PREFIX_KEY, 'com.cefrium.ChildServices$Sandboxed'),
                       (audit.SERVICE_COUNT_KEY, '40')):
        ET.SubElement(app, 'meta-data', {A + 'name': key, A + 'value': value})
    for slot in range(40):
        ET.SubElement(app, 'service', {
            A + 'name': f'com.cefrium.ChildServices$Sandboxed{slot}',
            A + 'isolatedProcess': 'true', A + 'exported': 'false',
            A + 'useAppZygote': 'true' if slot == 0 else 'false'})
    return root


class PackagedManifestTest(unittest.TestCase):
    def test_renamed_pool_is_accepted(self):
        self.assertEqual([], validate(manifest())['errors'])

    def native_manifest(self):
        root = manifest()
        app = root.find('application')
        app.set(A + 'zygotePreloadNativeLib', 'libcef.so')
        for key, value in (
            ('com.cefrium.JAVALESS_RENDERERS', 'true'),
            ('org.chromium.content.browser.NATIVE_SANDBOXED_SERVICES_NAME',
             'com.cefrium.ChildServices$NativeSandboxed')):
            ET.SubElement(app, 'meta-data', {A + 'name': key, A + 'value': value})
        for idx in range(2):
            svc = ET.SubElement(app, 'service', {
                A + 'name': f'com.cefrium.ChildServices$NativeSandboxed{idx}',
                A + 'nativeService': 'true', A + 'isolatedProcess': 'true',
                A + 'exported': 'false', A + 'useAppZygote': 'true' if idx == 0 else 'false'})
            for key, value in (
                ('android.app.PROPERTY_NATIVE_SERVICE_LIBRARY_NAME', 'libcef.so'),
                ('android.app.PROPERTY_NATIVE_SERVICE_FUNCTION_NAME',
                 'NativeChildProcessService_onCreate')):
                ET.SubElement(svc, 'property', {A + 'name': key, A + 'value': value})
        return root

    def test_android17_native_renderer_services_are_accepted(self):
        self.assertEqual([], validate(self.native_manifest())['errors'])

    def test_native_security_failures_are_rejected(self):
        baseline = self.native_manifest()
        def clone():
            return ET.fromstring(ET.tostring(baseline))
        root = clone()
        for mutator in ('export', 'isolation', 'function', 'count', 'preload', 'marker'):
            with self.subTest(mutator=mutator):
                root = clone()
                app = root.find('application')
                native = next(x for x in app.findall('service')
                              if x.get(A + 'name', '').endswith('NativeSandboxed0'))
                if mutator == 'export':
                    native.set(A + 'exported', 'true')
                elif mutator == 'isolation':
                    native.set(A + 'isolatedProcess', 'false')
                elif mutator == 'function':
                    native.find('property').set(A + 'value', 'not_libcef.so')
                elif mutator == 'count':
                    app.remove(native)
                elif mutator == 'preload':
                    app.set(A + 'zygotePreloadNativeLib', 'anything.so')
                elif mutator == 'marker':
                    next(x for x in app.findall('meta-data')
                         if x.get(A + 'name') == 'com.cefrium.JAVALESS_RENDERERS').set(
                             A + 'value', 'false')
                self.assertTrue(validate(root)['errors'])

    def test_wrong_app_cannot_replace_old_experiment(self):
        root = manifest()
        root.set('package', 'dev.velocity.browser.chromiumtest')
        self.assertTrue(validate(root)['errors'])

    def test_broken_slots_rejected(self):
        for attribute, value in (('isolatedProcess', 'false'), ('exported', 'true'),
                                 ('enabled', 'false'), ('nativeService', 'true'),
                                 ('useAppZygote', 'false')):
            with self.subTest(attribute=attribute):
                root = manifest()
                root.find('application/service').set(A + attribute, value)
                self.assertTrue(validate(root)['errors'])

    def test_missing_or_duplicate_slot_rejected(self):
        for duplicate in (False, True):
            root = manifest()
            app = root.find('application')
            service = app.find('service')
            if duplicate:
                service.set(A + 'name', 'com.cefrium.ChildServices$Sandboxed1')
            else:
                app.remove(service)
            self.assertTrue(validate(root)['errors'])

    def test_wrong_metadata_rejected(self):
        root = manifest()
        root.find('application/meta-data').set(A + 'value', audit.DEFAULT_SERVICE_PREFIX)
        self.assertTrue(validate(root)['errors'])


if __name__ == '__main__':
    unittest.main()
