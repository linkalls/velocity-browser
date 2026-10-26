package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NativeCodePrefetchTest {
    @Test public void disabledModeNeverReadsAnyBytes() {
        assertEquals(0, NativeCodePrefetch.boundedLength(238619136L, 0));
    }

    @Test public void boundedWindowCannotReadPastElfEnd() {
        assertEquals(16L * 1024 * 1024,
            NativeCodePrefetch.boundedLength(238619136L, 16));
        assertEquals(1024, NativeCodePrefetch.boundedLength(
            NativeCodePrefetch.EXECUTABLE_WINDOW_OFFSET + 1024, 16));
        assertEquals(0, NativeCodePrefetch.boundedLength(
            NativeCodePrefetch.EXECUTABLE_WINDOW_OFFSET, 16));
    }

    @Test public void oversizedWindowIsRejectedInsteadOfReadingEntireLibrary() {
        assertEquals(0, NativeCodePrefetch.boundedLength(238619136L, 64));
        assertEquals(0, NativeCodePrefetch.boundedLength(238619136L, -1));
    }
}
