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
