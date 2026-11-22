package dev.velocity.chromium;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Versioned, flat Velocity bookmark backup. No page content or browser account data. */
final class BookmarkTransferFile {
    private static final String FORMAT = "velocity-bookmarks-v1";

    static final class Parsed {
        final List<BookmarkStore.Bookmark> items;
        final int skipped;

        Parsed(List<BookmarkStore.Bookmark> items, int skipped) {
            this.items = Collections.unmodifiableList(items);
            this.skipped = skipped;
        }
    }

    static final class Encoded {
        final byte[] bytes;
        final int count;
        final int skipped;

        Encoded(byte[] bytes, int count, int skipped) {
            this.bytes = bytes;
            this.count = count;
            this.skipped = skipped;
        }
    }

    private BookmarkTransferFile() {}

    static Parsed decode(String input) throws IOException {
        // Both platform and desktop JSONTokener admit some extensions. Bound depth
        // before parsing, and reject syntax that could conceal nested values from the scan.
        if (input == null || input.length() > BookmarkTransferLimits.MAX_FILE_BYTES) {
            throw new IOException("Bookmark file too large");
        }
        checkStructure(input);
        try {
            JSONTokener tokener = new JSONTokener(input);
            Object raw = tokener.nextValue();
            if (!(raw instanceof JSONObject) || tokener.nextClean() != 0) {
                throw new IOException("This is not a Velocity bookmark backup");
            }
            JSONObject root = (JSONObject) raw;
            if (root.length() != 2 || !FORMAT.equals(root.opt("format"))) {
                throw new IOException("Unknown bookmark backup format");
            }
            JSONArray rows = root.optJSONArray("bookmarks");
            if (rows == null || rows.length() > BookmarkTransferLimits.MAX_IMPORT_ROWS) {
                throw new IOException("Missing or too many bookmark rows");
            }
            List<BookmarkStore.Bookmark> result = new ArrayList<>();
            int skipped = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.optJSONObject(i);
                Object url = item == null ? null : item.opt("url");
                Object title = item == null ? null : item.opt("title");
                if (!(url instanceof String) || !BookmarkTransferLimits.supportsUrl((String) url)) {
                    skipped++;
                    continue;
                }
                String link = (String) url;
                result.add(new BookmarkStore.Bookmark(null, link,
                    BookmarkTransferLimits.safeTitle(title instanceof String ? (String) title : null, link), 0));
            }
            return new Parsed(result, skipped);
        } catch (JSONException error) {
            throw new IOException("Unreadable bookmark backup", error);
        }
    }

    static Encoded encode(List<BookmarkStore.Bookmark> bookmarks) throws IOException {
        JSONArray rows = new JSONArray();
        int skipped = 0;
        try {
            for (BookmarkStore.Bookmark entry : bookmarks) {
                if (!BookmarkTransferLimits.supportsUrl(entry.url)) {
                    skipped++;
                    continue;
                }
                if (rows.length() == BookmarkTransferLimits.MAX_IMPORT_ROWS) {
                    throw new IOException("Too many bookmarks to export to a single backup");
                }
                rows.put(new JSONObject().put("url", entry.url)
                    .put("title", BookmarkTransferLimits.safeTitle(entry.title, entry.url)));
            }
            byte[] bytes = new JSONObject().put("format", FORMAT).put("bookmarks", rows)
                .toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > BookmarkTransferLimits.MAX_FILE_BYTES) {
                throw new IOException("Bookmark backup exceeds the file limit");
            }
            return new Encoded(bytes, rows.length(), skipped);
        } catch (JSONException error) {
            throw new IOException("Could not create bookmark backup", error);
        }
    }

    private static void checkStructure(String input) throws IOException {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c < ' ' && c != '\t' && c != '\n' && c != '\r') {
                throw new IOException("Unexpected control character in bookmark file");
            }
            if (quoted) {
                if (c == '\\') i++;
                else if (c == '"') quoted = false;
            } else if (c == '"') {
                quoted = true;
            } else if (c == '{' || c == '[') {
                if (++depth > 3) throw new IOException("Bookmark data is nested too deeply");
            } else if (c == '}' || c == ']') {
                if (--depth < 0) throw new IOException("Unbalanced bookmark file");
            } else if (c == '\'' || c == '/' || c == '#' || c == '\\') {
                throw new IOException("Unexpected bookmark file syntax");
            }
        }
        if (quoted || depth != 0) throw new IOException("Incomplete bookmark file");
    }
}
