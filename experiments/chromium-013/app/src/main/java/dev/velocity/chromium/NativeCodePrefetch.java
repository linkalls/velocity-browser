package dev.velocity.chromium;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.MappedByteBuffer;

/**
 * Experimental bounded native executable page-cache warming.
 *
 * This is opt-in for disposable x86_64 benchmark APKs only.  It never
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

    public static void start(Context context, int mebibytes) {
        if (mebibytes < 0 || mebibytes > MAX_MIB) {
            throw new IllegalArgumentException("Native prefetch outside bounded experiment");
        }
        Log.i(TAG, "CEFNativePrefetch requested_mib=" + mebibytes);
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
                long fileSize = channel.size();
                long length = boundedLength(fileSize, mebibytes);
                if (length <= 0) {
                    Log.i(TAG, "CEFNativePrefetch status=no_matching_elf_window");
                    return;
                }
                MappedByteBuffer mapped = channel.map(
                    FileChannel.MapMode.READ_ONLY, EXECUTABLE_WINDOW_OFFSET, length);
                // Best effort read-ahead. Kernel may ignore this if memory is
                // pressured. Keep the page cache reclaimable; never mlock.
                mapped.load();
                Log.i(TAG, "CEFNativePrefetch status=completed bytes=" + length
                    + " wall_ms=" + (SystemClock.elapsedRealtime() - start));
            } catch (IOException | RuntimeException | OutOfMemoryError failure) {
                Log.i(TAG, "CEFNativePrefetch status=failed");
                // Experimental failure must never prevent normal CEF init.
            }
        }, "velocity-native-page-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    static long boundedLength(long libraryBytes, int mebibytes) {
        if (mebibytes <= 0 || mebibytes > MAX_MIB
                || libraryBytes <= EXECUTABLE_WINDOW_OFFSET) return 0;
        return Math.min(libraryBytes - EXECUTABLE_WINDOW_OFFSET,
            (long) mebibytes * 1024 * 1024);
    }
}
