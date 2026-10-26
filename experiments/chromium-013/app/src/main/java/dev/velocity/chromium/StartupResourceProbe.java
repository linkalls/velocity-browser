package dev.velocity.chromium;

import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Read-only startup profiler. Self /proc metrics are best effort: Android
 * versions may restrict procfs. Missing counters are -1, never zero.
 * No file paths, browser URLs, keys, or process arguments are logged.
 */
public final class StartupResourceProbe {
    public static final class Snapshot {
        public final long wallMs;
        public final long cpuMs;
        public final long minorFaults;
        public final long majorFaults;
        public final long readBytes;
        public final long writtenBytes;

        Snapshot(long wallMs, long cpuMs, long minorFaults, long majorFaults,
                 long readBytes, long writtenBytes) {
            this.wallMs = wallMs;
            this.cpuMs = cpuMs;
            this.minorFaults = minorFaults;
            this.majorFaults = majorFaults;
            this.readBytes = readBytes;
            this.writtenBytes = writtenBytes;
        }
    }

    private StartupResourceProbe() {}

    public static Snapshot capture() {
        long wallMs = SystemClock.elapsedRealtime();
        long cpuMs = Process.getElapsedCpuTime();
        long minor = -1, major = -1, read = -1, written = -1;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/stat"))) {
            long[] faults = parseFaults(reader.readLine());
            if (faults != null) {
                minor = faults[0];
                major = faults[1];
            }
        } catch (IOException | SecurityException ignored) {
            // Procfs metrics are optional and may be blocked by Android.
        }
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/io"))) {
            long[] bytes = parseIo(reader);
            read = bytes[0];
            written = bytes[1];
        } catch (IOException | SecurityException ignored) {
            // Some sandbox policies deny /proc/self/io.
        }
        return new Snapshot(wallMs, cpuMs, minor, major, read, written);
    }

    /**
     * Linux stat fields 10 (minflt), 12 (majflt). Skip the comm field
     * with its rightmost ')'; comm may contain spaces and parentheses.
     */
    static long[] parseFaults(String stat) {
        if (stat == null) return null;
        int end = stat.lastIndexOf(')');
        if (end < 0 || end + 2 >= stat.length()) return null;
        String[] words = stat.substring(end + 2).trim().split("\\s+");
        // Index zero is field 3 (state), so fields 10 and 12 are 7 and 9.
        if (words.length <= 9) return null;
        try {
            long min = Long.parseLong(words[7]);
            long maj = Long.parseLong(words[9]);
            return min >= 0 && maj >= 0 ? new long[] {min, maj} : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static long[] parseIo(BufferedReader reader) throws IOException {
        long read = -1, written = -1;
        for (String line; (line = reader.readLine()) != null;) {
            if (line.startsWith("read_bytes:")) {
                read = parseNonnegative(line.substring("read_bytes:".length()));
            } else if (line.startsWith("write_bytes:")) {
                written = parseNonnegative(line.substring("write_bytes:".length()));
            }
        }
        return new long[] {read, written};
    }

    private static long parseNonnegative(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? -1 : parsed;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static long delta(long start, long end) {
        return start < 0 || end < start ? -1 : end - start;
    }

    public static String describe(Snapshot start, Snapshot end, String prefix) {
        if (start == null || end == null) {
            return prefix + "_wall_ms=-1 " + prefix + "_cpu_ms=-1 "
                + prefix + "_minor_faults=-1 " + prefix + "_major_faults=-1 "
                + prefix + "_read_bytes=-1 " + prefix + "_written_bytes=-1";
        }
        return prefix + "_wall_ms=" + delta(start.wallMs, end.wallMs)
            + " " + prefix + "_cpu_ms=" + delta(start.cpuMs, end.cpuMs)
            + " " + prefix + "_minor_faults=" + delta(start.minorFaults, end.minorFaults)
            + " " + prefix + "_major_faults=" + delta(start.majorFaults, end.majorFaults)
            + " " + prefix + "_read_bytes=" + delta(start.readBytes, end.readBytes)
            + " " + prefix + "_written_bytes=" + delta(start.writtenBytes, end.writtenBytes);
    }
}
