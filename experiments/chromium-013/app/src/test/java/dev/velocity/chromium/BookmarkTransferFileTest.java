package dev.velocity.chromium;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class BookmarkTransferFileTest {
    @Test public void roundTripExportsOnlyBookmarksAndDoesNotOverwrite() throws IOException {
        BookmarkStore original = new BookmarkStore();
        original.add("https://example.org/A", "Archive");
        original.add("https://example.jp/表", "歴史");
        original.add("file:///local/page", "Not transferable");
        BookmarkTransferFile.Encoded encoded = BookmarkTransferFile.encode(original.all());
        assertEquals(2, encoded.count);
        assertEquals(1, encoded.skipped);
        BookmarkTransferFile.Parsed parsed = BookmarkTransferFile.decode(
            new String(encoded.bytes, StandardCharsets.UTF_8));
        BookmarkStore destination = new BookmarkStore();
        BookmarkStore.Bookmark keep = destination.add("https://example.org/A", "Keep this title");
        assertEquals(1, destination.mergeTransferred(parsed.items));
        assertEquals(0, destination.mergeTransferred(parsed.items));
        assertEquals("歴史", destination.all().get(0).title);
        assertSame(keep, destination.all().get(1));
    }

    @Test public void skipsUnsupportedRowsAndDeduplicatesImportedUrls() throws IOException {
        String input = "{\"format\":\"velocity-bookmarks-v1\",\"bookmarks\":["
            + "{\"url\":\"javascript:alert(1)\"},"
            + "{\"url\":\"https://example.org/A\",\"title\":\"First\"},"
            + "{\"url\":\"HTTPS://example.org/A\",\"title\":\"Later\"}]}";
        BookmarkTransferFile.Parsed parsed = BookmarkTransferFile.decode(input);
        BookmarkStore store = new BookmarkStore();
        assertEquals(1, parsed.skipped);
        assertEquals(1, store.mergeTransferred(parsed.items));
        assertEquals("First", store.all().get(0).title);
    }

    @Test(expected = IOException.class) public void refusesUnknownFormat() throws IOException {
        BookmarkTransferFile.decode("{\"format\":\"velocity-bookmarks-v2\",\"bookmarks\":[]}");
    }

    @Test(expected = IOException.class) public void rejectsMalformedOrTrailingData() throws IOException {
        BookmarkTransferFile.decode("{\"format\":\"velocity-bookmarks-v1\",\"bookmarks\":[]} true");
    }

    @Test(expected = IOException.class) public void boundsNestedInput() throws IOException {
        BookmarkTransferFile.decode("{\"format\":\"velocity-bookmarks-v1\",\"bookmarks\":[{\"url\":"
            + "[".repeat(10_000) + "1" + "]".repeat(10_000) + "}]}");
    }
}
