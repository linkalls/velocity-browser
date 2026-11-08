package dev.velocity.chromium;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Guards narrow-screen, tablet and very tall-font layout calculations. */
public final class BrowserUiLayoutTest {
    @Test public void mobileWidthsDoNotForceCardsOffScreen() {
        assertEquals(1, BrowserUiLayout.columnsForAvailableWidthDp(
            BrowserUiLayout.availableSheetWidthDp(320)));
        assertEquals(2, BrowserUiLayout.columnsForAvailableWidthDp(
            BrowserUiLayout.availableSheetWidthDp(393)));
        assertEquals(2, BrowserUiLayout.columnsForAvailableWidthDp(
            BrowserUiLayout.availableSheetWidthDp(480)));
        assertEquals(3, BrowserUiLayout.columnsForAvailableWidthDp(
            BrowserUiLayout.availableSheetWidthDp(600)));
        assertEquals(4, BrowserUiLayout.columnsForAvailableWidthDp(
            BrowserUiLayout.availableSheetWidthDp(840)));
    }

    @Test public void columnsAlwaysRespectMinimumCardWidth() {
        for (int width = 240; width <= 1050; width += 5) {
            int available = BrowserUiLayout.availableSheetWidthDp(width);
            int columns = BrowserUiLayout.columnsForAvailableWidthDp(available);
            assertTrue(columns >= 1 && columns <= 4);
            if (available >= BrowserUiLayout.MIN_CARD_WIDTH_DP) {
                int required = columns * BrowserUiLayout.MIN_CARD_WIDTH_DP
                    + (columns - 1) * BrowserUiLayout.CARD_GAP_DP;
                assertTrue("Cards exceed viewport at " + width, required <= available);
            }
        }
    }

    @Test public void shortSheetsShrinkButLongSheetsScroll() {
        assertEquals(300, BrowserUiLayout.sheetMaxHeightPx(300, 1100, 160));
        assertTrue(BrowserUiLayout.sheetMaxHeightPx(1200, 1100, 160) < 1100);
        assertTrue(BrowserUiLayout.sheetMaxHeightPx(1000, 350, 160) < 350);
    }
}
