package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;

/** Parses Velocity's intentionally constrained ABP-domain subset. */
public final class NetworkRulesTest {
    private final NetworkRules rules = NetworkRules.parse(
        "! comment\n||ads.example.com^\n||tracker.example.net^\n"
            + "@@||allow.ads.example.com^\n"
            + "bad##.selector\n||evil.example.com^$important\n");

    @Test public void blocksDomainAndSubdomainOnly() {
        assertTrue(rules.shouldBlock("https://ads.example.com/ad.js", false));
        assertTrue(rules.shouldBlock("https://cdn.ads.example.com/a", false));
        assertFalse(rules.shouldBlock("https://notads.example.com/a", false));
        assertFalse(rules.shouldBlock("https://ads.example.com.evil.test/a", false));
    }

    @Test public void exceptionsWinAgainstParentDenial() {
        assertFalse(rules.shouldBlock("https://allow.ads.example.com/a", false));
        assertFalse(rules.shouldBlock("https://child.allow.ads.example.com/a", false));
    }

    @Test public void neverBlocksMainFrameOrNonHttpSchemes() {
        assertFalse(rules.shouldBlock("https://ads.example.com/", true));
        assertFalse(rules.shouldBlock("file:///ads.example.com/a", false));
        assertFalse(rules.shouldBlock("javascript:alert(1)", false));
        assertFalse(rules.shouldBlock(null, false));
    }

    @Test public void ignoresUnsupportedSyntaxAndCountsOnlyValidDomains() {
        assertEquals(2, rules.blockedDomains());
        assertFalse(rules.shouldBlock("https://evil.example.com/a", false));
    }

    @Test public void emptyOrMalformedRulesAreSafe() {
        NetworkRules empty = NetworkRules.parse(
            "||bad domain^\n||domain^\n||-broken.example^\n");
        assertEquals(0, empty.blockedDomains());
        assertFalse(empty.shouldBlock("https://domain.example/a", false));
    }
}
