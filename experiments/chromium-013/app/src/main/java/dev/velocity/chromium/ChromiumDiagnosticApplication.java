package dev.velocity.chromium;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Installs a crash capture before ContentProviders are installed.
 *
 * Cefrium's auto-init provider can fail before any Activity starts. The
 * separate :diagnostic process reads the last stack from the same app sandbox.
 * This does NOT mask the crash or make the Chromium engine safe to browse with.
 */
public final class ChromiumDiagnosticApplication extends Application {
    static final String CRASH_FILE = "cefrium-last-java-crash.txt";
    static final String TAG = "VelocityCefStartup";
    // Set in the main process BEFORE ContentProvider installation. This lets
    // perf tests measure native/provider startup independently of Activity UI.
    static volatile long mainProcessAttachedAtMs;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        String process = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : "";
        if (!getPackageName().equals(process)) {
            // Diagnostic/renderer/network processes do not write the main
            // process Java stack trace, preventing misleading overwrites.
            return;
        }
        mainProcessAttachedAtMs = SystemClock.elapsedRealtime();
        final Thread.UncaughtExceptionHandler original =
            Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                File target = new File(getFilesDir(), CRASH_FILE);
                try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(target, false), StandardCharsets.UTF_8))) {
                    String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.JAPAN)
                        .format(new Date());
                    writer.println("Velocity Chromium 0.14.0 - Java crash at " + time);
                    writer.println("process=" + process + " thread=" + thread.getName());
                    writer.println("Android=" + Build.VERSION.SDK_INT);
                    error.printStackTrace(writer);
                    writer.flush();
                }
            } catch (Throwable ignored) {
                // Never interfere with Android's default fatal exception handling.
            }
            if (original != null) {
                original.uncaughtException(thread, error);
            } else {
                Log.e(TAG, "Uncaught Java startup exception", error);
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }
}
