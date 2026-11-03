package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

public final class StartupResourceProbeTest {
    @Test public void parseStatSkipsParenthesesAndCommSpaces() {
        String stat = "1234 (cef browser (x)) S 1 2 3 4 5 6 109 8 13 9 10 11 12";
        long[] faults = StartupResourceProbe.parseFaults(stat);
        assertNotNull(faults);
        assertArrayEquals(new long[]{109, 13}, faults);
    }

    @Test public void invalidProcfsIsMissingNotZero() {
        assertNull(StartupResourceProbe.parseFaults(null));
        assertNull(StartupResourceProbe.parseFaults("42 (bad) S"));
        assertNull(StartupResourceProbe.parseFaults("42 (bad) S 1 2 3 4 5 6 nope 8 0 9"));
    }

    @Test public void ioSeparatesActualStorageBytesFromRchar() throws IOException {
        BufferedReader in = new BufferedReader(new StringReader(
            "rchar: 5000000\nsyscr: 23\nread_bytes: 16384\n"
                + "wchar: 40000\nwrite_bytes: 8192\ncancelled_write_bytes: 0\n"));
        assertArrayEquals(new long[]{16384, 8192}, StartupResourceProbe.parseIo(in));
        assertArrayEquals(new long[]{-1, -1}, StartupResourceProbe.parseIo(
            new BufferedReader(new StringReader("rchar: 33\n"))));
    }

    @Test public void deltaDoesNotInventMissingOrNegativeNumbers() {
        StartupResourceProbe.Snapshot first = new StartupResourceProbe.Snapshot(
            100, 30, 12, 400, 5, -1, 1000);
        StartupResourceProbe.Snapshot last = new StartupResourceProbe.Snapshot(
            900, 530, 111, 780, 6, -1, 800);
        String timing = StartupResourceProbe.describe(first, last, "provider");
        assertTrue(timing.contains("provider_wall_ms=800"));
        assertTrue(timing.contains("provider_cpu_ms=500"));
        assertTrue(timing.contains("provider_thread_cpu_ms=99"));
        assertTrue(timing.contains("provider_minor_faults=380"));
        assertTrue(timing.contains("provider_major_faults=1"));
        assertTrue(timing.contains("provider_read_bytes=-1"));
        assertTrue(timing.contains("provider_written_bytes=-1"));
        assertTrue(StartupResourceProbe.describe(null, last, "provider")
            .contains("provider_thread_cpu_ms=-1"));
    }
}
