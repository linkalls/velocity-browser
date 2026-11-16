package dev.velocity.chromium;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Toast;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local PDF reader for documents already downloaded by Cefrium and published
 * as an Android content URI. No network request, Cookie extraction, or PDF JS.
 *
 * The renderer and all document page operations run on one background thread.
 * Only the current page's bounded bitmap is retained in the UI.
 */
public final class PdfPreviewActivity extends Activity {
    public static final String EXTRA_PRIVATE = "velocity_private_pdf";
    private final ExecutorService pdfWorker = Executors.newSingleThreadExecutor();
    private PdfRenderer renderer;
    private ZoomablePage pageView;
    private TextView pageLabel;
    private TextView status;
    private TextView previous;
    private TextView next;
    private int pageCount;
    private int selectedPage;
    private volatile int requestedGeneration;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra(EXTRA_PRIVATE, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        Uri source = getIntent().getData();
        // Never consume network or filesystem URLs; only a content provider
        // opened through Android's permission-aware ContentResolver.
        if (source == null || !"content".equals(source.getScheme())) {
            finish();
            return;
        }

        // Android 15+ forces edge-to-edge for targetSdk 35. Opt in explicitly
        // on API 30+ so system-bar insets have one predictable owner: this root.
        // API 29 retains the platform's standard fitted content window.
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) {
                // Keep white status/navigation glyphs readable on the dark viewer.
                bars.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            getWindow().setStatusBarColor(Color.rgb(22, 23, 28));
            getWindow().setNavigationBarColor(Color.rgb(22, 23, 28));
        }
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(22, 23, 28));
        if (Build.VERSION.SDK_INT >= 30) {
            // Apply only absolute insets, never add them to existing padding.
            // This prevents double-padding on re-dispatch, rotation and cutouts.
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        }
        setContentView(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView back = control("閉じる", "PDFプレビューを閉じる", this::finish);
        bar.addView(back, new LinearLayout.LayoutParams(dp(72), dp(48)));
        pageLabel = new TextView(this);
        pageLabel.setText("PDF");
        pageLabel.setTextSize(15);
        pageLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setSingleLine(true);
        pageLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(pageLabel, new LinearLayout.LayoutParams(0, -1, 1));

        TextView share = control("共有", "PDFファイルを共有", () -> shareDocument(source));
        bar.addView(share, new LinearLayout.LayoutParams(dp(58), dp(48)));

        previous = control("‹", "前のPDFページへ", () -> showPage(selectedPage - 1));
        next = control("›", "次のPDFページへ", () -> showPage(selectedPage + 1));
        LinearLayout.LayoutParams previousSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        previousSize.leftMargin = dp(6);
        bar.addView(previous, previousSize);
        LinearLayout.LayoutParams nextSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        nextSize.leftMargin = dp(4);
        bar.addView(next, nextSize);
        previous.setEnabled(false);
        next.setEnabled(false);
        previous.setAlpha(0.4f);
        next.setAlpha(0.4f);

        pageView = new ZoomablePage(this);
        root.addView(pageView, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.rgb(187, 194, 207));
        status.setText("PDFを読み込み中…");
        status.setTextSize(13);
        status.setBackgroundColor(Color.rgb(22, 23, 28));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(48)));
        openDocument(source);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    /** Native rounded, accessible controls; no legacy themed Button chrome. */
    private TextView control(String text, String description, Runnable action) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(text.equals("‹") || text.equals("›") ? 27 : 14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setContentDescription(description);
        button.setFocusable(true);
        GradientDrawable surface = new GradientDrawable();
        surface.setShape(GradientDrawable.RECTANGLE);
        surface.setColor(Color.rgb(46, 49, 59));
        surface.setCornerRadius(dp(18));
        // Ripple feedback works with keyboard, TalkBack, and touch without
        // pulling a different Material theme into the embedded CEF process.
        button.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Color.argb(64, 255, 255, 255)), surface, null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void shareDocument(Uri source) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/pdf");
            share.putExtra(Intent.EXTRA_STREAM, source);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "PDFを共有"));
        } catch (Exception err) {
            Toast.makeText(this, "共有できるアプリが見つからなかった", Toast.LENGTH_SHORT).show();
        }
    }

    private void openDocument(Uri source) {
        pdfWorker.execute(() -> {
            try {
                ParcelFileDescriptor descriptor =
                    getContentResolver().openFileDescriptor(source, "r");
                if (descriptor == null) throw new IOException("Missing PDF descriptor");
                try {
                    renderer = new PdfRenderer(descriptor);
                } catch (Exception error) {
                    descriptor.close();
                    throw error;
                }
                int count = renderer.getPageCount();
                if (count < 1) throw new IOException("Empty PDF");
                runOnUiThread(() -> {
                    if (destroyed) return;
                    pageCount = count;
                    showPage(0);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    status.setText("このPDFは表示できないよ。破損・暗号化・非対応形式の可能性がある。");
                    pageLabel.setText("PDFを開けない");
                });
            }
        });
    }

    private void showPage(int index) {
        if (renderer == null || index < 0 || index >= pageCount || destroyed) return;
        selectedPage = index;
        pageLabel.setText((index + 1) + " / " + pageCount);
        previous.setEnabled(index > 0);
        next.setEnabled(index < pageCount - 1);
        previous.setAlpha(index > 0 ? 1f : 0.4f);
        next.setAlpha(index < pageCount - 1 ? 1f : 0.4f);
        status.setText("ページを描画中…");
        int generation = ++requestedGeneration;
        int desiredWidth = Math.min(2048,
            Math.max(640, getResources().getDisplayMetrics().widthPixels));
        pdfWorker.execute(() -> {
            Bitmap bitmap = null;
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                // A bounded bitmap prevents rendering huge PDFs into full-resolution RAM.
                int width = desiredWidth;
                int height = Math.max(1,
                    (int) ((long) width * page.getHeight() / Math.max(1, page.getWidth())));
                if (height > 3072) {
                    width = Math.max(1, width * 3072 / height);
                    height = 3072;
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            } catch (Exception | OutOfMemoryError error) {
                if (bitmap != null) bitmap.recycle();
                bitmap = null;
            }
            Bitmap rendered = bitmap;
            runOnUiThread(() -> {
                if (destroyed || generation != requestedGeneration) {
                    if (rendered != null) rendered.recycle();
                    return;
                }
                if (rendered == null) {
                    status.setText("このページを描画できなかった");
                } else {
                    pageView.setPage(rendered);
                    status.setText("2本指で拡大・縮小できるよ");
                }
            });
        });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        requestedGeneration++;
        if (pageView != null) pageView.setPage(null);
        pdfWorker.execute(() -> {
            if (renderer != null) renderer.close();
            renderer = null;
        });
        pdfWorker.shutdown();
        super.onDestroy();
    }

    /** Fit-page on arrival; matrix pinch zoom and one-finger pan, no giant scroll bitmap. */
    private static final class ZoomablePage extends ImageView {
        private final Matrix transform = new Matrix();
        private final ScaleGestureDetector pinch;
        private Bitmap current;
        private float scale = 1f;
        private float lastX;
        private float lastY;

        ZoomablePage(Activity context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            setBackgroundColor(Color.rgb(31, 33, 40));
            setContentDescription("PDFページ");
            pinch = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float next = Math.max(1f, Math.min(5f, scale * detector.getScaleFactor()));
                        float factor = next / scale;
                        scale = next;
                        transform.postScale(factor, factor,
                            detector.getFocusX(), detector.getFocusY());
                        setImageMatrix(transform);
                        return true;
                    }
                });
        }

        void setPage(Bitmap page) {
            Bitmap old = current;
            current = page;
            setImageBitmap(page);
            scale = 1f;
            post(this::fit);
            if (old != null && old != page) old.recycle();
        }

        private void fit() {
            if (current == null || getWidth() <= 0 || getHeight() <= 0) return;
            float ratio = Math.min((float)getWidth() / current.getWidth(),
                                   (float)getHeight() / current.getHeight());
            transform.reset();
            transform.postScale(ratio, ratio);
            transform.postTranslate((getWidth() - current.getWidth() * ratio) / 2f,
                                    (getHeight() - current.getHeight() * ratio) / 2f);
            setImageMatrix(transform);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (current == null) return false;
            pinch.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastX = event.getX(); lastY = event.getY();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && event.getPointerCount() == 1 && scale > 1f) {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                transform.postTranslate(dx, dy);
                setImageMatrix(transform);
            }
            lastX = event.getX(); lastY = event.getY();
            return true;
        }
    }
}
