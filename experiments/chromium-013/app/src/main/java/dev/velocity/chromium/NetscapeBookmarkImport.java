package dev.velocity.chromium;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Locale;

/** Reads the line-based export format. No HTML engine, preview, network, or local URL loading. */
final class NetscapeBookmarkImport {
    private static final String MARKER = "<!DOCTYPE NETSCAPE-Bookmark-file-1>";
    private static final String PREFIX = "<DT><A HREF=";

    private NetscapeBookmarkImport() {}

    static String trimHeader(String s) {
        return (s.startsWith("\uFEFF") ? s.substring(1) : s).trim();
    }

    static boolean looksLikeExport(String input) {
        String s = trimHeader(input);
        return s.regionMatches(true, 0, MARKER, 0, MARKER.length());
    }

    static BookmarkTransferFile.Parsed decode(String input) throws IOException {
        if (input.length() > BookmarkTransferLimits.MAX_FILE_BYTES) {
            throw new IOException("Bookmark file too large");
        }
        String source = trimHeader(input);
        var result = new ArrayList<BookmarkStore.Bookmark>();
        int rows = 0;
        int skipped = 0;
        boolean comment = false;
        try (BufferedReader reader = new BufferedReader(new StringReader(source))) {
            if (!MARKER.equalsIgnoreCase(reader.readLine())) {
                throw new IOException("Not a Netscape bookmark export");
            }
            for (String line; (line = reader.readLine()) != null;) {
                line = line.trim();
                if (comment || line.startsWith("<!--")) {
                    // A Netscape export may include the header comment. Never read rows inside it.
                    comment = !line.contains("-->");
                    continue;
                }
                String lowered = line.toLowerCase(Locale.ROOT);
                if (lowered.startsWith("<script") || lowered.startsWith("<style")
                    || lowered.startsWith("<iframe") || lowered.startsWith("<object")
                    || lowered.startsWith("<textarea") || lowered.startsWith("<template")) {
                    throw new IOException("Page content is not a supported bookmark export");
                }
                if (!lowered.startsWith("<dt><a")) continue;
                if (++rows > BookmarkTransferLimits.MAX_IMPORT_ROWS) {
                    throw new IOException("Bookmark file has too many rows");
                }
                if (!line.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
                    skipped++;
                    continue;
                }
                int start = PREFIX.length();
                if (line.length() <= start || (line.charAt(start) != '"' && line.charAt(start) != '\'')) {
                    skipped++;
                    continue;
                }
                int end = line.indexOf(line.charAt(start), start + 1);
                int tagEnd = end < 0 ? -1 : unquotedTagEnd(line, end + 1);
                if (tagEnd < 0 || !lowered.endsWith("</a>")) {
                    skipped++;
                    continue;
                }
                String url = unescape(line.substring(start + 1, end));
                int titleEnd = line.length() - 4;
                if (tagEnd >= titleEnd || !BookmarkTransferLimits.supportsUrl(url)) {
                    skipped++;
                    continue;
                }
                String rawTitle = line.substring(tagEnd + 1, titleEnd);
                if (rawTitle.contains("<")) {
                    skipped++;
                    continue;
                }
                result.add(new BookmarkStore.Bookmark(null, url,
                    BookmarkTransferLimits.safeTitle(unescape(rawTitle), url), 0));
            }
        }
        if (comment) throw new IOException("Incomplete bookmark export");
        return new BookmarkTransferFile.Parsed(result, skipped);
    }

    private static int unquotedTagEnd(String line, int start) {
        char quote = 0;
        for (int i = start; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '>') {
                return i;
            } else if (ch == '<') {
                return -1;
            }
        }
        return -1;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '&') {
                int end = i + 1;
                while (end < s.length() && end - i <= 11 && s.charAt(end) != ';'
                    && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '#')) end++;
                if (end < s.length() && s.charAt(end) == ';' && end - i <= 11) {
                    int decoded = entity(s.substring(i + 1, end));
                    if (decoded >= 0) {
                        out.appendCodePoint(decoded);
                        i = end;
                        continue;
                    }
                }
            }
            out.append(s.charAt(i));
        }
        return out.toString();
    }

    private static int entity(String s) {
        switch (s) {
            case "amp": return '&';
            case "lt": return '<';
            case "gt": return '>';
            case "quot": return '"';
            case "apos": return '\'';
            case "nbsp": return ' ';
            default:
                if (!s.startsWith("#")) return -1;
                boolean hex = s.startsWith("#x") || s.startsWith("#X");
                try {
                    int cp = Integer.parseInt(s.substring(hex ? 2 : 1), hex ? 16 : 10);
                    return cp > 0 && Character.isValidCodePoint(cp) && !(cp >= 0xd800 && cp <= 0xdfff)
                        && !Character.isISOControl(cp) ? cp : -1;
                } catch (NumberFormatException e) {
                    return -1;
                }
        }
    }
}
