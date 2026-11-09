package dev.velocity.chromium;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cefrium.CefriumBrowser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Receives bytes through CEF's authenticated download pipeline, never re-fetches a URL.
 * CEF writes to an app-owned temporary file; only completed bytes are published to
 * Android MediaStore.Downloads. The app requests no broad storage permission.
 */
final class CefriumDownloadController {
    private static final String TAG = "VelocityDownloads";
    private static final int MAX_ACTIVE = 2;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService fileIo = Executors.newSingleThreadExecutor();
    private final Consumer<CefriumBrowser> onIdle;
    private final List<Job> jobs = new ArrayList<>();
    private AlertDialog listDialog;
    private LinearLayout listContent;
    private boolean shownPrivate;
    private long lastRepaintMs;
    private boolean disposed;

    enum State { OFFERED, DOWNLOADING, PUBLISHING, COMPLETE, CANCELED, FAILED }

    private static final class Job {
        CefriumBrowser browser;
        final int id;
        final String name;
        final String mime;
        final boolean privateMode;
        final File temporary;
        State state = State.OFFERED;
        long received;
        long total = -1;
        Uri saved;
        Job(CefriumBrowser browser, CefriumBrowser.DownloadItem item,
                String name, boolean privateMode, File temporary) {
            this.browser = browser;
            this.id = item.getId();
            this.name = name;
            this.mime = DownloadNamePolicy.mimeType(item.getMimeType());
            this.privateMode = privateMode;
            this.temporary = temporary;
        }
        boolean busy() {
            return state == State.OFFERED || state == State.DOWNLOADING
                || state == State.PUBLISHING;
        }
    }

    CefriumDownloadController(Activity activity, Consumer<CefriumBrowser> onIdle) {
        this.activity = activity;
        this.onIdle = onIdle;
        // Clean interrupted prior-session temporary bytes, including private downloads.
        fileIo.execute(() -> {
            File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null) directory = activity.getFilesDir();
            File[] orphans = directory.listFiles((dir, name) -> name.startsWith("velocity-"));
            if (orphans != null) for (File orphan : orphans) {
                if (orphan.isFile() && !orphan.delete()) Log.w(TAG, "Orphan cleanup failed");
            }
        });
    }

    void bind(CefriumBrowser browser, boolean privateMode) {
        browser.setDownloadHandler(new CefriumBrowser.CefriumDownloadHandler() {
            @Override public void onBeforeDownload(CefriumBrowser.DownloadItem item,
                    CefriumBrowser.BeforeDownloadCallback callback) {
                // All mutable state and Android dialogs live on the Android main thread.
                main.post(() -> offer(browser, privateMode, item, callback));
            }
            @Override public void onDownloadUpdated(CefriumBrowser.DownloadItem item) {
                main.post(() -> updated(browser, item));
            }
        });
    }

    boolean isBusy(CefriumBrowser browser) {
        for (Job job : jobs) if (job.browser == browser && job.busy()) return true;
        return false;
    }

    private int activeCount() {
        int count = 0;
        for (Job job : jobs) if (job.busy()) count++;
        return count;
    }

    private void offer(CefriumBrowser browser, boolean privateMode,
            CefriumBrowser.DownloadItem item, CefriumBrowser.BeforeDownloadCallback callback) {
        AtomicBoolean decided = new AtomicBoolean();
        if (disposed || !DownloadNamePolicy.supportedSource(item.getUrl())
                || activeCount() >= MAX_ACTIVE) {
            callback.cancel();
            if (!disposed) toast("ダウンロードできないファイル、または同時保存数の上限だよ");
            return;
        }
        String safe = DownloadNamePolicy.filename(item.getSuggestedName());
        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) directory = activity.getFilesDir();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            callback.cancel();
            toast("ファイルの保存領域を準備できなかった");
            return;
        }
        // CEF sees only an app-private, unique path. User-facing name is assigned by MediaStore.
        File temporary = new File(directory, "velocity-" + UUID.randomUUID() + "-" + safe);
        Job job = new Job(browser, item, safe, privateMode, temporary);
        jobs.add(job);
        // Keep memory and any private history bounded. Never discard an active job.
        if (jobs.size() > 30) {
            int extra = jobs.size() - 30;
            for (int i = 0; i < jobs.size() && extra > 0; i++) {
                if (!jobs.get(i).busy()) { jobs.remove(i--); extra--; }
            }
        }
        AlertDialog confirm = new AlertDialog.Builder(activity)
            .setTitle("ファイルを保存")
            .setMessage(safe + (privateMode
                ? "\n\n非公開タブで保存したファイルも端末のダウンロードに残るよ。"
                : "\n\n完了後、端末の「ダウンロード」に表示するよ。"))
            .setPositiveButton("ダウンロード", (dialog, which) -> {
                if (!decided.compareAndSet(false, true)) return;
                if (disposed) { callback.cancel(); job.state = State.CANCELED; return; }
                job.state = State.DOWNLOADING;
                callback.allow(temporary.getAbsolutePath());
                repaint();
            })
            .setNegativeButton("キャンセル", (dialog, which) -> {
                if (decided.compareAndSet(false, true)) callback.cancel();
                job.state = State.CANCELED;
                idle(browser);
            })
            .create();
        confirm.setOnCancelListener(dialog -> {
            if (decided.compareAndSet(false, true)) callback.cancel();
            job.state = State.CANCELED;
            idle(browser);
        });
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            confirm.show();
            if (privateMode) confirm.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        else {
            if (decided.compareAndSet(false, true)) callback.cancel();
            job.state = State.CANCELED;
            idle(browser);
        }
    }

    private void updated(CefriumBrowser browser, CefriumBrowser.DownloadItem item) {
        if (disposed) return;
        Job job = null;
        for (Job candidate : jobs) {
            if (candidate.browser == browser && candidate.id == item.getId()) {
                job = candidate;
                break;
            }
        }
        // Ignore duplicate terminal updates: never publish the same bytes twice.
        if (job == null || job.state != State.DOWNLOADING) return;
        job.received = item.getReceivedBytes();
        job.total = item.getTotalBytes();
        if (item.isComplete()) {
            job.state = State.PUBLISHING;
            final Job completed = job;
            fileIo.execute(() -> publish(completed));
        } else if (item.isCanceled() || item.isInterrupted()) {
            job.state = item.isCanceled() ? State.CANCELED : State.FAILED;
            final File failedFile = job.temporary;
            fileIo.execute(() -> { if (failedFile.exists()) failedFile.delete(); });
            idle(browser);
            job.browser = null;
        }
        repaint();
    }

    private void publish(Job job) {
        Uri saved = null;
        boolean success = false;
        try {
            if (!job.temporary.isFile()) throw new IllegalStateException("Missing downloaded file");
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, job.name);
            values.put(MediaStore.Downloads.MIME_TYPE, job.mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Velocity Browser");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            saved = activity.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (saved == null) throw new IllegalStateException("MediaStore insert failed");
            try (InputStream input = new FileInputStream(job.temporary);
                    OutputStream output = activity.getContentResolver().openOutputStream(saved, "w")) {
                if (output == null) throw new IllegalStateException("MediaStore output missing");
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                output.flush();
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            if (activity.getContentResolver().update(saved, ready, null, null) != 1)
                throw new IllegalStateException("MediaStore publish failed");
            success = true;
        } catch (Exception error) {
            Log.e(TAG, "Download publication failed", error);
            if (saved != null) {
                try { activity.getContentResolver().delete(saved, null, null); }
                catch (RuntimeException cleanupError) { Log.w(TAG, "Partial MediaStore cleanup failed"); }
            }
        } finally {
            if (job.temporary.exists() && !job.temporary.delete())
                Log.w(TAG, "Temporary download cleanup failed");
        }
        final Uri result = success ? saved : null;
        main.post(() -> {
            if (disposed) return;
            job.saved = result;
            job.state = result == null ? State.FAILED : State.COMPLETE;
            toast(result == null ? "ダウンロードの保存に失敗した" : job.name + " を保存したよ");
            CefriumBrowser completedBrowser = job.browser;
            idle(completedBrowser);
            job.browser = null;
            repaint();
        });
    }

    private void idle(CefriumBrowser browser) {
        if (!isBusy(browser)) onIdle.accept(browser);
        repaint();
    }

    void show(boolean privateMode) {
        if (disposed) return;
        if (listDialog != null) listDialog.dismiss();
        shownPrivate = privateMode;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * activity.getResources().getDisplayMetrics().density + .5f);
        content.setPadding(pad, pad, pad, pad);
        listContent = content;
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        listDialog = new AlertDialog.Builder(activity).setTitle("ダウンロード")
            .setView(scroll).setPositiveButton("閉じる", (d, w) -> {}).create();
        listDialog.setOnDismissListener(d -> {
            listDialog = null;
            listContent = null;
        });
        listDialog.show();
        if (privateMode) listDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        renderList();
    }

    private void repaint() {
        if (listDialog == null || listContent == null) return;
        long since = SystemClock.elapsedRealtime() - lastRepaintMs;
        if (since >= 250) renderList();
        else {
            main.removeCallbacks(repaintRunnable);
            main.postDelayed(repaintRunnable, 250 - Math.max(0, since));
        }
    }
    private final Runnable repaintRunnable = this::renderList;

    private void renderList() {
        if (listContent == null) return;
        lastRepaintMs = SystemClock.elapsedRealtime();
        listContent.removeAllViews();
        int count = 0;
        for (Job job : jobs) {
            if (job.privateMode != shownPrivate) continue;
            count++;
            TextView title = new TextView(activity);
            title.setText(job.name);
            title.setTextSize(16);
            listContent.addView(title);
            TextView detail = new TextView(activity);
            String amount = job.total > 0
                ? job.received + " / " + job.total + " bytes"
                : job.received + " bytes";
            detail.setText(job.state + " · " + amount);
            listContent.addView(detail);
            if (job.state == State.DOWNLOADING) {
                Button cancel = new Button(activity);
                cancel.setText("保存をキャンセル");
                cancel.setOnClickListener(v -> job.browser.cancelDownload(job.id));
                listContent.addView(cancel);
            } else if (job.saved != null && job.state == State.COMPLETE) {
                Button open = new Button(activity);
                open.setText("開く");
                open.setOnClickListener(v -> openSaved(job));
                listContent.addView(open);
            }
        }
        if (count == 0) {
            TextView empty = new TextView(activity);
            empty.setText("保存したファイルはまだないよ");
            listContent.addView(empty);
        }
    }

    private void openSaved(Job job) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(job.saved, job.mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (RuntimeException noViewer) {
            toast("このファイルを開けるアプリが見つからなかった");
        }
    }

    private void toast(String value) {
        if (!disposed) Toast.makeText(activity, value, Toast.LENGTH_SHORT).show();
    }

    void dispose() {
        disposed = true;
        main.removeCallbacks(repaintRunnable);
        if (listDialog != null) listDialog.dismiss();
        listContent = null;
        for (Job job : jobs) {
            if (job.state == State.DOWNLOADING) job.browser.cancelDownload(job.id);
        }
        fileIo.shutdown();
        jobs.clear();
    }
}
