package dev.velocity.chromium;

import java.net.URI;
import java.util.Locale;

/**
 * Allocation-light HTTP(S) host parser used only on CEF's hot resource path.
 *
 * Common ASCII DNS URL syntax avoids URI object allocation. Less common
 * authorities (IPv6, credentials, Unicode/escaped hosts, backslashes)
 * deliberately fall back to java.net.URI to preserve conservative behavior.
 */
public final class HttpUrlHost {
    private HttpUrlHost() {}

    public static String parse(String url) {
        if (url == null) return null;
        final int start;
        if (url.regionMatches(true, 0, "https://", 0, 8)) start = 8;
        else if (url.regionMatches(true, 0, "http://", 0, 7)) start = 7;
        else return null;

        // Scan just the authority, not an entire long resource URL.
        int end = start;
        while (end < url.length()) {
            char ch = url.charAt(end);
            if (ch == '/' || ch == '?' || ch == '#') break;
            // Absolute URI authority needs special parsing for these cases.
            if (ch == '@' || ch == '[' || ch == ']' || ch == '\\'
                    || ch == '%' || ch <= ' ') return standardParse(url);
            end++;
        }
        if (end == start) return null;
        int hostEnd = end;
        for (int i = start; i < end; i++) {
            if (url.charAt(i) == ':') {
                hostEnd = i;
                // Malformed ports need standard URI validation.
                if (i + 1 == end) return standardParse(url);
                long port = 0L;
                for (int j = i + 1; j < end; j++) {
                    char digit = url.charAt(j);
                    if (digit < '0' || digit > '9') return standardParse(url);
                    port = port * 10L + (digit - '0');
                    // java.net.URI.getHost() rejects a syntactically numeric
                    // port if its value overflows a signed 32-bit int.
                    if (port > Integer.MAX_VALUE) return standardParse(url);
                }
                break;
            }
        }
        if (hostEnd == start) return null;
        // URI.getHost() rejects a terminal all-numeric or digit-prefixed
        // TLD unless the authority is a valid numeric IP literal. Delegate
        // these uncommon hosts (including IPv4) to the standard parser.
        int finalLabel = start;
        for (int i = start; i < hostEnd; i++) {
            if (url.charAt(i) == '.') finalLabel = i + 1;
        }
        if (finalLabel < hostEnd && Character.isDigit(url.charAt(finalLabel))) {
            return standardParse(url);
        }
        int length = hostEnd - start;
        if (length > 253) return standardParse(url);

        boolean lastDot = false;
        int labelLength = 0;
        for (int i = start; i < hostEnd; i++) {
            char c = url.charAt(i);
            if (c == '.') {
                if (labelLength == 0 || url.charAt(i - 1) == '-') return standardParse(url);
                lastDot = true;
                labelLength = 0;
                continue;
            }
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
            if (!(alnum || c == '-') || (labelLength == 0 && c == '-')) {
                return standardParse(url);
            }
            lastDot = false;
            if (++labelLength > 63) return standardParse(url);
        }
        // A trailing dot is valid DNS notation and URI.getHost retains it.
        if (labelLength == 0 && !lastDot) return standardParse(url);
        if (url.charAt(hostEnd - 1) == '-') return standardParse(url);
        return url.substring(start, hostEnd).toLowerCase(Locale.ROOT);
    }

    private static String standardParse(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || !("http".equalsIgnoreCase(scheme)
                        || "https".equalsIgnoreCase(scheme))) return null;
            return host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
