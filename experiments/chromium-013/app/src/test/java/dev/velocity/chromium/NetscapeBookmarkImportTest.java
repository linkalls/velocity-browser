package dev.velocity.chromium;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import org.junit.Test;

public class NetscapeBookmarkImportTest {
    private static final String HEADER = "<!DOCTYPE NETSCAPE-Bookmark-file-1>\n";

    @Test public void importsPlainLabelsAndUrlsWithoutFaviconOrFolder() throws IOException {
        String text = "\uFEFF" + HEADER + "<DL><p>\n <DT><H3>Folder</H3>\n"
            + " <DT><A HREF=\"https://example.org/?a=1&amp;b=2\" ICON=\"data:image/png;base64,ignored\">"
            + "日本史 &amp; &#x1f4d8;</A>\n"
            + " <dt><a href='https://example.test/二'>Example</a>\n</DL><p>";
        assertTrue(NetscapeBookmarkImport.looksLikeExport(text));
        BookmarkTransferFile.Parsed parsed = NetscapeBookmarkImport.decode(text);
        assertEquals(2, parsed.items.size());
        assertEquals("https://example.org/?a=1&b=2", parsed.items.get(0).url);
        assertEquals("日本史 & 📘", parsed.items.get(0).title);
    }

    @Test public void ignoresCommentsAndSkipsUnsupportedUrlsAndMalformedRows() throws IOException {
        String text = HEADER + "<!--\n <DT><A HREF=\"https://example.org\">Comment</A>\n-->\n"
            + "<DT><A HREF=\"javascript:void(0)\">Unsupported</A>\n"
            + "<DT><A HREF=\"https://example.org\" ADD_DATE=\"broken>Malformed\n";
        BookmarkTransferFile.Parsed parsed = NetscapeBookmarkImport.decode(text);
        assertEquals(0, parsed.items.size());
        assertEquals(2, parsed.skipped);
    }

    @Test(expected = IOException.class) public void rejectsOrdinaryHtml() throws IOException {
        NetscapeBookmarkImport.decode("<html><a href=\"https://example.org\">Not an export</a></html>");
    }

    @Test(expected = IOException.class) public void rejectsScriptSectionInsteadOfRenderingIt() throws IOException {
        NetscapeBookmarkImport.decode(HEADER + "<script>not a bookmark export</script>");
    }

    @Test(expected = IOException.class) public void boundsEntryCount() throws IOException {
        NetscapeBookmarkImport.decode(HEADER + "<DT><A HREF=\"https://example.org\">Test</A>\n"
            .repeat(BookmarkTransferLimits.MAX_IMPORT_ROWS + 1));
    }
}
