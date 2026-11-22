package dev.velocity.chromium;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Constraints for explicit, offline bookmark file transfer, not for ordinary navigation. */
final class BookmarkTransferLimits {
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final int MAX_IMPORT_ROWS = 1_000;
    static final int MAX_URL_LENGTH = 8_192;
    static final int MAX_TITLE_LENGTH = 512;

    private BookmarkTransferLimits() {}

    static String readUtf8(InputStream in) throws IOException {
        if (in == null) throw new IOException("Could not open the selected document");
        byte[] buffer = new byte[8_192];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = 0;
        while (true) {
            int count = in.read(buffer, 0, Math.min(buffer.length, MAX_FILE_BYTES - total + 1));
            if (count < 0) break;
            if (count == 0) {
                int single = in.read();
                if (single < 0) break;
                if (++total > MAX_FILE_BYTES) throw new IOException("Bookmark file too large");
                out.write(single);
                continue;
            }
            total += count;
            if (total > MAX_FILE_BYTES) throw new IOException("Bookmark file too large");
            out.write(buffer, 0, count);
        }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(out.toByteArray())).toString();
    }

    static boolean supportsUrl(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '\\') return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && uri.getHost() != null && uri.getRawUserInfo() == null
                && uri.getPort() <= 65_535;
        } catch (URISyntaxException failure) {
            return false;
        }
    }

    static String safeTitle(String title, String fallback) {
        if (title == null) return fallback;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < title.length() && result.length() < MAX_TITLE_LENGTH;) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);
            // Avoid control codes and bidi overrides in imported single-line UI labels.
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) continue;
            if (Character.getType(cp) == Character.SURROGATE) continue;
            if (result.length() + Character.charCount(cp) > MAX_TITLE_LENGTH) break;
            result.appendCodePoint(cp);
        }
        String text = result.toString().trim();
        return text.isEmpty() ? fallback : text;
    }
}
