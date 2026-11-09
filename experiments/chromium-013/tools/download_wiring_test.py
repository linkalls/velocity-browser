"""Source-level guard: the shipped Cefrium browser must have a real download flow.

This test does not claim that an APK completed an on-device transfer.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = ROOT / "app/src/main/java/dev/velocity/chromium/ChromiumTestActivity.java"
CONTROLLER = ROOT / "app/src/main/java/dev/velocity/chromium/CefriumDownloadController.java"


class NativeDownloadWiringTest(unittest.TestCase):
    def test_download_handler_is_bound_to_real_browser(self):
        activity = ACTIVITY.read_text()
        controller = CONTROLLER.read_text()
        self.assertIn("downloads.bind(browser, tab.privateMode)", activity)
        self.assertIn("browser.setDownloadHandler(", controller)
        self.assertIn("onBeforeDownload(", controller)
        self.assertIn("onDownloadUpdated(", controller)

    def test_uses_original_authenticated_transfer_and_pending_mediastore(self):
        controller = CONTROLLER.read_text()
        self.assertIn("callback.allow(job.temporary.getAbsolutePath())", controller)
        self.assertIn("MediaStore.Downloads.IS_PENDING, 1", controller)
        self.assertIn("MediaStore.Downloads.IS_PENDING, 0", controller)
        self.assertNotIn("HttpURLConnection", controller)
        self.assertNotIn("DownloadManager.Request", controller)

    def test_retains_inflight_browser_and_shows_real_download_menu(self):
        activity = ACTIVITY.read_text()
        self.assertIn("downloads.isBusy(closing)", activity)
        self.assertIn("closingAfterDownload.add(closing)", activity)
        self.assertIn('"ダウンロード", null, () ->', activity)


if __name__ == "__main__":
    unittest.main()
