"""Conservative source checks for the separate local PDF-rendering path.

Neither this test nor a successful compilation proves every online PDF can be opened.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main"
ACTIVITY = (MAIN / "java/dev/velocity/chromium/ChromiumTestActivity.java").read_text()
PREVIEW = (MAIN / "java/dev/velocity/chromium/PdfPreviewActivity.java").read_text()
DOWNLOAD = (MAIN / "java/dev/velocity/chromium/CefriumDownloadController.java").read_text()
MANIFEST = (MAIN / "AndroidManifest.xml").read_text()


class PdfWiringTest(unittest.TestCase):
    def test_internal_only_preview_and_actual_pdf_render(self):
        self.assertIn('android:name=".PdfPreviewActivity" android:exported="false"', MANIFEST)
        self.assertIn('!"content".equals(source.getScheme())', PREVIEW)
        self.assertIn("new PdfRenderer(descriptor)", PREVIEW)
        self.assertIn("pdfWorker.execute(", PREVIEW)
        self.assertIn("page.render(", PREVIEW)

    def test_viewer_respects_system_bar_and_cutout_insets(self):
        # The PDF viewer is a separate Activity and must handle Android 15+
        # edge-to-edge independently of ChromiumTestActivity.
        self.assertIn("setDecorFitsSystemWindows(false)", PREVIEW)
        self.assertIn("WindowInsets.Type.systemBars()", PREVIEW)
        self.assertIn("WindowInsets.Type.displayCutout()", PREVIEW)
        self.assertIn("root.setPadding(safe.left, safe.top, safe.right, safe.bottom)", PREVIEW)
        # Never increment existing padding when insets are dispatched again.
        self.assertNotIn("getPaddingTop() + safe.top", PREVIEW)

    def test_pdf_controls_do_not_use_platform_default_buttons(self):
        self.assertIn("new RippleDrawable(", PREVIEW)
        self.assertIn("surface.setCornerRadius(dp(18))", PREVIEW)
        self.assertIn("button.setMinWidth(dp(48))", PREVIEW)
        self.assertIn("button.setMinHeight(dp(48))", PREVIEW)
        self.assertNotIn("new Button(", PREVIEW)

    def test_authenticated_download_reused_not_refetched(self):
        self.assertIn("PdfLinkPolicy.canPreview(pdfTab.url)", ACTIVITY)
        self.assertIn("engine.startDownload(pdfTab.url)", ACTIVITY)
        self.assertIn("PdfPreviewActivity.class", DOWNLOAD)
        self.assertIn("previewWhenReady", DOWNLOAD)
        self.assertNotIn("HttpURLConnection", PREVIEW + DOWNLOAD)


if __name__ == "__main__":
    unittest.main()
