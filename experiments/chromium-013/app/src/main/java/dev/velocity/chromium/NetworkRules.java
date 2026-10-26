package dev.velocity.chromium;

import java.net.URI;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
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

    /**
     * Streaming parser. Keep this builder confined to the startup thread;
     * build() returns a defensively copied immutable snapshot for CEF workers.
     */
    public static final class Builder {
        private final HashSet<String> deny = new HashSet<>(4096);
        private final HashSet<String> allow = new HashSet<>();

        public void acceptLine(String line) {
            if (line == null) return;
            String rule = line.trim().toLowerCase(Locale.ROOT);
            boolean exception = rule.startsWith("@@||");
            int start = exception ? 4 : 2;
            if ((!exception && !rule.startsWith("||"))
                    || rule.length() <= start + 1 || rule.charAt(rule.length() - 1) != '^') {
                return;
            }
            String host = rule.substring(start, rule.length() - 1);
            if (!validHost(host)) return;
            (exception ? allow : deny).add(host);
        }

        public NetworkRules build() {
            return new NetworkRules(new HashSet<>(deny), new HashSet<>(allow));
        }
    }

    public static NetworkRules parse(String text) {
        Builder builder = new Builder();
        if (text == null) return builder.build();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) builder.acceptLine(line);
        } catch (IOException unexpected) {
            // StringReader does no I/O: preserve fail-open behavior.
            return new Builder().build();
        }
        return builder.build();
    }

    private static boolean validHost(String host) {
        if (host.length() < 3 || host.length() > 253) return false;
        int labelLength = 0;
        boolean hasDot = false;
        boolean lastAlphanumeric = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                if (labelLength == 0 || !lastAlphanumeric) return false;
                labelLength = 0;
                hasDot = true;
                continue;
            }
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!alnum && c != '-') return false;
            if (labelLength == 0 && !alnum) return false;
            if (++labelLength > 63) return false;
            lastAlphanumeric = alnum;
        }
        return hasDot && labelLength > 0 && lastAlphanumeric;
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
