package dev.velocity.chromium;

import java.net.URI;
import java.util.Locale;

/**
 * Controls only the optional "preview this PDF" menu for a tab URL.
 * This must never be used to validate Cefrium's onBeforeDownload events.
 * The browser engine, not URL metadata, owns actual download transfers.
 */
final class PdfLinkPolicy {
    private PdfLinkPolicy() {}

    static boolean canPreview(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            // This menu calls startDownload(url) explicitly. Restrict that
            // action to normal navigations and browser-owned blob URLs;
            // never hand javascript:, intent:, file: etc. to this action.
            if (!normalizedScheme.equals("http")
                    && !normalizedScheme.equals("https")
                    && !normalizedScheme.equals("blob")) return false;
            String path = uri.isOpaque() ? uri.getSchemeSpecificPart() : uri.getPath();
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".pdf");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
