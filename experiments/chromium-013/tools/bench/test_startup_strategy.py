import importlib.util
from pathlib import Path
import unittest

p = Path(__file__).with_name("startup_strategy.py")
spec = importlib.util.spec_from_file_location("startup_strategy", p)
strategy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(strategy)


class StartupStrategyTest(unittest.TestCase):
    def test_order_is_balanced_against_warmup(self):
        self.assertEqual(("post", "attach", "attach", "post"), strategy.ORDER)

    def test_parses_numeric_phases_and_js_dom_readiness(self):
        log = """I/VelocityCefStartup: CEFStartupStrategy strategy=attach
I/VelocityCefStartup: CEFStartupTiming provider_ms=100 ui_wait_ms=40 pool_ms=1 total_ms=220
I/VelocityCefStartup: CEF surface created and page navigation started
I/VelocityCefStartup: CEFPageTiming synthetic_dom_ready_ms=37"""
        result = strategy.parse_log(log)
        self.assertEqual("attach", result["strategy"])
        self.assertEqual(320, result["surface_ready_ms"])
        self.assertEqual(37, result["dom_js_ready_ms"])
        self.assertEqual(40, result["phases_ms"]["ui_wait_ms"])

    def test_missing_log_is_not_a_fake_benchmark_success(self):
        result = strategy.parse_log("some unrelated log")
        self.assertIsNone(result["surface_ready_ms"] if "surface_ready_ms" in result else None)
        self.assertIsNone(result["dom_js_ready_ms"])
        self.assertFalse(result["renderer_ready"])
        records = [{"iteration": i, "mode": mode, "status": "failed"}
                   for i, mode in enumerate(strategy.ORDER, 1)]
        self.assertIn("incomplete or failed", strategy.summarize(records))


if __name__ == "__main__":
    unittest.main()
