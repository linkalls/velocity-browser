package dev.velocity.chromium;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PdfLinkPolicyTest {
    @Test public void offersOnlyExplicitHttpPdfUrls() {
        assertTrue(PdfLinkPolicy.canPreview("https://example.org/paper.pdf"));
        assertTrue(PdfLinkPolicy.canPreview("https://example.org/PAPER.PDF?download=1"));
        assertFalse(PdfLinkPolicy.canPreview("https://example.org/download?id=1"));
        assertFalse(PdfLinkPolicy.canPreview("file:///sdcard/paper.pdf"));
        assertFalse(PdfLinkPolicy.canPreview("https://"));
        assertFalse(PdfLinkPolicy.canPreview(null));
    }
}
