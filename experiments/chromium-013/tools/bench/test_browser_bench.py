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

    def test_memory_parses_explicit_totals(self):
        self.assertEqual(123456, bench.parse_pss('TOTAL PSS: 123,456 TOTAL RSS: 234567'))
        self.assertEqual(98765, bench.parse_pss(' TOTAL   98765    0    0\n'))
        self.assertIsNone(bench.parse_pss('Java Heap: 64 MB\n'))

    def test_cefrium_phase_log_is_numeric_and_optional(self):
        result = bench.parse_velocity_timing(
            'I/VelocityCefStartup: CEFStartupTiming ui_ms=20 pool_ms=15 '
            'rules_ms=5 create_ms=1200 total_ms=1240')
        self.assertEqual(result['create_ms'], 1200)
        self.assertIsNone(bench.parse_velocity_timing('unrelated activity log'))

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
