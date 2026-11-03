import importlib.util
import pathlib
import zipfile
import unittest
from tempfile import TemporaryDirectory

path = pathlib.Path(__file__).with_name("native_packaging_bench.py")
spec = importlib.util.spec_from_file_location("native_packaging_bench", path)
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class NativePackagingTest(unittest.TestCase):
    def test_balanced_abba(self):
        self.assertEqual(("legacy", "mmap", "mmap", "legacy"), bench.BLOCKS)

    def test_missing_blocks_cannot_be_declared_fast(self):
        data = [
            {"block": 1, "variant": "legacy", "status": "measured",
             "activity_median_ms": 1800},
            {"block": 2, "variant": "mmap", "status": "unavailable"},
            {"block": 3, "variant": "mmap", "status": "measured",
             "activity_median_ms": 800},
            {"block": 4, "variant": "legacy", "status": "measured",
             "activity_median_ms": 1700}
        ]
        report, medians = bench.summarize(data, {"legacy": {
            "compression": "DEFLATED", "lib_size": 1
        }, "mmap": {"compression": "STORED", "lib_size": 1}})
        self.assertIsNone(medians["mmap"])
        self.assertEqual(1750, medians["legacy"])
        self.assertIn("unavailable / incomplete", report)

    def test_reads_actual_zip_compression(self):
        with TemporaryDirectory() as d:
            p = pathlib.Path(d) / "example.apk"
            with zipfile.ZipFile(p, "w") as z:
                z.writestr(bench.NATIVE_SO, b"ELFfake", compress_type=zipfile.ZIP_STORED)
            info = bench.packaging_info(p)
            self.assertEqual("STORED", info["compression"])
            self.assertEqual(7, info["lib_size"])


if __name__ == "__main__":
    unittest.main()
