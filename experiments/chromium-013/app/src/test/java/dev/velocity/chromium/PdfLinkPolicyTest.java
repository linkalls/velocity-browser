package dev.velocity.chromium;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PdfLinkPolicyTest {
    @Test public void offersExplicitWebPdfUrlsIncludingBlob() {
        assertTrue(PdfLinkPolicy.canPreview("https://example.org/paper.pdf"));
        assertTrue(PdfLinkPolicy.canPreview("https://example.org/PAPER.PDF?download=1"));
        assertFalse(PdfLinkPolicy.canPreview("https://example.org/download?id=1"));
        assertTrue(PdfLinkPolicy.canPreview("blob:https://example.org/generated.pdf"));
        assertFalse(PdfLinkPolicy.canPreview("file:///sdcard/paper.pdf"));
        assertFalse(PdfLinkPolicy.canPreview("javascript:alert(1)//paper.pdf"));
        assertFalse(PdfLinkPolicy.canPreview("https://"));
        assertFalse(PdfLinkPolicy.canPreview(null));
    }
}
