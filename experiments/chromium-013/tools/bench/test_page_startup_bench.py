import importlib.util
import math
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('page_startup', Path(__file__).with_name('page_startup_bench.py'))
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class PageStartupTest(unittest.TestCase):
    def test_invalid_or_unpainted_reports_do_not_count(self):
        valid = {'fcp_ms': 20, 'ready_ms': 50, 'dom_ms': 10, 'interaction_ok': True}
        self.assertEqual(valid, bench.validate_payload(valid))
        for field, value in [('fcp_ms', 0), ('fcp_ms', math.nan), ('ready_ms', 10),
                             ('dom_ms', None), ('interaction_ok', False), ('fcp_ms', True)]:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                bench.validate_payload({**valid, field: value})

    def test_missing_trials_invalidate_the_comparison(self):
        record = {'variant': 'candidate', 'status': 'measured', 'host_ready_ms': 500, 'fcp_ms': 50}
        self.assertIn('INCOMPLETE', bench.summary([record]))
        self.assertEqual(4, bench.ORDER.count('baseline'))
        self.assertEqual(4, bench.ORDER.count('candidate'))

    def test_fixture_server_rejects_stale_reports_and_accepts_actual_fixture(self):
        import json
        import threading
        import urllib.error
        import urllib.request
        server = bench.FixtureServer()
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with self.assertRaises(urllib.error.HTTPError):
                urllib.request.urlopen('http://127.0.0.1:8765/fixture?trial=stale')
            import time
            record = {'event': threading.Event(), 'started': time.monotonic()}
            server.trials['fresh'] = record
            self.assertEqual(bench.FIXTURE, urllib.request.urlopen('http://127.0.0.1:8765/fixture?trial=fresh').read())
            payload = {'fcp_ms': 20, 'ready_ms': 50, 'dom_ms': 10, 'interaction_ok': True}
            request = urllib.request.Request('http://127.0.0.1:8765/ready?trial=fresh',
                data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
            self.assertEqual(204, urllib.request.urlopen(request).status)
            self.assertTrue(record['event'].is_set())
            self.assertGreater(record['host_ready_ms'], 0)
        finally:
            server.shutdown()
            server.server_close()


class AdbTransportTest(unittest.TestCase):
    def test_exit_zero_broken_pipe_is_retried_for_force_stop(self):
        from types import SimpleNamespace
        from unittest import mock
        bad = SimpleNamespace(returncode=0, stdout=b'cmd: Failure calling service activity: Broken pipe (32)', stderr=b'')
        good = SimpleNamespace(returncode=0, stdout=b'', stderr=b'')
        with mock.patch.object(bench.subprocess, 'run', side_effect=[bad, good]) as run, mock.patch.object(bench.time, 'sleep'):
            self.assertEqual('', bench.adb('shell', 'am', 'force-stop', bench.PACKAGE))
            self.assertEqual(2, run.call_count)

    def test_launch_is_never_retried_as_a_warm_intent(self):
        from types import SimpleNamespace
        from unittest import mock
        bad = SimpleNamespace(returncode=0, stdout=b'cmd: Failure calling service activity: Broken pipe (32)', stderr=b'')
        with mock.patch.object(bench.subprocess, 'run', return_value=bad) as run:
            with self.assertRaises(RuntimeError):
                bench.adb('shell', 'am', 'start', '-W', '-n', bench.ACTIVITY)
            self.assertEqual(1, run.call_count)

    def test_three_variant_order_balances_linear_time_effects(self):
        for variant in bench.VARIANTS:
            positions = [i for i, name in enumerate(bench.ORDER) if name == variant]
            self.assertEqual(4, len(positions))
            self.assertEqual(5.5, sum(positions) / len(positions))


class EmulatorBoundaryTest(unittest.TestCase):
    def test_physical_device_profile_cannot_be_cleared(self):
        from unittest import mock
        for answers in (['physical-phone', '0'], ['emulator-5554', '0'], ['physical-phone', '1']):
            with self.subTest(answers=answers), mock.patch.object(bench, 'adb', side_effect=answers):
                with self.assertRaises(RuntimeError):
                    bench.require_emulator()
        with mock.patch.object(bench, 'adb', side_effect=['emulator-5554', '1']):
            bench.require_emulator()


class PackageReplacementTest(unittest.TestCase):
    def test_foreground_browser_is_stopped_before_apk_replacement(self):
        import tempfile
        from types import SimpleNamespace
        from unittest import mock
        server = SimpleNamespace(trials={})
        commands = []

        def fake_adb(*args, **kwargs):
            commands.append(args)
            if args[:3] == ('shell', 'am', 'start'):
                record = next(iter(server.trials.values()))
                record['page'] = {'fcp_ms': 20, 'dom_ms': 10, 'ready_ms': 30, 'interaction_ok': True}
                record['host_ready_ms'] = 100
                record['event'].set()
                return 'Status: ok\nLaunchState: COLD\nTotalTime: 100'
            if args[:2] == ('shell', 'pidof'):
                return '1234'
            if args[0] == 'logcat':
                return 'CEF surface created and page navigation started'
            return ''

        with tempfile.TemporaryDirectory() as temp, mock.patch.object(bench, 'adb', side_effect=fake_adb), \
                mock.patch.object(bench, 'capture_screen'), mock.patch.object(bench.time, 'sleep'), \
                mock.patch.object(bench.subprocess, 'run', return_value=SimpleNamespace(stdout=b'')):
            record = bench.one_trial(server, 'baseline', Path(temp) / 'base.apk', Path(temp), 1, False)
        self.assertEqual('measured', record['status'])
        self.assertEqual(('shell', 'am', 'force-stop', bench.PACKAGE), commands[0])
        self.assertEqual('install', commands[1][0])
        self.assertGreater(sum(c[:3] == ('shell', 'am', 'force-stop') for c in commands), 1)
