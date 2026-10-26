package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;
import java.net.URI;
import java.util.Locale;

public final class HttpUrlHostTest {
    @Test public void regularUrlsAreNormalized() {
        assertEquals("tracker.example", HttpUrlHost.parse(
            "https://TRACKER.Example:443/res/advert.js?q=hello#fragment"));
        assertEquals("www.example.org", HttpUrlHost.parse(
            "HTTP://WWW.EXAMPLE.ORG/path"));
        assertEquals("example.org", HttpUrlHost.parse("https://example.org?foo"));
        assertEquals("example.org.", HttpUrlHost.parse("https://example.org./"));
    }

    @Test public void unusualAuthoritiesMatchStandardJavaParser() {
        String[] urls = {
            "https://username:pass@tracker.example/ad",
            "https://[2001:db8::1]:443/",
            "https://bad_host.example/res",
            "http://example.org:invalid/",
            "https://example.org%2eexample.invalid/",
            "https://-invalid.example/",
            "https://a..example/",
            "https://[bad/",
            "https://example.org:443/",
            "http://127.0.0.1:8765/a",
            "https:///missing-host",
            "https://%zz.example/",
            "HTTPS://Example.Org:9443/a",
            "https://a.example/very/long/path?many=parameters",
            "https://example.com:9999999999999999/",
            "https://example.com:2147483648/",
            "https://example.com:2147483647/",
            "https://example.com-/foo",
            "http://127.1/",
            "http://127.0.0.256/",
            "http://127.0.0.1/",
            "http://foo.123/",
            "http://8.8.8.8a/",
            "http://123.456.789.1/",
            "http://a.1.2/",
            "http://123.abc/",
            "https://example.com.:443/",
            "https://example.com../",
        };
        for (String url : urls) {
            String expected = null;
            try {
                URI uri = URI.create(url);
                if (uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme())
                        || "http".equalsIgnoreCase(uri.getScheme()))) {
                    expected = uri.getHost().toLowerCase(Locale.ROOT);
                }
            } catch (IllegalArgumentException ignored) {}
            assertEquals("Mismatch for " + url, expected, HttpUrlHost.parse(url));
        }
    }

    @Test public void nonWebSchemesRemainUnblocked() {
        for (String url : new String[] {
            "javascript:alert(1)", "file:///etc/passwd",
            "about:blank", "data:text/html,x", "", "https://", "http://",
            "ftp://tracker.example/ad", "http://tracker.example\\@evil.invalid"}) {
            assertNull("Expected no HTTP(S) host for " + url, HttpUrlHost.parse(url));
        }
        assertNull(HttpUrlHost.parse(null));
    }

    @Test public void realBlockRulesRemainEffective() {
        NetworkRules rules = NetworkRules.parse("||tracker.example^\n@@||ok.tracker.example^\n");
        assertTrue(rules.shouldBlock("https://tracker.example/a.js", false));
        assertTrue(rules.shouldBlock("https://ads.tracker.example/a.js", false));
        assertFalse(rules.shouldBlock("https://ok.tracker.example/a.js", false));
        assertFalse(rules.shouldBlock("https://tracker.example.evil.invalid/a.js", false));
        assertFalse(rules.shouldBlock("https://tracker.example/a.js", true));
    }
}
