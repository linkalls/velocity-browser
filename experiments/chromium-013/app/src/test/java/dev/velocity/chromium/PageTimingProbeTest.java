package dev.velocity.chromium;

import org.junit.Test;
import static org.junit.Assert.*;

public class PageTimingProbeTest {
    @Test public void restrictsNavigationToExactPublicBenchmarkPages() {
        assertTrue(PageTimingProbe.allows("https://www.wikipedia.org/"));
        assertFalse(PageTimingProbe.allows("https://www.wikipedia.org.evil.test/"));
        assertFalse(PageTimingProbe.allows("https://www.wikipedia.org/?secret=1"));
        assertFalse(PageTimingProbe.allows(null));
    }
    @Test public void rejectsMissingMalformedOrReversedPaintTimings() {
        assertArrayEquals(new int[]{20, 30, 40}, PageTimingProbe.parse("VelocityExternalTiming2026:20:30:40"));
        for (String title : new String[]{null, "Normal title", "VelocityExternalTiming2026:0:30:40",
                "VelocityExternalTiming2026:50:30:40", "VelocityExternalTiming2026:20:50:40",
                "VelocityExternalTiming2026:20:30:9999999", "VelocityExternalTiming2026:NaN:30:40"}) {
            assertNull(PageTimingProbe.parse(title));
        }
    }
}
