package dev.velocity.wpe;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Conservative prototype address parser; never load file/data/javascript URLs. */
public final class NavigationPolicy {
    private NavigationPolicy() {}

    public static String parse(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (!value.contains("://") && !value.contains(" ") && value.contains("."))
            value = "https://" + value;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                URI uri = new URI(value);
                String host = uri.getHost();
                if (host == null || host.isBlank() || uri.getUserInfo() != null) return null;
                String scheme = uri.getScheme();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                    return null;
                return uri.toASCIIString();
            } catch (Exception ignored) { return null; }
        }
        // Never pass arbitrary URI schemes through the navigation API.
        if (value.contains("://") || value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*"))
            return null;
        return "https://www.google.com/search?q=" +
            URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
