package dev.velocity.chromium;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class BookmarkTransferLimitsTest {
    @Test public void onlyAcceptsHierarchicalHttpAndHttpsWithoutUserInfo() {
        assertTrue(BookmarkTransferLimits.supportsUrl("https://example.org/A?q=%20#section"));
        assertTrue(BookmarkTransferLimits.supportsUrl("http://[::1]:8080/"));
        assertTrue(BookmarkTransferLimits.supportsUrl("https://example.jp/日本史"));
        assertFalse(BookmarkTransferLimits.supportsUrl("javascript:alert(1)"));
        assertFalse(BookmarkTransferLimits.supportsUrl("file:///tmp/test.html"));
        assertFalse(BookmarkTransferLimits.supportsUrl("data:text/html,test"));
        assertFalse(BookmarkTransferLimits.supportsUrl("//example.org"));
        assertFalse(BookmarkTransferLimits.supportsUrl("https:opaque"));
        assertFalse(BookmarkTransferLimits.supportsUrl("https://user:pass@example.org"));
        assertFalse(BookmarkTransferLimits.supportsUrl("https://example.org:65536"));
    }

    @Test public void acceptsJapaneseUtf8() throws IOException {
        assertEquals("日本史", BookmarkTransferLimits.readUtf8(new ByteArrayInputStream(
            "日本史".getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void acceptsExactFileLimit() throws IOException {
        byte[] bytes = new byte[BookmarkTransferLimits.MAX_FILE_BYTES];
        assertEquals(bytes.length, BookmarkTransferLimits.readUtf8(new ByteArrayInputStream(bytes)).length());
    }

    @Test(expected = IOException.class) public void rejectsOneByteOverFileLimit() throws IOException {
        BookmarkTransferLimits.readUtf8(new ByteArrayInputStream(
            new byte[BookmarkTransferLimits.MAX_FILE_BYTES + 1]));
    }

    @Test(expected = IOException.class) public void rejectsMalformedUtf8() throws IOException {
        BookmarkTransferLimits.readUtf8(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x41}));
    }

    @Test public void avoidsControlCharactersInTransferredTitles() {
        assertEquals("日本史", BookmarkTransferLimits.safeTitle("日\u202E本史\n", "fallback"));
        assertEquals("fallback", BookmarkTransferLimits.safeTitle("\n\t", "fallback"));
    }
}
