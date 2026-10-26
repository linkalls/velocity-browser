package dev.velocity.chromium;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Cefrium-native network counterpart for the domain-only part of Velocity
 * Block Lite. No Gecko WebExtension API, native JS bridge or cosmetic script.
 *
 * Once created this object is immutable and safe to query from CEF's
 * background network thread. It never blocks the top-level navigation.
 */
public final class NetworkRules {
    private final Set<String> blocked;
    private final Set<String> allowed;

    private NetworkRules(Set<String> blocked, Set<String> allowed) {
        this.blocked = Collections.unmodifiableSet(blocked);
        this.allowed = Collections.unmodifiableSet(allowed);
    }

    public static NetworkRules parse(String text) {
        Set<String> deny = new HashSet<>();
        Set<String> allow = new HashSet<>();
        if (text == null) return new NetworkRules(deny, allow);

        // The original Gecko Lite parser intentionally supports only
        // domain-level ABP rules. Don't try to reinterpret cosmetic rules,
        // scriptlets, regex filters or advanced AdGuard syntax.
        for (String line : text.split("\\r?\\n")) {
            String rule = line.trim().toLowerCase(Locale.ROOT);
            boolean exception = rule.startsWith("@@||");
            String prefix = exception ? "@@||" : "||";
            if (!rule.startsWith(prefix) || !rule.endsWith("^")) continue;
            String host = rule.substring(prefix.length(), rule.length() - 1);
            if (!validHost(host)) continue;
            (exception ? allow : deny).add(host);
        }
        return new NetworkRules(deny, allow);
    }

    private static boolean validHost(String host) {
        if (host.length() > 253 || host.indexOf('.') < 1) return false;
        for (String label : host.split("\\.", -1)) {
            if (label.length() == 0 || label.length() > 63) return false;
            if (!Character.isLetterOrDigit(label.charAt(0))
                    || !Character.isLetterOrDigit(label.charAt(label.length() - 1))) return false;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'))
                    return false;
            }
        }
        return true;
    }

    public int blockedDomains() { return blocked.size(); }

    public boolean shouldBlock(String url, boolean mainFrame) {
        if (mainFrame || url == null) return false;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https")
                    || scheme.equalsIgnoreCase("http"))) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            // Domain suffix matching must stop at a label boundary.
            boolean denied = false;
            for (int start = 0; start < host.length();) {
                String candidate = host.substring(start);
                if (allowed.contains(candidate)) return false;
                if (blocked.contains(candidate)) denied = true;
                int dot = host.indexOf('.', start);
                if (dot < 0) break;
                start = dot + 1;
            }
            return denied;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
