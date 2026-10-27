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

    @Test public void boundedBufferedReadHandlesOffsetAndShortFinalChunk()
            throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("native-elf-test", ".bin");
        try {
            java.nio.file.Files.write(file, new byte[] {10, 20, 30, 40, 50});
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    file, java.nio.file.StandardOpenOption.READ)) {
                // Consuming a subrange must not seek past the end or grow a buffer.
                NativeCodePrefetch.readWindow(channel, 1, 3);
                NativeCodePrefetch.readWindow(channel, 0, 5);
                NativeCodePrefetch.readWindow(channel, 5, 0);
                assertThrows(java.io.IOException.class,
                    () -> NativeCodePrefetch.readWindow(channel, 2, 4));
                assertThrows(IllegalArgumentException.class,
                    () -> NativeCodePrefetch.readWindow(channel, -1, 1));
                assertThrows(IllegalArgumentException.class,
                    () -> NativeCodePrefetch.readWindow(channel, 0, -1));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
