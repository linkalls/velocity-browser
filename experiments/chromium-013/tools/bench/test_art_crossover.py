import importlib.util
import pathlib
import unittest

file = pathlib.Path(__file__).with_name("art_crossover.py")
spec = importlib.util.spec_from_file_location("art_crossover", file)
crossover = importlib.util.module_from_spec(spec)
spec.loader.exec_module(crossover)


class CrossoverTest(unittest.TestCase):
    def test_uses_abba_not_post_treatment_only(self):
        self.assertEqual(("verify", "speed", "speed", "verify"), crossover.MODES)

    def test_report_handles_partial_failure_without_fabricating_wins(self):
        records = [
            {"block": 1, "art_mode": "verify",
             "status": "measured", "launch_median_ms": 1100},
            {"block": 2, "art_mode": "speed", "status": "unavailable"},
            {"block": 3, "art_mode": "speed",
             "status": "measured", "launch_median_ms": 950},
            {"block": 4, "art_mode": "verify",
             "status": "measured", "launch_median_ms": 1300},
        ]
        report, medians = crossover.summarize(records)
        self.assertEqual(1200, medians["verify"])
        self.assertEqual(950, medians["speed"])
        self.assertIn("unavailable", report)
        self.assertIn("Do not claim faster-than-Brave", report)

    def test_no_measurements_are_not_zero(self):
        report, medians = crossover.summarize([])
        self.assertIsNone(medians["verify"])
        self.assertIsNone(medians["speed"])
        self.assertIn("unavailable", report)


if __name__ == "__main__":
    unittest.main()
