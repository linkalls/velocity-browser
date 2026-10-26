package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NetworkRulesStreamingTest {
    private static final String SAMPLE =
        "! comment\n||ads.example.com^\n@@||login.ads.example.com^\n"
        + "||metrics.example.net^\n||bad domain^\n"
        + "||last.example.org^\r\n";

    @Test public void streamingMatchesOriginalDomainSemantics() {
        NetworkRules.Builder builder = new NetworkRules.Builder();
        for (String line : SAMPLE.split("\\r?\\n", -1)) builder.acceptLine(line);
        NetworkRules streaming = builder.build();
        NetworkRules direct = NetworkRules.parse(SAMPLE);
        assertEquals(direct.blockedDomains(), streaming.blockedDomains());
        for (String url : new String[] {
            "https://ads.example.com/ad.js", "https://login.ads.example.com/a",
            "https://metrics.example.net/a", "https://last.example.org/ad",
            "https://last.example.org.other.net/ok", "https://bad.domain/ok"
        }) {
            assertEquals(url, direct.shouldBlock(url, false), streaming.shouldBlock(url, false));
        }
    }

    @Test public void snapshotDoesNotMutateAfterBuilderReuse() {
        NetworkRules.Builder builder = new NetworkRules.Builder();
        builder.acceptLine("||one.example^");
        NetworkRules old = builder.build();
        builder.acceptLine("||two.example^");
        NetworkRules next = builder.build();
        assertEquals(1, old.blockedDomains());
        assertEquals(2, next.blockedDomains());
        assertFalse(old.shouldBlock("https://two.example/", false));
        assertTrue(next.shouldBlock("https://two.example/", false));
    }

    @Test public void acceptsAsciiHostAndRejectsMalformedHost() {
        NetworkRules rules = NetworkRules.parse(
            "||a-b.example.com^\n||-bad.example.com^\n||ends-.example.com^\n"
            + "||doubled..example.com^\n||no-trailing-dot.example.com.^\n");
        assertEquals(1, rules.blockedDomains());
        assertTrue(rules.shouldBlock("https://a-b.example.com/img", false));
    }
}
