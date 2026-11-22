package dev.velocity.chromium;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
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
import com.cefrium.CefriumDownloadClient;
import com.cefrium.CefriumProfile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
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
    private static final AtomicBoolean STALE_PRIVATE_CLEANUP = new AtomicBoolean();
    // Serialize publication even if a new Activity replaces the old one.
    private static final ExecutorService FILE_IO = Executors.newSingleThreadExecutor();
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService fileIo = FILE_IO;
    private final Consumer<CefriumBrowser> onIdle;
    private final DownloadHistoryStore history;
    private final File stagingDirectory;
    private final File privateStagingDirectory;
    private final SharedPreferences receipts;
    private CefriumProfile observedProfile;
    private final List<Job> jobs = new ArrayList<>();
    private AlertDialog listDialog;
    private LinearLayout listContent;
    private boolean shownPrivate;
    private long lastRepaintMs;
    private boolean disposed;

    enum State { OFFERED, DOWNLOADING, PUBLISHING, COMPLETE, CANCELED, FAILED, RECOVERABLE, READY_TO_SAVE }

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
        // The browser callback, not a click, confirms the current pause state.
        boolean paused;
        Uri saved;
        boolean previewWhenReady;
        // No raw request URLs or authentication tokens are stored in error UI.
        String failureCategory;
        CefriumProfile.Download recovered;
        Job(CefriumBrowser browser, CefriumBrowser.DownloadItem item,
                String name, boolean privateMode, File temporary) {
            this.browser = browser;
            this.id = item.getId();
            this.name = name;
            this.mime = DownloadNamePolicy.mimeType(item.getMimeType());
            this.privateMode = privateMode;
            this.temporary = temporary;
        }
        Job(DownloadHistory.Entry entry) {
            id = -1;
            name = entry.name();
            mime = entry.mime();
            privateMode = false;
            temporary = null;
            state = State.COMPLETE;
            received = entry.bytes();
            total = entry.bytes();
            saved = Uri.parse(entry.contentUri());
        }
        Job(CefriumProfile.Download item, String name, File temporary) {
            id = -1;
            this.name = name;
            mime = DownloadNamePolicy.mimeType(item.getMimeType());
            privateMode = false;
            this.temporary = temporary;
            recovered = item;
        }
        boolean busy() {
            return state == State.OFFERED || state == State.DOWNLOADING
                || state == State.PUBLISHING;
        }
    }

    CefriumDownloadController(Activity activity, Consumer<CefriumBrowser> onIdle) {
        this.activity = activity;
        this.onIdle = onIdle;
        this.history = new DownloadHistoryStore(activity);
        this.receipts = activity.getSharedPreferences("download-publish-receipts", Context.MODE_PRIVATE);
        File stagingRoot = new File(activity.getNoBackupFilesDir(), "download-staging");
        try { stagingRoot = stagingRoot.getCanonicalFile(); }
        catch (IOException unavailable) { Log.w(TAG, "Could not normalize download staging directory"); }
        this.stagingDirectory = new File(stagingRoot, "regular");
        this.privateStagingDirectory = new File(stagingRoot, "private");
        // Never sweep normal partial files: CEF's normal profile can still own them.
        // Sweep private destinations left by a previous process before a new offer can start.
        if (STALE_PRIVATE_CLEANUP.compareAndSet(false, true)) {
            sweep(privateStagingDirectory.listFiles(DownloadStaging::isPrivateStagingFile));
            // Earlier versions used a shared staging directory without separate privacy labels.
            // Their private residue is not eligible for recovery, so preserve the old cleanup.
            File legacy = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (legacy == null) legacy = activity.getFilesDir();
            sweep(legacy.listFiles(DownloadStaging::isLegacyStagingFile));
        }
    }

    private static void sweep(File[] orphans) {
        if (orphans == null) return;
        for (File orphan : orphans) {
            if (orphan.isFile() && !orphan.delete()) Log.w(TAG, "Orphan cleanup failed");
        }
    }

    void bind(CefriumBrowser browser, boolean privateMode) {
        if (!privateMode) attachNormalProfile(browser.getProfile());
        // Cefrium 0.21 SDK client; the engine supplies the originating browser
        // rather than asking us to capture it in a legacy handler closure.
        browser.setDownloadClient(new CefriumDownloadClient() {
            @Override public void onBeforeDownload(CefriumBrowser source,
                    CefriumBrowser.DownloadItem item,
                    CefriumBrowser.BeforeDownloadCallback callback) {
                main.post(() -> offer(source, privateMode, item, callback));
            }
            @Override public void onDownloadUpdated(CefriumBrowser source,
                    CefriumBrowser.DownloadItem item) {
                main.post(() -> updated(source, item));
            }
        });
    }

    private void attachNormalProfile(CefriumProfile profile) {
        if (profile == null || disposed || observedProfile != null || profile.isIncognito()) return;
        if (!profile.setDownloadObserver(this::updateRecovered)) return;
        observedProfile = profile;
        profile.getDownloads(items -> {
            if (disposed || observedProfile != profile) return;
            for (CefriumProfile.Download item : items) updateRecovered(item);
        });
    }

    private void updateRecovered(CefriumProfile.Download item) {
        if (disposed || item == null || item.getProfile().isIncognito()
                || !item.getProfile().equals(observedProfile)) return;
        String name = DownloadStaging.normalFilename(stagingDirectory, item.getPath());
        if (name == null || item.getGuid() == null || item.getGuid().isEmpty()) return;
        File file = new File(item.getPath());
        Job job = null;
        for (Job candidate : jobs) {
            if (!file.equals(candidate.temporary)) continue;
            if (candidate.browser != null || candidate.state == State.PUBLISHING
                    || candidate.state == State.COMPLETE) return;
            if (candidate.recovered != null
                    && !item.getGuid().equals(candidate.recovered.getGuid())) return;
            job = candidate;
            break;
        }
        int state = item.getState();
        boolean saveable = state == CefriumProfile.DOWNLOAD_COMPLETE && file.isFile();
        boolean canContinue = state == CefriumProfile.DOWNLOAD_INTERRUPTED && item.canResume();
        boolean running = state == CefriumProfile.DOWNLOAD_IN_PROGRESS;
        if (job == null) {
            if (!(saveable || canContinue || running)) return;
            job = new Job(item, name, file);
            jobs.add(job);
        }
        boolean resumedHere = job.state == State.DOWNLOADING;
        job.recovered = item;
        job.received = item.getReceivedBytes();
        job.total = item.getTotalBytes();
        job.paused = item.isPaused();
        if (saveable) {
            job.state = State.READY_TO_SAVE;
            if (resumedHere) publishAsync(job);
        } else if (canContinue) {
            job.state = State.RECOVERABLE;
        } else if (running) {
            job.state = State.DOWNLOADING;
        } else if (state == CefriumProfile.DOWNLOAD_CANCELED) {
            job.state = State.CANCELED;
        } else {
            job.state = State.FAILED;
        }
        repaint();
    }

    private void publishAsync(Job job) {
        if (job.state == State.PUBLISHING) return;
        job.state = State.PUBLISHING;
        fileIo.execute(() -> publish(job));
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
        // This callback means Cefrium has already created a real engine-owned
        // download. Its reported URL is metadata, not an app-side fetch target:
        // blob:, data:, redirected and even empty download URLs may be valid.
        // Re-parsing that URL as HTTP(S) was canceling legitimate downloads.
        // Keep the user confirmation, bounded concurrency and app-private
        // temporary destination below; never retry with a separate HTTP client.
        String safe = DownloadNamePolicy.filename(item.getSuggestedName());
        File directory = privateMode ? privateStagingDirectory : stagingDirectory;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            callback.cancel();
            toast("ファイルの保存領域を準備できなかった");
            return;
        }
        // CEF sees only an app-private, unique path. User-facing name is assigned by MediaStore.
        File temporary = DownloadStaging.target(directory, privateMode, UUID.randomUUID(), safe);
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
        boolean pauseChanged = job.paused != item.isPaused();
        job.paused = item.isPaused();
        if (item.isComplete()) {
            publishAsync(job);
        } else if (item.isCanceled() || item.isInterrupted()) {
            job.state = item.isCanceled() ? State.CANCELED : State.FAILED;
            if (item.isInterrupted()) {
                // Typed 0.21 failure category: FILE, NETWORK, SERVER, USER, OTHER.
                // Avoid leaking a signed URL into logs or the downloads dialog.
                job.failureCategory = String.valueOf(item.getInterruption());
                Log.w(TAG, "CEF download interrupted: " + job.failureCategory);
            }
            if (job.privateMode || item.isCanceled()) {
                final File failedFile = job.temporary;
                fileIo.execute(() -> { if (failedFile.exists()) failedFile.delete(); });
            }
            idle(browser);
            job.browser = null;
            if (!job.privateMode && item.isInterrupted() && observedProfile != null) {
                observedProfile.getDownloads(items -> {
                    for (CefriumProfile.Download download : items) updateRecovered(download);
                });
            }
        }
        if (pauseChanged) lastRepaintMs = 0;
        repaint();
    }

    private void publish(Job job) {
        Uri saved = null;
        boolean success = false;
        boolean pending = true;
        boolean receiptCommitted = false;
        boolean newlyInserted = false;
        String receiptKey = job.temporary.getName();
        try {
            if (!job.privateMode && DownloadStaging.normalFilename(
                    stagingDirectory, job.temporary.getAbsolutePath()) == null)
                throw new IllegalStateException("Unsafe download staging path");
            if (!job.temporary.isFile()) throw new IllegalStateException("Missing downloaded file");
            String prior = job.privateMode ? null : receipts.getString(receiptKey, null);
            if (prior != null) {
                receiptCommitted = true;
                saved = Uri.parse(prior);
                if (!isOwnedReceiptUri(saved))
                    throw new IllegalStateException("Invalid publication receipt");
                try (var row = activity.getContentResolver().query(saved,
                        new String[]{MediaStore.Downloads.IS_PENDING}, null, null, null)) {
                    if (row == null || !row.moveToFirst())
                        throw new IllegalStateException("Download destination is unavailable");
                    pending = row.getInt(0) != 0;
                }
            } else {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, job.name);
                values.put(MediaStore.Downloads.MIME_TYPE, job.mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Velocity Browser");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                saved = activity.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                newlyInserted = saved != null;
            }
            if (saved == null) throw new IllegalStateException("MediaStore insert failed");
            if (!job.privateMode && !receiptCommitted) {
                if (!isOwnedReceiptUri(saved)) throw new IllegalStateException("Invalid download destination");
                if (!receipts.edit().putString(receiptKey, saved.toString()).commit())
                    throw new IllegalStateException("Could not record download destination");
                receiptCommitted = true;
            }
            if (pending) {
                try (InputStream input = new FileInputStream(job.temporary);
                        OutputStream output = activity.getContentResolver().openOutputStream(saved, "rwt")) {
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
            }
            success = true;
        } catch (Exception error) {
            Log.w(TAG, "Download publication failed");
            if (saved != null && newlyInserted && (job.privateMode || !receiptCommitted)
                    && isOwnedReceiptUri(saved)) {
                try { activity.getContentResolver().delete(saved, null, null); }
                catch (RuntimeException cleanupError) { Log.w(TAG, "Partial MediaStore cleanup failed"); }
            }
        } finally {
            if ((success || job.privateMode) && job.temporary.exists() && !job.temporary.delete())
                Log.w(TAG, "Temporary download cleanup failed");
            if (success && !job.privateMode && !job.temporary.exists())
                receipts.edit().remove(receiptKey).apply();
        }
        final Uri result = success ? saved : null;
        if (result != null && !job.privateMode) {
            try {
                history.record(new DownloadHistory.Entry(result.toString(), job.name,
                    job.mime, Math.max(0, job.received)));
            } catch (IllegalArgumentException invalid) {
                Log.w(TAG, "Could not record download location");
            }
        }
        main.post(() -> {
            if (disposed) return;
            job.saved = result;
            job.state = result != null ? State.COMPLETE
                    : job.privateMode ? State.FAILED : State.READY_TO_SAVE;
            toast(result == null ? "ダウンロードの保存に失敗した" : job.name + " を保存したよ");
            CefriumBrowser completedBrowser = job.browser;
            idle(completedBrowser);
            job.browser = null;
            if (result != null && job.previewWhenReady) showPdf(job);
            repaint();
        });
    }

    private static boolean isOwnedReceiptUri(Uri uri) {
        String value = uri.toString();
        return isRowUnder(value, MediaStore.Downloads.EXTERNAL_CONTENT_URI.toString())
                || isRowUnder(value, MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY).toString());
    }

    private static boolean isRowUnder(String value, String collection) {
        String base = collection + "/";
        return value.startsWith(base) && value.substring(base.length()).matches("[0-9]+");
    }

    private void idle(CefriumBrowser browser) {
        if (browser != null && !isBusy(browser)) onIdle.accept(browser);
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

        List<Job> visibleJobs = new ArrayList<>();
        List<DownloadHistory.Entry> persisted = shownPrivate ? java.util.Collections.emptyList() : history.read();
        // Live jobs take precedence; otherwise show completed regular downloads newest first.
        for (int index = jobs.size() - 1; index >= 0; index--) {
            Job job = jobs.get(index);
            if (job.privateMode == shownPrivate && (job.privateMode || job.state != State.COMPLETE
                    || persisted.stream().noneMatch(entry -> job.saved != null
                        && entry.contentUri().equals(job.saved.toString())))) {
                visibleJobs.add(job);
            }
        }
        for (DownloadHistory.Entry entry : persisted) {
            visibleJobs.add(new Job(entry));
        }
        for (Job job : visibleJobs) {
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
            if (job.state == State.OFFERED) {
                statusText = "確認待ち";
            } else if (job.state == State.DOWNLOADING) {
                statusText = job.paused ? "一時停止中" : "ダウンロード中";
            } else if (job.state == State.PUBLISHING) {
                statusText = "保存中…";
            } else if (job.state == State.RECOVERABLE) {
                statusText = "中断・再開可能";
            } else if (job.state == State.READY_TO_SAVE) {
                statusText = "ダウンロード済み・保存待ち";
            } else if (job.state == State.COMPLETE) {
                statusText = "完了";
            } else if (job.state == State.CANCELED) {
                statusText = "キャンセル済み";
            } else {
                statusText = job.failureCategory == null
                    ? "失敗" : "失敗 (" + job.failureCategory + ")";
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
                progress.setIndeterminate(job.total <= 0 && !job.paused);
                if (job.total > 0) progress.setProgress(percent);
                card.addView(progress, new LinearLayout.LayoutParams(-1, dp8 * 2));

                LinearLayout actions = new LinearLayout(activity);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                boolean wasPaused = job.paused;
                Button togglePause = new Button(activity);
                togglePause.setText(wasPaused ? "再開" : "一時停止");
                togglePause.setOnClickListener(v -> {
                    if (disposed || job.state != State.DOWNLOADING
                            || job.paused != wasPaused) return;
                    if (job.recovered != null) {
                        boolean accepted = wasPaused ? job.recovered.resume() : job.recovered.pause();
                        if (!accepted) toast("ダウンロードを操作できません");
                    } else if (job.browser != null) {
                        if (wasPaused) job.browser.resumeDownload(job.id);
                        else job.browser.pauseDownload(job.id);
                    }
                });
                actions.addView(togglePause, new LinearLayout.LayoutParams(0, -2, 1));
                Button cancel = new Button(activity);
                cancel.setText("キャンセル");
                cancel.setOnClickListener(v -> {
                    if (!disposed && job.state == State.DOWNLOADING) {
                        if (job.recovered != null) job.recovered.cancel();
                        else if (job.browser != null) job.browser.cancelDownload(job.id);
                    }
                });
                actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, -2);
                btnLp.topMargin = dp8 / 2;
                card.addView(actions, btnLp);
            } else if (job.state == State.RECOVERABLE || job.state == State.READY_TO_SAVE) {
                boolean resume = job.state == State.RECOVERABLE;
                Button continueButton = new Button(activity);
                continueButton.setText(resume ? "再開" : "端末に保存");
                continueButton.setOnClickListener(v -> {
                    if (disposed) return;
                    if (resume && job.state == State.RECOVERABLE && job.recovered != null) {
                        if (activeCount() >= MAX_ACTIVE) {
                            toast("同時ダウンロード数の上限（5件）に達しています");
                        } else if (job.recovered.canResume() && job.recovered.resume()) {
                            if (job.state == State.RECOVERABLE) job.state = State.DOWNLOADING;
                            repaint();
                        } else toast("このダウンロードは再開できません");
                    } else if (!resume && job.state == State.READY_TO_SAVE) {
                        publishAsync(job);
                        repaint();
                    }
                });
                card.addView(continueButton, new LinearLayout.LayoutParams(-1, -2));
            } else if (job.saved != null && job.state == State.COMPLETE) {
                LinearLayout actions = new LinearLayout(activity);
                actions.setOrientation(LinearLayout.HORIZONTAL);

                Button open = new Button(activity);
                open.setText("開く");
                open.setOnClickListener(v -> withReadableFile(job, () -> openSaved(job)));
                actions.addView(open, new LinearLayout.LayoutParams(0, -2, 1));

                Button share = new Button(activity);
                share.setText("共有");
                share.setOnClickListener(v -> withReadableFile(job, () -> shareSaved(job)));
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

    private void withReadableFile(Job job, Runnable action) {
        Uri uri = job.saved;
        if (uri == null || disposed) return;
        fileIo.execute(() -> {
            boolean readable = false;
            boolean missing = false;
            try (var descriptor = activity.getContentResolver().openFileDescriptor(uri, "r")) {
                readable = descriptor != null;
            } catch (java.io.FileNotFoundException missingFile) {
                // Missing and inaccessible files are distinguished below; do not delete bytes.
                missing = true;
            } catch (java.io.IOException | SecurityException unavailable) {
                Log.w(TAG, "Could not read saved download");
            }
            boolean canOpen = readable;
            boolean notFound = missing;
            main.post(() -> {
                if (disposed || !uri.equals(job.saved)) return;
                if (canOpen) {
                    action.run();
                } else {
                    toast("保存ファイルを開けなかった");
                    if (notFound && !job.privateMode) offerForgetMissing(uri);
                }
            });
        });
    }

    private void offerForgetMissing(Uri uri) {
        new AlertDialog.Builder(activity).setTitle("ファイルにアクセスできません")
            .setMessage("ダウンロード履歴の項目だけを取り除きますか？")
            .setPositiveButton("履歴から消す", (d, w) -> {
                history.forget(uri.toString());
                jobs.removeIf(job -> !job.privateMode && uri.equals(job.saved));
                renderList();
            })
            .setNegativeButton("閉じる", null).show();
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
                        if (!job.privateMode) history.forget(job.saved.toString());
                        Uri removed = job.saved;
                        jobs.removeIf(candidate -> removed.equals(candidate.saved));
                        job.saved = null;
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
            if (job.privateMode && job.state == State.DOWNLOADING && job.browser != null)
                job.browser.cancelDownload(job.id);
        }
        if (observedProfile != null) observedProfile.setDownloadObserver(null);
        observedProfile = null;
        jobs.clear();
    }
}
