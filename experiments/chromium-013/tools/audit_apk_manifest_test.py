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
