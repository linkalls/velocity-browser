import importlib.util
from pathlib import Path
import unittest

file = Path(__file__).with_name("validate_benchmark.py")
spec = importlib.util.spec_from_file_location("validate_benchmark", file)
validate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validate)


class ValidationTest(unittest.TestCase):
    def valid_report(self):
        return {"velocity_build_variant": "release-r8-minified", "repetitions": 1,
                "browsers": {"Velocity": {"status": "measured", "samples": [{
                    "cefrium_surface_created": True,
                    "process_alive_after_settle": True,
                    "cefrium_startup_phases_ms": {"total_ms": 1234},
                    "pss_kib_after_settle": 100000
                }]}}}

    def test_valid_surface_and_live_renderer(self):
        self.assertEqual([], validate.validate(self.valid_report(), "release-r8-minified"))

    def test_rejects_benchmark_cheat_by_only_launching_activity(self):
        report = self.valid_report()
        report["browsers"]["Velocity"]["samples"][0]["cefrium_surface_created"] = False
        self.assertTrue(validate.validate(report, "release-r8-minified"))

    def test_every_fast_attach_trial_must_log_the_actual_strategy(self):
        report = self.valid_report()
        report["velocity_build_variant"] = "release-r8-fast-attach"
        report["browsers"]["Velocity"]["samples"][0]["cefrium_start_strategy"] = "post"
        errors = validate.validate(report, "release-r8-fast-attach", "attach")
        self.assertTrue(any("trial 1: startup strategy" in err for err in errors))
        report["browsers"]["Velocity"]["samples"][0]["cefrium_start_strategy"] = "attach"
        self.assertEqual([], validate.validate(report, "release-r8-fast-attach", "attach"))

    def test_rejects_variant_mismatch(self):
        self.assertTrue(validate.validate(self.valid_report(), "release-unminified"))


if __name__ == "__main__":
    unittest.main()
