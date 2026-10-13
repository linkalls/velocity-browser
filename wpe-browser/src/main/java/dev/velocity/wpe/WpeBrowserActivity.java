package dev.velocity.wpe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.wpewebkit.wpeview.WPEChromeClient;
import org.wpewebkit.wpeview.WPEView;
import org.wpewebkit.wpeview.WPEViewClient;

/**
 * Isolated WPE WebKit proof of concept.
 * Not Android System WebView, not GeckoView, no dynamic toolbar and no extensions.
 * The fixed chrome is deliberate for comparing scrolling/painting independently.
 */
public final class WpeBrowserActivity extends Activity {
    private static final String[] PRESET_NAMES = {
        "Yahoo!ニュース", "Google検索", "Wikipedia", "Reddit", "YouTube", "Amazon.co.jp"
    };
    private static final String[] PRESET_URLS = {
        "https://news.yahoo.co.jp/",
        "https://www.google.com/search?q=android",
        "https://ja.wikipedia.org/wiki/Android",
        "https://www.reddit.com/",
        "https://www.youtube.com/",
        "https://www.amazon.co.jp/"
    };

    private WPEView engine;
    private EditText address;
    private TextView title;
    private TextView progressLabel;
    private ProgressBar progress;
    private Button back;
    private Button forward;
    private boolean destroyed;

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button button(String value, String accessibility, Runnable onClick) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setContentDescription(accessibility);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(20, 22, 28));
        getWindow().setNavigationBarColor(Color.rgb(20, 22, 28));
        getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.rgb(26, 29, 36));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        title = new TextView(this);
        title.setText("Velocity WPE · ENGINE EXPERIMENT");
        title.setTextColor(Color.rgb(228, 234, 245));
        title.setTextSize(13);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(14), dp(8), dp(14), dp(5));
        header.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(8), 0, dp(8), dp(3));
        header.addView(addressRow, new LinearLayout.LayoutParams(-1, -2));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(15);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(Color.LTGRAY);
        address.setHint("URL または検索");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        address.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadAddress();
                return true;
            }
            return false;
        });
        addressRow.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button go = button("Go", "URLを開く", this::loadAddress);
        addressRow.addView(go, new LinearLayout.LayoutParams(dp(64), dp(48)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        header.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));

        FrameLayout canvas = new FrameLayout(this);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1f));

        // This class is the independent WPE WebKit port's widget.
        // Do NOT accidentally import android.webkit.WebView here.
        engine = new WPEView(this);
        canvas.addView(engine, new FrameLayout.LayoutParams(-1, -1));
        engine.getSettings().setDeveloperExtrasEnabled(true);
        engine.setWPEViewClient(new WPEViewClient() {
            @Override public void onPageStarted(WPEView view, String url) {
                if (destroyed) return;
                updateAddress(url);
                title.setText("読み込み中 · Velocity WPE");
                updateButtons();
            }
            @Override public void onPageFinished(WPEView view, String url) {
                if (destroyed) return;
                updateAddress(url);
                title.setText("Velocity WPE · " + safeTitle(view.getTitle()));
                progress.setProgress(0);
                updateButtons();
            }
            @Override public void onReceivedSslError(WPEView view, SslErrorHandler handler,
                                                      android.net.http.SslError error) {
                // Never bypass invalid certificate warnings.
                handler.cancel();
                Toast.makeText(WpeBrowserActivity.this, "TLS証明書を検証できません", Toast.LENGTH_SHORT).show();
            }
        });
        engine.setWPEChromeClient(new WPEChromeClient() {
            @Override public void onProgressChanged(WPEView view, int value) {
                if (!destroyed) progress.setProgress(Math.max(0, Math.min(value, 100)));
            }
            @Override public void onReceivedTitle(WPEView view, String pageTitle) {
                if (!destroyed) title.setText("Velocity WPE · " + safeTitle(pageTitle));
            }
        });

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(4), 0, dp(4), 0);
        footer.setBackgroundColor(Color.rgb(26, 29, 36));
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(54)));

        back = button("‹", "戻る", () -> { if (engine.canGoBack()) engine.goBack(); });
        forward = button("›", "進む", () -> { if (engine.canGoForward()) engine.goForward(); });
        Button reload = button("↻", "再読み込み", () -> engine.reload());
        Button presets = button("Sites", "主要サイトを選ぶ", this::showPresets);
        Button info = button("ⓘ", "実験版の制限", this::showInfo);
        footer.addView(back, new LinearLayout.LayoutParams(0, -1, 1f));
        footer.addView(forward, new LinearLayout.LayoutParams(0, -1, 1f));
        footer.addView(reload, new LinearLayout.LayoutParams(0, -1, 1f));
        footer.addView(presets, new LinearLayout.LayoutParams(0, -1, 1.6f));
        footer.addView(info, new LinearLayout.LayoutParams(0, -1, 1f));

        updateButtons();
        String initial = "https://news.yahoo.co.jp/";
        if (getIntent() != null && Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            String candidate = getIntent().getDataString();
            if (NavigationPolicy.parse(candidate) != null) initial = candidate;
        }
        address.setText(initial);
        engine.loadUrl(initial);
        address.clearFocus();
    }

    private String safeTitle(String name) {
        if (name == null || name.isBlank()) return "WebKit";
        return name.replace('\n', ' ').replace('\r', ' ');
    }

    private void updateAddress(String url) {
        if (url != null && !address.hasFocus()) address.setText(url);
    }

    private void updateButtons() {
        if (engine == null) return;
        back.setEnabled(engine.canGoBack());
        forward.setEnabled(engine.canGoForward());
    }

    private void loadAddress() {
        String url = NavigationPolicy.parse(address.getText().toString());
        if (url == null) {
            Toast.makeText(this, "HTTP/HTTPSのURLまたは検索語を入力", Toast.LENGTH_LONG).show();
            return;
        }
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
        engine.loadUrl(url);
    }

    private void showPresets() {
        new AlertDialog.Builder(this).setTitle("同一ページでエンジン比較")
            .setItems(PRESET_NAMES, (dialog, index) -> {
                address.clearFocus();
                address.setText(PRESET_URLS[index]);
                engine.loadUrl(PRESET_URLS[index]);
            })
            .setNegativeButton("戻る", null).show();
    }

    private void showInfo() {
        new AlertDialog.Builder(this).setTitle("Velocity WPE 実験版")
            .setMessage("WPE WebKit 2.50.6（WPE Android 0.3.3）\n\n" +
                "GeckoViewやAndroid標準WebViewは使用していません。\n\n" +
                "スクロール比較用の最低限のUIです。固定ツールバーで、広告ブロック、" +
                "複数タブ、Passkey、プライベートモード、保存、ダウンロード、" +
                "権限管理などは移植前です。\n\n" +
                "重要なアカウントへのログインには使用しないでください。")
            .setPositiveButton("閉じる", null).show();
    }

    @Override @Deprecated public void onBackPressed() {
        if (engine != null && engine.canGoBack()) engine.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (engine != null) {
            android.view.ViewGroup parent = (android.view.ViewGroup) engine.getParent();
            if (parent != null) parent.removeView(engine);
            engine.destroy();
            engine = null;
        }
        super.onDestroy();
    }
}
