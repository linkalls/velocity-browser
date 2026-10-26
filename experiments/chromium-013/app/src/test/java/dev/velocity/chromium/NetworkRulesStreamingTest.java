package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.IOException;

public final class NetworkRulesStreamingTest {
    private static final String SAMPLE =
        "! comment\n||ads.example.com^\n@@||login.ads.example.com^\n"
        + "||metrics.example.net^\n||bad domain^\n"
        + "||last.example.org^\r\n";

    @Test public void bundledPeterLoweSnapshotPreservesCoverage() throws IOException {
        Path assets = Path.of("src/main/assets/velocity-block");
        if (!Files.isDirectory(assets)) {
            // Gradle's JVM Test task normally runs with app/ as its cwd.
            // Resolve the same repository root when launched from the root.
            assets = Path.of("app/src/main/assets/velocity-block");
        }
        assertTrue("Bundled blocklists must be available in CI", Files.isDirectory(assets));
        NetworkRules.Builder builder = new NetworkRules.Builder();
        for (String filename : new String[] {"rules.txt", "pgl.txt"}) {
            try (BufferedReader reader = Files.newBufferedReader(
                    assets.resolve(filename), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) builder.acceptLine(line);
            }
        }
        NetworkRules rules = builder.build();
        // Snapshot dated 2026-10-10: Peter Lowe has 3548 unique ABP domains;
        // 4 of Velocity's 8 additional entries overlap it.
        assertEquals(3552, rules.blockedDomains());
        assertTrue(rules.shouldBlock("https://ads.doubleclick.net/banner.js", false));
        assertFalse(rules.shouldBlock("https://doubleclick.net/", true));
    }

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
