package dev.velocity.chromium;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable site exceptions for the *additional* Velocity domain blocker.
 * Hostname-only exceptions are deliberate: an exception cannot silently
 * apply to unrelated suffix domains (example.com.evil.invalid).
 *
 * Keep normal and private exception snapshots separate in the Activity.
 */
public final class SiteBlockPolicy {
    private SiteBlockPolicy() {}

    public static String hostOf(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null ||
                    !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                return null;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.length() <= 253 ? normalized : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static Set<String> normalize(Set<String> candidates) {
        HashSet<String> out = new HashSet<>();
        if (candidates != null) {
            for (String candidate : candidates) {
                if (candidate == null || out.size() >= 100) continue;
                String normalized = hostOf("https://" + candidate);
                if (normalized != null && normalized.equals(candidate.toLowerCase(Locale.ROOT))) {
                    out.add(normalized);
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public static Set<String> toggle(Set<String> current, String host) {
        if (host == null) return current;
        HashSet<String> mutable = new HashSet<>(current);
        if (!mutable.remove(host) && mutable.size() < 100) mutable.add(host);
        return normalize(mutable);
    }

    public static boolean shouldBlock(NetworkRules rules, String requestedUrl,
                                      boolean mainFrame, String topLevelUrl,
                                      Set<String> allowedHosts) {
        String host = hostOf(topLevelUrl);
        if (host != null && allowedHosts.contains(host)) return false;
        return rules.shouldBlock(requestedUrl, mainFrame);
    }
}
