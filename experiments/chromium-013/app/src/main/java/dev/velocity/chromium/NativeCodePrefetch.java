package dev.velocity.chromium;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.MappedByteBuffer;

/**
 * Experimental bounded native executable page-cache warming.
 *
 * This is opt-in for disposable x86_64 benchmark APKs only. It never
 * reads private profile data, contacts the network or changes renderer
 * sandboxing. It may hurt startup and RAM; DO NOT ship enabled by default.
 */
public final class NativeCodePrefetch {
    private static final String TAG = "VelocityCefStartup";
    // Official Cefrium 0.14 x86_64 ELF starts .text at ~45 MiB. This
    // experimental window is intentionally well into the executable pages,
    // not a promise that future ELF link orders preserve that position.
    static final long EXECUTABLE_WINDOW_OFFSET = 48L * 1024 * 1024;
    private static final int MAX_MIB = 32;

    private NativeCodePrefetch() {}

    /**
     * "mmap" is the original experiment; "read" fills the OS file page
     * cache through a tiny reusable buffer without faulting a second
     * 16-MiB file mapping into Velocity's address space.
     *
     * Both modes are opt-in. Signed arm64 Preview always passes zero.
     */
    public static void start(Context context, int mebibytes, String mode) {
        if (mebibytes < 0 || mebibytes > MAX_MIB) {
            throw new IllegalArgumentException("Native prefetch outside bounded experiment");
        }
        if (!"mmap".equals(mode) && !"read".equals(mode)) {
            throw new IllegalArgumentException("Unknown native prefetch experiment");
        }
        Log.i(TAG, "CEFNativePrefetch requested_mib=" + mebibytes + " mode=" + mode);
        if (mebibytes == 0) return;

        final String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeDir == null) {
            Log.i(TAG, "CEFNativePrefetch status=unavailable");
            return;
        }
        Thread worker = new Thread(() -> {
            long start = SystemClock.elapsedRealtime();
            try (FileInputStream input = new FileInputStream(
                    new File(nativeDir, "libcef.so"));
                 FileChannel channel = input.getChannel()) {
                long length = boundedLength(channel.size(), mebibytes);
                if (length <= 0) {
                    Log.i(TAG, "CEFNativePrefetch status=no_matching_elf_window");
                    return;
                }
                if ("read".equals(mode)) {
                    // Linux can reclaim these cached pages independently
                    // from the short-lived user-space copy buffer.
                    readWindow(channel, EXECUTABLE_WINDOW_OFFSET, length);
                } else {
                    MappedByteBuffer mapped = channel.map(
                        FileChannel.MapMode.READ_ONLY, EXECUTABLE_WINDOW_OFFSET, length);
                    // This original mode may raise process PSS by 10+ MiB.
                    mapped.load();
                }
                Log.i(TAG, "CEFNativePrefetch status=completed bytes=" + length
                    + " wall_ms=" + (SystemClock.elapsedRealtime() - start));
            } catch (IOException | RuntimeException | OutOfMemoryError failure) {
                Log.i(TAG, "CEFNativePrefetch status=failed");
                // A failed hint must never prevent normal CEF startup.
            }
        }, "velocity-native-page-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    /** Read exactly the bounded ELF range with no growing allocation. */
    static void readWindow(FileChannel channel, long startOffset, long length)
            throws IOException {
        if (startOffset < 0 || length < 0) {
            throw new IllegalArgumentException("Invalid read window");
        }
        // A 64-KiB buffer is intentionally tiny compared with Chromium's
        // enormous native ELF and is reused for every read() call.
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long offset = startOffset;
        long remaining = length;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(remaining, buffer.capacity()));
            int count = channel.read(buffer, offset);
            if (count <= 0) {
                throw new IOException("Native ELF prefetch ended early");
            }
            offset += count;
            remaining -= count;
        }
    }

    static long boundedLength(long libraryBytes, int mebibytes) {
        if (mebibytes <= 0 || mebibytes > MAX_MIB
                || libraryBytes <= EXECUTABLE_WINDOW_OFFSET) return 0;
        return Math.min(libraryBytes - EXECUTABLE_WINDOW_OFFSET,
            (long) mebibytes * 1024 * 1024);
    }
}
