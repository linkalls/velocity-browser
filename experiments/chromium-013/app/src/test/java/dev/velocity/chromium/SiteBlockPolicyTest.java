package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Set;

public final class SiteBlockPolicyTest {
    private final NetworkRules rules = NetworkRules.parse("||tracker.example^\n");

    @Test public void siteExemptionDoesNotExcludeOtherSites() {
        Set<String> allow = Set.of("news.example");
        assertFalse(SiteBlockPolicy.shouldBlock(rules, "https://tracker.example/js", false,
            "https://news.example/a", allow));
        assertTrue(SiteBlockPolicy.shouldBlock(rules, "https://tracker.example/js", false,
            "https://other.example/a", allow));
        assertTrue(SiteBlockPolicy.shouldBlock(rules, "https://tracker.example/js", false,
            "https://news.example.evil.invalid/a", allow));
    }

    @Test public void invalidUrlsDoNotAcquireExemptions() {
        assertNull(SiteBlockPolicy.hostOf("javascript:alert(1)"));
        assertNull(SiteBlockPolicy.hostOf("file:///etc/passwd"));
        assertNull(SiteBlockPolicy.hostOf(null));
    }

    @Test public void exceptionTogglingIsImmutable() {
        Set<String> first = Set.of();
        Set<String> second = SiteBlockPolicy.toggle(first, "news.example");
        assertTrue(second.contains("news.example"));
        assertFalse(first.contains("news.example"));
        assertFalse(SiteBlockPolicy.toggle(second, "news.example").contains("news.example"));
    }
}
