"""Ensure an import/hidden/mangled symbol cannot pass the native export gate."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from audit import ANDROID, SERVICE_COUNT_KEY, SERVICE_PREFIX_KEY, audit, audit_manifest, defined_entry


class ManifestAuditTest(unittest.TestCase):
    def manifest(self, prefix=None, count=2):
        root = ET.Element("manifest", {"package": "com.cefrium"})
        app = ET.SubElement(root, "application")
        if count is not None:
            ET.SubElement(app, "meta-data", {ANDROID + "name": SERVICE_COUNT_KEY,
                                            ANDROID + "value": str(count)})
        if prefix is not None:
            ET.SubElement(app, "meta-data", {ANDROID + "name": SERVICE_PREFIX_KEY,
                                            ANDROID + "value": prefix})
        base = prefix or "org.chromium.content.app.SandboxedProcessService"
        for i in range(2):
            ET.SubElement(app, "service", {ANDROID + "name": base + str(i),
                                          ANDROID + "isolatedProcess": "true",
                                          ANDROID + "exported": "false"})
        return root

    def test_legacy_pool(self):
        report = audit_manifest(self.manifest())
        self.assertEqual(report["java_sandboxed_services"], 2)
        self.assertEqual(report["java_service_pool_gate"], "PASS")

    def test_cefrium_renamed_pool(self):
        report = audit_manifest(self.manifest("com.cefrium.ChildServices$Sandboxed"))
        self.assertEqual(report["java_sandboxed_services"], 2)
        self.assertEqual(report["java_service_pool_gate"], "PASS")
        self.assertEqual(report["native_services"], 0)

    def test_metadata_selects_pool_without_counting_other_services(self):
        root = self.manifest("com.cefrium.ChildServices$Sandboxed")
        app = root.find("application")
        for name in ("org.chromium.content.app.SandboxedProcessService0",
                     "com.cefrium.ChildServices$Privileged0",
                     "com.cefrium.ChildServices$Sandboxed0extra"):
            ET.SubElement(app, "service", {ANDROID + "name": name,
                                          ANDROID + "isolatedProcess": "true"})
        self.assertEqual(audit_manifest(root)["java_sandboxed_services"], 2)

    def test_relative_class_names(self):
        root = self.manifest("com.cefrium.ChildServices$Sandboxed")
        for service in root.findall("application/service"):
            name = service.get(ANDROID + "name")
            service.set(ANDROID + "name", name.removeprefix("com.cefrium"))
        report = audit_manifest(root)
        self.assertEqual(report["sandboxed_services_base"], "com.cefrium.ChildServices$Sandboxed")
        self.assertEqual(report["java_service_pool_gate"], "PASS")

    def test_metadata_prefix_is_not_resolved_as_an_android_class_attribute(self):
        report = audit_manifest(self.manifest(".ChildServices$Sandboxed"))
        self.assertEqual(report["java_service_pool_gate"], "FAIL")

    def test_missing_duplicate_extra_or_nonisolated_slots_fail(self):
        for fault in ("missing", "duplicate", "extra", "nonisolated"):
            with self.subTest(fault=fault):
                root = self.manifest()
                app = root.find("application")
                services = app.findall("service")
                if fault == "missing":
                    app.remove(services[1])
                elif fault == "duplicate":
                    services[1].set(ANDROID + "name", services[0].get(ANDROID + "name"))
                elif fault == "extra":
                    ET.SubElement(app, "service", {ANDROID + "name":
                        "org.chromium.content.app.SandboxedProcessService2",
                        ANDROID + "isolatedProcess": "true"})
                else:
                    services[1].set(ANDROID + "isolatedProcess", "false")
                self.assertEqual(audit_manifest(root)["java_service_pool_gate"], "FAIL")

    def test_missing_invalid_or_zero_count_fails(self):
        for count in (None, "@integer/pool_count", 0, -1):
            with self.subTest(count=count):
                self.assertEqual(audit_manifest(self.manifest(count=count))[
                    "java_service_pool_gate"], "FAIL")

    def test_native_service_count_uses_android_attribute(self):
        root = self.manifest()
        app = root.find("application")
        ET.SubElement(app, "service", {ANDROID + "name": "com.example.ActualNative",
                                      ANDROID + "nativeService": "true"})
        ET.SubElement(app, "service", {ANDROID + "name":
            "org.chromium.content.app.NativeOnlySandboxedProcessService0"})
        report = audit_manifest(root)
        self.assertEqual(report["native_services"], 1)
        self.assertEqual(report["java_service_pool_gate"], "PASS")

    def test_native_service_does_not_satisfy_java_pool(self):
        root = self.manifest()
        root.find("application/service").set(ANDROID + "nativeService", "true")
        report = audit_manifest(root)
        self.assertEqual(report["java_sandboxed_services"], 1)
        self.assertEqual(report["java_service_pool_gate"], "FAIL")


class ExportGateTest(unittest.TestCase):
    def test_empty_abi_set_is_not_an_export_pass(self):
        with self.assertRaisesRegex(ValueError, "At least one ABI"):
            audit(Path("unused.aar"), Path("unused-output"), "", [])

    def test_defined_public_function(self):
        self.assertTrue(defined_entry(
            "  77: 000000001234 128 FUNC GLOBAL DEFAULT 18 NativeChildProcessService_onCreate"))

    def test_default_version(self):
        self.assertTrue(defined_entry(
            "  77: 000000001234 128 FUNC GLOBAL DEFAULT 18 NativeChildProcessService_onCreate@@V1"))

    def test_unusable_symbols(self):
        for kind, bind, visibility, index, name in (
                ("FUNC", "GLOBAL", "DEFAULT", "UND", "NativeChildProcessService_onCreate"),
                ("FUNC", "LOCAL", "DEFAULT", "18", "NativeChildProcessService_onCreate"),
                ("FUNC", "GLOBAL", "HIDDEN", "18", "NativeChildProcessService_onCreate"),
                ("OBJECT", "GLOBAL", "DEFAULT", "18", "NativeChildProcessService_onCreate"),
                ("FUNC", "GLOBAL", "DEFAULT", "18", "_ZNativeChildProcessService_onCreate"),
                ("FUNC", "GLOBAL", "DEFAULT", "18", "NativeChildProcessService_onCreate_extra"),
                ("FUNC", "GLOBAL", "DEFAULT", "18", "NativeChildProcessService_onCreate@V1")):
            with self.subTest(kind=kind, bind=bind, visibility=visibility, index=index, name=name):
                self.assertFalse(defined_entry(f"77: 1234 128 {kind} {bind} {visibility} {index} {name}"))

    def test_absent(self):
        self.assertFalse(defined_entry("77: 1234 128 FUNC GLOBAL DEFAULT 18 JNI_OnLoad"))


if __name__ == "__main__":
    unittest.main()
