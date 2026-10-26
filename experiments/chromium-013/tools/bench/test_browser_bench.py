import importlib.util
import pathlib
import unittest

path = pathlib.Path(__file__).with_name('browser_bench.py')
spec = importlib.util.spec_from_file_location('browser_bench', path)
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class ParseTest(unittest.TestCase):
    def test_valid_launch(self):
        output = 'Status: ok\nLaunchState: COLD\nActivity: com.android.chrome/.Main\nTotalTime: 534\nWaitTime: 600\n'
        self.assertEqual(534, bench.parse_launch(output, 'com.android.chrome'))

    def test_missing_or_wrong_launch_is_unavailable(self):
        self.assertIsNone(bench.parse_launch('Error: Activity not started\n', 'x'))
        self.assertIsNone(bench.parse_launch('Status: ok\nActivity: other/.Main\nTotalTime: 150\n', 'x'))

    def test_launch_state_is_not_fabricated(self):
        self.assertEqual(bench.parse_launch_state('LaunchState: COLD\n'), 'COLD')
        self.assertIsNone(bench.parse_launch_state('Activity launched\n'))

    def test_memory_parses_explicit_totals(self):
        self.assertEqual(123456, bench.parse_pss('TOTAL PSS: 123,456 TOTAL RSS: 234567'))
        self.assertEqual(98765, bench.parse_pss(' TOTAL   98765    0    0\n'))
        self.assertIsNone(bench.parse_pss('Java Heap: 64 MB\n'))

    def test_cefrium_phase_log_is_numeric_and_optional(self):
        result = bench.parse_velocity_timing(
            'I/VelocityCefStartup: CEFStartupTiming provider_ms=400 ui_build_ms=13 ui_wait_ms=7 ui_ms=20 pool_ms=15 '
            'rules_ms=5 create_ms=1200 total_ms=1240')
        self.assertEqual(result['create_ms'], 1200)
        self.assertEqual(result['provider_ms'], 400)
        self.assertEqual(result['ui_build_ms'], 13)
        self.assertIsNone(bench.parse_velocity_timing('unrelated activity log'))

    def test_startup_resource_parsing_with_unknown_procfs_values(self):
        fields = bench.RESOURCE_FIELDS
        periods = ("provider", "activity", "total")
        sample = {f"{period}_{field}": n for n, (period, field) in enumerate(
            ((period, field) for period in periods for field in fields))}
        sample["provider_read_bytes"] = -1
        line = "CEFStartupResource " + " ".join(
            f"{key}={value}" for key, value in sample.items())
        result = bench.parse_velocity_resources(
            "I/VelocityCefStartup: " + line)
        self.assertEqual(-1, result["provider_read_bytes"])
        self.assertEqual(0, result["provider_wall_ms"])
        self.assertEqual(17, result["total_written_bytes"])
        self.assertIsNone(bench.parse_velocity_resources("no resources"))
        self.assertIsNone(bench.parse_velocity_resources(
            "CEFStartupResource provider_wall_ms=200"))

    def test_resource_report_does_not_make_zero_from_missing_values(self):
        r = {f"{period}_{field}": -1
             for period in ("provider", "activity", "total")
             for field in bench.RESOURCE_FIELDS}
        r.update(provider_wall_ms=1200, provider_cpu_ms=350,
                 provider_minor_faults=1700, provider_major_faults=9)
        d = {"browsers": {"Velocity": {
            "status": "measured", "version": "0.14",
            "median_activity_launch_ms": 1500, "median_pss_kib": 100000,
            "samples": [{"cefrium_startup_resources": r}]}}}
        report = bench.markdown_report(d)
        self.assertIn("CPU, page faults and storage I/O", report)
        self.assertIn("0.29", report)
        self.assertIn("unavailable", report)

    def test_ci_logs_prove_actual_early_attach_strategy(self):
        self.assertEqual("attach", bench.parse_velocity_strategy(
            "I VelocityCefStartup: CEFStartupStrategy strategy=attach"))
        self.assertEqual("post", bench.parse_velocity_strategy(
            "I VelocityCefStartup: CEFStartupStrategy strategy=post"))
        self.assertIsNone(bench.parse_velocity_strategy("no relevant message"))

    def test_native_surface_ready_metric_is_not_content_first_paint(self):
        d = {'browsers': {'Velocity': {
            'status': 'measured', 'version': '0.14',
            'median_activity_launch_ms': 1200, 'median_pss_kib': 124000,
            'median_cefrium_surface_ready_ms': 1400,
            'samples': [{'cefrium_startup_phases_ms': {'provider_ms': 900, 'total_ms': 500}}]}}}
        output = bench.markdown_report(d)
        self.assertIn('end-to-end Cefrium Surface creation: 1400 ms', output)
        self.assertIn('NOT first paint', output)

    def test_four_browser_execution_order_balanced(self):
        names = ["Velocity", "Chrome", "Brave", "Vivaldi"]
        rounds = bench.rotated_trials(names, 4)
        self.assertEqual(4, len(rounds))
        for name in names:
            self.assertEqual([0, 1, 2, 3],
                sorted(group.index(name) for group in rounds))
        self.assertEqual([["Velocity"]] * 4, bench.rotated_trials(["Velocity"], 4))

    def test_aggregate_preserves_missing_memory(self):
        rec = bench.aggregate([
            {"activity_launch_ms": 110, "pss_kib_after_settle": None},
            {"activity_launch_ms": 140, "pss_kib_after_settle": 10000},
        ])
        self.assertEqual(125, rec["median_activity_launch_ms"])
        self.assertEqual(10000, rec["median_pss_kib"])
        self.assertIsNone(rec["median_cefrium_surface_ready_ms"])

    def test_spread_reports_uncertainty_not_a_false_winner(self):
        samples = [
            {"activity_launch_ms": 400, "pss_kib_after_settle": 8000},
            {"activity_launch_ms": 1200, "pss_kib_after_settle": 8500},
            {"activity_launch_ms": 700, "pss_kib_after_settle": 9000},
            {"activity_launch_ms": 600, "pss_kib_after_settle": 8600},
        ]
        rec = bench.aggregate(samples)
        self.assertEqual(400, rec["min_activity_launch_ms"])
        self.assertEqual(1200, rec["max_activity_launch_ms"])
        self.assertEqual(3, rec["startup_variability_ratio"])
        report = bench.markdown_report({"browsers": {"Velocity": rec}})
        self.assertIn("Startup variability", report)
        self.assertIn("filesystem, ART", report)

    def test_report_preserves_missing_results(self):
        data = {'browsers': {'Velocity': {'status': 'unavailable', 'reason': 'not installed'},
                             'Chrome': {'status': 'measured', 'version': '1',
                                        'median_activity_launch_ms': 140,
                                        'median_pss_kib': None,
                                        'samples': [{}, {}]}}}
        report = bench.markdown_report(data)
        self.assertIn('Velocity | — | — | —', report)
        self.assertIn('Chrome | 1 | 140.0 | —', report)
        self.assertIn('NOT first paint', report)


if __name__ == '__main__':
    unittest.main()
