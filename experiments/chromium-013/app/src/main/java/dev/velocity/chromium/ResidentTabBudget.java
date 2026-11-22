package dev.velocity.chromium;

/** A soft resident WebContents target, not a cap on the number of saved tabs. */
final class ResidentTabBudget {
    private static final long GIB = 1024L * 1024L * 1024L;

    private ResidentTabBudget() {}

    static int target(long totalBytes, boolean lowRamDevice, boolean lowMemoryNow) {
        // Preserve a modest number of neighboring pages on constrained devices.
        if (lowMemoryNow) return 4;
        if (lowRamDevice) return 5;
        if (totalBytes >= 7 * GIB) return 10;
        if (totalBytes >= 5 * GIB) return 8;
        return 6;
    }
}
