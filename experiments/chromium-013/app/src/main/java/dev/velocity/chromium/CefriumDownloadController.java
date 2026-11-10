package dev.velocity.chromium;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
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
    private static final int MAX_ACTIVE = 5;
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
        boolean previewWhenReady;
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
        if (disposed) {
            callback.cancel();
            return;
        }
        if (activeCount() >= MAX_ACTIVE) {
            callback.cancel();
            if (!disposed) toast("同時ダウンロード数の上限（5件）に達しています");
            return;
        }
        if (!DownloadNamePolicy.supportedSource(item.getUrl())) {
            callback.cancel();
            if (!disposed) toast("サポートされていないURL形式です");
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
        boolean pdf = job.mime.equals("application/pdf")
            || job.name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("ファイルを保存")
            .setMessage(safe + (privateMode
                ? "\n\n非公開タブで保存したファイルも端末のダウンロードに残るよ。"
                : "\n\n完了後、端末の「ダウンロード」に表示するよ。"))
            .setPositiveButton("ダウンロード", (dialog, which) ->
                accept(job, callback, decided, false))
            .setNegativeButton("キャンセル", (dialog, which) -> {
                if (decided.compareAndSet(false, true)) callback.cancel();
                job.state = State.CANCELED;
                idle(browser);
            });
        if (pdf) builder.setNeutralButton("保存してPDFを開く", (dialog, which) ->
            accept(job, callback, decided, true));
        AlertDialog confirm = builder.create();
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

    private void accept(Job job, CefriumBrowser.BeforeDownloadCallback callback,
            AtomicBoolean decided, boolean preview) {
        if (!decided.compareAndSet(false, true)) return;
        if (disposed) {
            callback.cancel();
            job.state = State.CANCELED;
            return;
        }
        job.previewWhenReady = preview;
        job.state = State.DOWNLOADING;
        callback.allow(job.temporary.getAbsolutePath());
        repaint();
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
            if (result != null && job.previewWhenReady) showPdf(job);
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

    private String formatBytes(long bytes) {
        if (bytes < 0) return "0 B";
        return android.text.format.Formatter.formatFileSize(activity, bytes);
    }

    private void renderList() {
        if (listContent == null) return;
        lastRepaintMs = SystemClock.elapsedRealtime();
        listContent.removeAllViews();
        int count = 0;
        float density = activity.getResources().getDisplayMetrics().density;
        int dp8 = (int) (8 * density + .5f);
        int dp12 = (int) (12 * density + .5f);

        for (Job job : jobs) {
            if (job.privateMode != shownPrivate) continue;
            count++;

            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp12, dp12, dp12, dp12);
            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(Color.rgb(43, 46, 54));
            cardBg.setCornerRadius(12 * density);
            card.setBackground(cardBg);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.bottomMargin = dp12;
            listContent.addView(card, cardLp);

            TextView title = new TextView(activity);
            title.setText(job.name);
            title.setTextSize(15);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setTextColor(Color.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            card.addView(title);

            String statusText;
            if (job.state == State.DOWNLOADING) {
                statusText = "ダウンロード中";
            } else if (job.state == State.PUBLISHING) {
                statusText = "保存中…";
            } else if (job.state == State.COMPLETE) {
                statusText = "完了";
            } else if (job.state == State.CANCELED) {
                statusText = "キャンセル済み";
            } else {
                statusText = "失敗";
            }

            String sizeText = job.total > 0
                ? formatBytes(job.received) + " / " + formatBytes(job.total)
                : formatBytes(job.received);
            int percent = (job.total > 0 && job.received >= 0)
                ? (int) Math.min(100, (job.received * 100L / job.total)) : -1;

            TextView detail = new TextView(activity);
            detail.setText(statusText + " · " + sizeText + (percent >= 0 ? " (" + percent + "%)" : ""));
            detail.setTextSize(12);
            detail.setTextColor(Color.rgb(170, 178, 195));
            detail.setPadding(0, dp8 / 2, 0, dp8 / 2);
            card.addView(detail);

            if (job.state == State.DOWNLOADING) {
                android.widget.ProgressBar progress = new android.widget.ProgressBar(
                    activity, null, android.R.attr.progressBarStyleHorizontal);
                progress.setIndeterminate(job.total <= 0);
                if (job.total > 0) progress.setProgress(percent);
                card.addView(progress, new LinearLayout.LayoutParams(-1, dp8 * 2));

                Button cancel = new Button(activity);
                cancel.setText("キャンセル");
                cancel.setOnClickListener(v -> job.browser.cancelDownload(job.id));
                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
                btnLp.topMargin = dp8 / 2;
                card.addView(cancel, btnLp);
            } else if (job.saved != null && job.state == State.COMPLETE) {
                LinearLayout actions = new LinearLayout(activity);
                actions.setOrientation(LinearLayout.HORIZONTAL);

                Button open = new Button(activity);
                open.setText("開く");
                open.setOnClickListener(v -> openSaved(job));
                actions.addView(open, new LinearLayout.LayoutParams(0, -2, 1));

                Button share = new Button(activity);
                share.setText("共有");
                share.setOnClickListener(v -> shareSaved(job));
                LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(0, -2, 1);
                shareLp.leftMargin = dp8;
                actions.addView(share, shareLp);

                Button delete = new Button(activity);
                delete.setText("削除");
                delete.setOnClickListener(v -> deleteSaved(job));
                LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(0, -2, 1);
                delLp.leftMargin = dp8;
                actions.addView(delete, delLp);

                LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-1, -2);
                actLp.topMargin = dp8 / 2;
                card.addView(actions, actLp);
            }
        }
        if (count == 0) {
            TextView empty = new TextView(activity);
            empty.setText("保存したファイルはまだないよ");
            empty.setTextColor(Color.LTGRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp12 * 4, 0, dp12 * 4);
            listContent.addView(empty);
        }
    }

    private void shareSaved(Job job) {
        if (job.saved == null) return;
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(job.mime);
            share.putExtra(Intent.EXTRA_STREAM, job.saved);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(share, "ファイルを共有"));
        } catch (Exception err) {
            toast("共有できるアプリが見つからなかった");
        }
    }

    private void deleteSaved(Job job) {
        if (job.saved == null) return;
        new AlertDialog.Builder(activity)
            .setTitle("ファイルを削除")
            .setMessage(job.name + " を端末から削除する？")
            .setPositiveButton("削除", (d, w) -> {
                try {
                    int deleted = activity.getContentResolver().delete(job.saved, null, null);
                    if (deleted > 0) {
                        job.saved = null;
                        jobs.remove(job);
                        toast("削除したよ");
                        renderList();
                    } else {
                        toast("ファイルが見つからないか削除できなかった");
                    }
                } catch (Exception err) {
                    toast("削除に失敗した");
                }
            })
            .setNegativeButton("キャンセル", null)
            .show();
    }

    private void showPdf(Job job) {
        if (job.saved == null) return;
        Intent preview = new Intent(activity, PdfPreviewActivity.class);
        preview.setData(job.saved);
        preview.putExtra(PdfPreviewActivity.EXTRA_PRIVATE, job.privateMode);
        activity.startActivity(preview);
    }

    private void openSaved(Job job) {
        if (job.mime.equals("application/pdf")
                || job.name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            showPdf(job);
            return;
        }
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
