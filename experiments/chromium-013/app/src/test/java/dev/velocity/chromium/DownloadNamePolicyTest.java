package dev.velocity.chromium;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure-Java security tests do not require Chromium or Android. */
public final class DownloadNamePolicyTest {
    @Test public void sanitizesTraversalControlAndBidiOverrides() {
        assertEquals("report.pdf", DownloadNamePolicy.filename("../../report.pdf"));
        assertEquals("report.pdf", DownloadNamePolicy.filename("C:\\fake\\report.pdf"));
        assertEquals("evil.pdf", DownloadNamePolicy.filename("\u202eevil.pdf"));
        assertEquals("download.bin", DownloadNamePolicy.filename(".."));
        assertEquals("download.bin", DownloadNamePolicy.filename(null));
        assertEquals("a.txt", DownloadNamePolicy.filename(".a.txt"));
        assertTrue(DownloadNamePolicy.filename("x".repeat(999)).length() <= 120);
    }
    @Test public void validatesMimeAndSourceUrl() {
        assertEquals("application/pdf", DownloadNamePolicy.mimeType("Application/PDF; charset=utf-8"));
        assertEquals("application/octet-stream", DownloadNamePolicy.mimeType("a/b\nX:evil"));
        assertEquals("application/octet-stream", DownloadNamePolicy.mimeType(null));
        assertTrue(DownloadNamePolicy.supportedSource("https://example.com/paper.pdf"));
        assertFalse(DownloadNamePolicy.supportedSource("file:///etc/passwd"));
        assertFalse(DownloadNamePolicy.supportedSource("javascript:alert(1)"));
        assertFalse(DownloadNamePolicy.supportedSource("https://"));
    }
}
