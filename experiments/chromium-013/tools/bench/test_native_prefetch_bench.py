import importlib.util
from pathlib import Path
import unittest
import tempfile
from unittest import mock

p = Path(__file__).with_name("native_prefetch_bench.py")
spec = importlib.util.spec_from_file_location("native_prefetch_bench", p)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class NativePrefetchTest(unittest.TestCase):
    def test_cross_over_and_bounds(self):
        self.assertEqual(("off", "prefetch16", "read16", "read16",
                          "prefetch16", "off"), mod.ORDER)
        self.assertEqual({"off": 0, "prefetch16": 16, "read16": 16}, mod.EXPECTED)
        self.assertEqual("read", mod.MODES["read16"])

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


    def test_report_exposes_pss_and_failure_reason_without_converting_to_zero(self):
        records = [
            {"block": 1, "variant": "off", "status": "measured",
             "activity_median_ms": 1500, "pss_median_kib": 122880},
            {"block": 2, "variant": "prefetch16", "status": "unavailable",
             "reason": "Renderer smoke failed", "pss_median_kib": None},
            {"block": 3, "variant": "prefetch16", "status": "measured",
             "activity_median_ms": 1200, "pss_median_kib": 143360},
            {"block": 4, "variant": "off", "status": "measured",
             "activity_median_ms": 1450, "pss_median_kib": 123904},
        ]
        report, medians = mod.summarize(records)
        self.assertIn("PSS median (MiB)", report)
        self.assertIn("120.0", report)
        self.assertIn("140.0", report)
        self.assertIn("Renderer smoke failed", report)
        self.assertIn("—", report)
        self.assertIsNone(medians["prefetch16"])

    def test_workflow_cli_accepts_settle_seconds_and_passes_it_to_sampler(self):
        # Regression for workflow run 40353287367: --settle-seconds was rejected
        # before the emulator could execute any of the ABBA measurements.
        observed = []

        def fake_block(number, variant, path, directory, repetitions, settle):
            observed.append((number, variant, str(path), repetitions, settle))
            requested = mod.EXPECTED[variant]
            return {
                "block": number, "variant": variant, "status": "measured",
                "activity_median_ms": 1200,
                "samples": [{"cefrium_native_prefetch": {
                    "requested_mib": requested,
                    "mode": mod.MODES[variant],
                    "status": "completed" if requested else None,
                }}],
            }

        with tempfile.TemporaryDirectory() as temp:
            with mock.patch.object(mod, "run_block", side_effect=fake_block):
                mod.main(["--off", "off.apk", "--prefetch16", "map.apk",
                          "--read16", "read.apk",
                          "--out", temp, "--repetitions", "1",
                          "--settle-seconds", "5.5"])
        self.assertEqual(list(mod.ORDER), [x[1] for x in observed])
        self.assertEqual([5.5] * 6, [x[4] for x in observed])
        self.assertEqual([1] * 6, [x[3] for x in observed])

    def test_cold_cache_flag_reaches_every_real_block_and_is_labeled(self):
        calls = []

        def fake_block(number, variant, path, directory, repetitions, settle,
                       cold_cache=False):
            calls.append((variant, cold_cache))
            return {"block": number, "variant": variant, "status": "measured",
                    "activity_median_ms": 2000,
                    "samples": [{"cefrium_native_prefetch": {
                        "requested_mib": mod.EXPECTED[variant],
                        "mode": mod.MODES[variant],
                        "status": "completed" if variant != "off" else None}}]}

        with tempfile.TemporaryDirectory() as temp:
            with mock.patch.object(mod, "run_block", side_effect=fake_block):
                mod.main(["--off", "off.apk", "--prefetch16", "map.apk",
                          "--read16", "read.apk", "--out", temp,
                          "--repetitions", "1", "--cold-cache"])
            report = (Path(temp) / "summary.md").read_text()
        self.assertEqual([(mode, True) for mode in mod.ORDER], calls)
        self.assertIn("Rooted AOSP emulator", report)

    def test_settle_seconds_refuses_invalid_values(self):
        # Avoid marking an unvalidated, not-yet-initialized CEF process as ready.
        for value in ("0", "-1", "61"):
            with self.subTest(value=value):
                with self.assertRaises(SystemExit) as caught:
                    mod.main(["--off", "off.apk", "--prefetch16", "map.apk",
                              "--read16", "read.apk",
                              "--settle-seconds", value])
                self.assertEqual(2, caught.exception.code)

    def test_read_variant_without_runtime_read_log_fails_closed(self):
        """Reject misleading fast results if the buffered mode did not run."""
        def fake_block(number, variant, path, directory, repetitions, settle):
            return {
                "block": number, "variant": variant, "status": "measured",
                "activity_median_ms": 500,
                "samples": [{"cefrium_native_prefetch": {
                    "requested_mib": mod.EXPECTED[variant],
                    # Pretend the APK incorrectly kept the mmap mode.
                    "mode": "mmap",
                    "status": "completed" if variant != "off" else None,
                }}],
            }

        with tempfile.TemporaryDirectory() as temp:
            with mock.patch.object(mod, "run_block", side_effect=fake_block):
                with self.assertRaises(SystemExit):
                    mod.main(["--off", "off.apk", "--prefetch16", "map.apk",
                              "--read16", "read.apk", "--out", temp,
                              "--repetitions", "1"])
            result = (Path(temp) / "results.json").read_text()
            self.assertIn("Runtime log does not prove the requested prefetch method",
                          result)
            self.assertIn('"status": "unavailable"', result)


if __name__ == "__main__":
    unittest.main()
