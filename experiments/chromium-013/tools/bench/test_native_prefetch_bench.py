import importlib.util
from pathlib import Path
import unittest

p = Path(__file__).with_name("native_prefetch_bench.py")
spec = importlib.util.spec_from_file_location("native_prefetch_bench", p)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class NativePrefetchTest(unittest.TestCase):
    def test_cross_over_and_bounds(self):
        self.assertEqual(("off", "prefetch16", "prefetch16", "off"), mod.ORDER)
        self.assertEqual({"off": 0, "prefetch16": 16}, mod.EXPECTED)

    def test_missing_prefetch_is_not_presented_as_a_win(self):
        records = [
            {"block": 1, "variant": "off", "status": "measured",
             "activity_median_ms": 1500},
            {"block": 2, "variant": "prefetch16", "status": "unavailable"},
            {"block": 3, "variant": "prefetch16", "status": "measured",
             "activity_median_ms": 800},
            {"block": 4, "variant": "off", "status": "measured",
             "activity_median_ms": 1400},
        ]
        result, medians = mod.summarize(records)
        self.assertEqual(1450, medians["off"])
        self.assertIsNone(medians["prefetch16"])
        self.assertIn("incomplete, no win claim", result)


if __name__ == "__main__":
    unittest.main()
