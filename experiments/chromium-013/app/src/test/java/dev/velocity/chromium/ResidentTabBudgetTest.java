package dev.velocity.chromium;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class ResidentTabBudgetTest {
    @Test public void scalesWithoutIgnoringMemoryPressure() {
        long gib = 1L << 30;
        assertEquals(5, ResidentTabBudget.target(3 * gib, true, false));
        assertEquals(6, ResidentTabBudget.target(4 * gib, false, false));
        assertEquals(8, ResidentTabBudget.target(6 * gib, false, false));
        assertEquals(10, ResidentTabBudget.target(8 * gib, false, false));
        assertEquals(4, ResidentTabBudget.target(8 * gib, false, true));
    }
}
