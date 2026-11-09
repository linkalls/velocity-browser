package dev.velocity.chromium;

import java.text.Normalizer;
import java.util.Locale;
import java.net.URI;

/** Small, Android-independent checks for user-visible download metadata. */
final class DownloadNamePolicy {
    private DownloadNamePolicy() {}

    static String filename(String suggested) {
        if (suggested == null) return "download.bin";
        String name = suggested.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < name.length() && safe.length() < 120; i++) {
            char c = name.charAt(i);
            // Exclude both path controls and bidi overrides that can disguise extensions.
            if (Character.isISOControl(c) || (c >= 0x202a && c <= 0x202e)
                    || (c >= 0x2066 && c <= 0x2069)
                    || "<>:\"|?*".indexOf(c) >= 0) continue;
            safe.append(c);
        }
        name = safe.toString().trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "download.bin";
        while (name.startsWith(".")) name = name.substring(1);
        return name.isEmpty() ? "download.bin" : name;
    }

    static String mimeType(String declared) {
        if (declared == null) return "application/octet-stream";
        String raw = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return raw.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
            ? raw : "application/octet-stream";
    }

    static boolean supportedSource(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("https")
                || scheme.equalsIgnoreCase("http")) && uri.getHost() != null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
