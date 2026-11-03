import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).with_name("perfetto_startup.py")
spec = importlib.util.spec_from_file_location("perfetto_startup", path)
perfetto = importlib.util.module_from_spec(spec)
spec.loader.exec_module(perfetto)


class PerfettoStartupTest(unittest.TestCase):
    def test_command_is_bounded_and_scoped_to_our_app(self):
        cmd = perfetto.perfetto_command()
        self.assertIn("-D", cmd)
        self.assertIn("-t", cmd)
        self.assertEqual("14s", cmd[cmd.index("-t") + 1])
        self.assertEqual(perfetto.APP, cmd[cmd.index("-a") + 1])
        self.assertIn("sched", cmd)
        self.assertIn("freq", cmd)
        self.assertNotIn("http", " ".join(cmd))

    def test_trace_is_not_redirected_to_public_dir(self):
        self.assertTrue(perfetto.TRACE_PATH.startswith("/data/misc/perfetto-traces/"))


if __name__ == "__main__":
    unittest.main()
