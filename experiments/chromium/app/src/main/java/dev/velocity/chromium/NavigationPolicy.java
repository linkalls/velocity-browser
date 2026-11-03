package dev.velocity.chromium;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Only HTTP(S) is accepted via the experiment's editable address field. */
public final class NavigationPolicy {
    private NavigationPolicy() {}

    public static String parse(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (value.isEmpty() || value.length() > 2048) return null;
        if (!value.contains("://") && !value.contains(" ") && value.contains(".")
                && !value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            value = "https://" + value;
        }
        if (value.regionMatches(true, 0, "https://", 0, 8) ||
            value.regionMatches(true, 0, "http://", 0, 7)) {
            try {
                URI uri = new URI(value);
                if (uri.getHost() == null || uri.getHost().isBlank() ||
                    uri.getUserInfo() != null ||
                    (!"https".equalsIgnoreCase(uri.getScheme()) &&
                     !"http".equalsIgnoreCase(uri.getScheme()))) return null;
                return uri.toASCIIString();
            } catch (Exception ignored) {
                return null;
            }
        }
        if (value.contains("://") || value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*"))
            return null;
        return "https://www.google.com/search?q=" +
            URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
