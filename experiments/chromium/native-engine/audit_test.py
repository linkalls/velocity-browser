"""Ensure an import/hidden/mangled symbol cannot pass the native export gate."""
import unittest
from audit import defined_entry


class ExportGateTest(unittest.TestCase):
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
