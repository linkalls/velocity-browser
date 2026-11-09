package dev.velocity.chromium;

/**
 * Dimension-only rules shared by the responsive native browser UI.
 * This class intentionally has no Android dependencies so CI unit tests can
 * catch regressions before emulator screenshots are taken.
 */
final class BrowserUiLayout {
    private BrowserUiLayout() {}

    /** The sheet's outer horizontal padding on each side, in dp. */
    static final int SHEET_SIDE_PADDING_DP = 14;
    static final int CARD_GAP_DP = 10;
    static final int MIN_CARD_WIDTH_DP = 128;

    static int columnsForAvailableWidthDp(int availableWidthDp) {
        // Real mobile tab switchers show two previews side-by-side even at
        // 320dp. Do not let a giant blank Home card push the active site
        // entirely below the fold. Narrow split-screen widths still use one.
        int preferred = availableWidthDp >= 740 ? 4
            : availableWidthDp >= 500 ? 3 : 2;
        for (int columns = preferred; columns >= 2; columns--) {
            int required = columns * MIN_CARD_WIDTH_DP
                + (columns - 1) * CARD_GAP_DP;
            if (required <= availableWidthDp) return columns;
        }
        return 1;
    }

    /** Cards show a useful portion of a portrait Web page, not a tiny strip. */
    static int tabPreviewHeightDp(int columns) {
        return columns <= 1 ? 264 : columns == 2 ? 205
            : columns == 3 ? 168 : 140;
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
