import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('startup_regression', Path(__file__).with_name('startup_home_regression.py'))
startup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(startup)


class StartupHierarchyTest(unittest.TestCase):
    def test_home_requires_native_home_and_an_address_field_without_google(self):
        home = '<hierarchy><node text="クイックアクセス"/><node class="android.widget.EditText" text=""/></hierarchy>'
        google = '<hierarchy><node class="android.widget.EditText" text="https://www.google.com/"/></hierarchy>'
        self.assertTrue(startup.home_visible(home))
        self.assertFalse(startup.home_visible(google))
        self.assertTrue(startup.google_visible(google))
        self.assertFalse(startup.home_visible('<hierarchy><node text="クイックアクセス"/></hierarchy>'))
        self.assertFalse(startup.home_visible(home.replace('text=""', 'text="https://www.google.com/"')))
