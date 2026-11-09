package dev.velocity.chromium;

import java.net.URI;
import java.util.Locale;

/** Only offer an explicit PDF download/preview for recognizable HTTP(S) paths. */
final class PdfLinkPolicy {
    private PdfLinkPolicy() {}

    static boolean canPreview(String url) {
        if (!DownloadNamePolicy.supportedSource(url)) return false;
        try {
            String path = URI.create(url).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".pdf");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
