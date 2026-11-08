package dev.velocity.chromium;

/**
 * Dimension-only rules shared by the responsive native browser UI.
 * This class intentionally has no Android dependencies so CI unit tests can
 * catch regressions before emulator screenshots are taken.
 */
final class BrowserUiLayout {
    private BrowserUiLayout() {}

    /** The sheet's outer horizontal padding on each side, in dp. */
    static final int SHEET_SIDE_PADDING_DP = 18;
    static final int CARD_GAP_DP = 8;
    static final int MIN_CARD_WIDTH_DP = 148;

    static int columnsForAvailableWidthDp(int availableWidthDp) {
        // Do not try to fit two 148dp cards in a 320dp viewport with padding.
        int columns = 1;
        for (int next = 2; next <= 4; next++) {
            int required = next * MIN_CARD_WIDTH_DP + (next - 1) * CARD_GAP_DP;
            if (availableWidthDp >= required) columns = next;
        }
        return columns;
    }

    static int availableSheetWidthDp(int viewportWidthDp) {
        return Math.max(1, viewportWidthDp - 2 * SHEET_SIDE_PADDING_DP);
    }

    static int sheetMaxHeightPx(int contentHeightPx, int screenHeightPx, int minimumPx) {
        // Keep the sheet below the status bar and leave room for system UI;
        // using actual Activity height also respects split-screen layouts.
        int safeScreenHeight = Math.max(minimumPx, screenHeightPx);
        return Math.max(minimumPx, Math.min(contentHeightPx,
            (int) (safeScreenHeight * 0.77f)));
    }
}
