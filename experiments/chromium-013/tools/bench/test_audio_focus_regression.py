import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('audio_regression', Path(__file__).with_name('audio_focus_regression.py'))
regression = importlib.util.module_from_spec(spec)
spec.loader.exec_module(regression)


class AudioFocusLogTest(unittest.TestCase):
    def test_only_player_loss_events_are_counted(self):
        log = '''I VelocityAudioProbe: player ready focus=granted play_state=3
I VelocityAudioProbe: player focus_change=1
I VelocityAudioProbe: ducker focus_change=-3
I VelocityAudioProbe: player focus_change=-3
I VelocityAudioProbe: player focus_change=-2
I VelocityAudioProbe: player focus_change=-1'''
        self.assertEqual([-3, -2, -1], regression.audio_losses(log))
        self.assertEqual([], regression.audio_losses('player focus_change=1'))
