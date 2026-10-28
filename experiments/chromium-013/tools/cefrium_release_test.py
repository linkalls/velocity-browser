"""Guard the Cefrium SDK/Preview/benchmark version and safety contract.

These are source-level regression checks. Android CI must separately compile
with JDK 25, inspect the merged Manifest and exercise real renderers.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ENGINE = ROOT / "experiments" / "chromium-013"
APP = ENGINE / "app" / "src" / "main" / "java" / "dev" / "velocity" / "chromium"
VERSION = "0.16.0"


class CefriumReleaseTest(unittest.TestCase):
    def test_plugin_and_sdk_versions_match(self):
        gradle = (ENGINE / "build.gradle.kts").read_text()
        app = (ENGINE / "app" / "build.gradle.kts").read_text()
        plugin = re.search(r'id\("com\.cefrium"\) version "([^"]+)"', gradle)
        sdk = re.search(r'implementation\("com\.cefrium:cefrium-sdk:([^"]+)"\)', app)
        self.assertIsNotNone(plugin)
        self.assertIsNotNone(sdk)
        self.assertEqual(VERSION, plugin.group(1))
        self.assertEqual(VERSION, sdk.group(1))

    def test_preview_version_and_ui_are_not_stale(self):
        preview = (ROOT / ".github" / "workflows" /
                   "chromium-013-signed-preview.yml").read_text()
        activity = (APP / "ChromiumTestActivity.java").read_text()
        self.assertIn("-PvelocityVersionName=0.16.0-preview.", preview)
        self.assertIn("Cefrium 0.16.0", activity)
        self.assertNotIn("Cefrium 0.14.0", activity)

    def test_sdk_prefetch_enabled_by_default_and_protects_signed_preview(self):
        gradle = (ENGINE / "app" / "build.gradle.kts").read_text()
        application = (APP / "ChromiumDiagnosticApplication.java").read_text()
        self.assertIn('providers.gradleProperty("velocitySdkPrefetch")', gradle)
        self.assertIn('.orElse("true").get().toBooleanStrict()', gradle)
        self.assertIn('!previewCanSign', gradle)
        self.assertIn('require(velocityNativePrefetchMiB == 0 || !velocitySdkPrefetch)', gradle)
        self.assertIn('Cefrium.setLibraryPrefetchEnabled(false)', application)
        self.assertIn('R.bool.velocity_sdk_prefetch_enabled', application)

    def test_old_benchmark_is_explicitly_sdk_prefetch_off(self):
        workflow = (ROOT / ".github" / "workflows" /
                    "chromium-native-prefetch-experiment.yml").read_text()
        self.assertIn('-PvelocitySdkPrefetch=false', workflow)
        self.assertIn('-PvelocityNativePrefetchMiB="$amount"', workflow)

    def test_youtube_rules_are_explicit_and_netlog_is_not_automatic(self):
        activity = (APP / "ChromiumTestActivity.java").read_text()
        application = (APP / "ChromiumDiagnosticApplication.java").read_text()
        self.assertIn('Cefrium.loadYouTubeAdRules()', activity)
        self.assertNotIn('setNetLogFile(', activity + application)


if __name__ == "__main__":
    unittest.main()
