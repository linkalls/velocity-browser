package dev.velocity.chromium;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.cefrium.CefriumBrowser;

/**
 * A strictly separate, single-tab Chromium/CEF prototype to evaluate engine
 * behavior, rather than a hidden Android WebView replacement.
 *
 * There is deliberately no moving toolbar, URL logging, automatic profile
 * recording or page JavaScript instrumentation. This is not a secure general
 * browser yet: downloads, Passkeys, tabs, permissions and extensions need
 * separate architecture / testing before any migration.
 */
public final class ChromiumTestActivity extends Activity {
    private static final String[] SITE_NAMES = {
        "Yahoo!ニュース", "Google検索", "Wikipedia", "Reddit", "YouTube", "Amazon.co.jp"
    };
    private static final String[] SITE_URLS = {
        "https://news.yahoo.co.jp/",
        "https://www.google.com/search?q=android",
        "https://ja.wikipedia.org/wiki/Android",
        "https://www.reddit.com/",
        "https://www.youtube.com/",
        "https://www.amazon.co.jp/"
    };

    private CefriumBrowser engine;
    private EditText address;
    private TextView title;
    private Button back;
    private Button forward;
    private boolean destroyed;
    private static final String TAG = "VelocityCefStartup";

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button makeButton(String label, String hint, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setContentDescription(hint);
        b.setTextColor(Color.WHITE);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(21, 25, 32));
        getWindow().setNavigationBarColor(Color.rgb(21, 25, 32));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.TRANSPARENT); // CEF surface may live behind this overlay.
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.rgb(27, 32, 40));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        title = new TextView(this);
        title.setText("Velocity Chromium · 0.9.4 · Experiment");
        title.setTextColor(Color.WHITE);
        title.setSingleLine(true);
        title.setTextSize(13);
        title.setPadding(dp(12), dp(8), dp(12), 0);
        header.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), dp(4));
        header.addView(row, new LinearLayout.LayoutParams(-1, -2));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(Color.LTGRAY);
        address.setHint("URL / 検索");
        address.setTextSize(15);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        address.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                loadAddress();
                return true;
            }
            return false;
        });
        row.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1f));
        row.addView(makeButton("Go", "サイトを開く", this::loadAddress),
            new LinearLayout.LayoutParams(dp(68), dp(48)));

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));


        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setBackgroundColor(Color.rgb(27, 32, 40));
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(54)));

        back = makeButton("‹", "戻る", () -> { if (engine != null && engine.canGoBack()) engine.goBack(); });
        forward = makeButton("›", "進む", () -> { if (engine != null) engine.goForward(); });
        Button reload = makeButton("↻", "再読み込み", () -> { if (engine != null) engine.reload(); });
        Button sites = makeButton("Sites", "比較用サイト", this::showSites);
        Button info = makeButton("ⓘ", "Chromium実験版の注意", this::showInfo);
        footer.addView(back, new LinearLayout.LayoutParams(0, -1, 1f));
        footer.addView(forward, new LinearLayout.LayoutParams(0, -1, 1f));
        footer.addView(reload, new LinearLayout.LayoutParams(0, -1, 1f));
        footer.addView(sites, new LinearLayout.LayoutParams(0, -1, 1.6f));
        footer.addView(info, new LinearLayout.LayoutParams(0, -1, 1f));

        String initial = SITE_URLS[0];
        address.setText(initial);
        updateNavigation();
        address.clearFocus();
        // Start native renderer after Android has attached the window.
        root.post(() -> startChromium(root, content, header, footer, initial));
    }

    private void positionChromiumSurface(FrameLayout surface, View header, View footer) {
        if (destroyed || !(surface.getParent() instanceof FrameLayout)) return;
        int top = header.getHeight();
        int bottom = footer.getHeight();
        ViewGroup.LayoutParams old = surface.getLayoutParams();
        if (old instanceof FrameLayout.LayoutParams &&
            old.width == -1 && old.height == -1 &&
            ((FrameLayout.LayoutParams) old).topMargin == top &&
            ((FrameLayout.LayoutParams) old).bottomMargin == bottom) return;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
        params.topMargin = top;
        params.bottomMargin = bottom;
        surface.setLayoutParams(params);
    }

    private void startChromium(LinearLayout root, FrameLayout content, View header, View footer,
                               String initialUrl) {
        if (destroyed || isFinishing()) return;
        try {
            // Preserve Chromium's native Surface attachment to WindowAndroid.
            // Reparenting the SDK-owned surface can break native lifecycle.
            engine = CefriumBrowser.createWithSurface(this);
            FrameLayout surface = engine.getSurfaceContainer();
            if (surface.getParent() == null) {
                content.addView(surface, new FrameLayout.LayoutParams(-1, -1));
            } else if (surface.getParent() != content) {
                root.bringToFront();
                root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) ->
                    positionChromiumSurface(surface, header, footer));
                surface.post(() -> positionChromiumSurface(surface, header, footer));
            }
    
            engine.setOnUrlChangedListener(url -> runOnUiThread(() -> {
                if (destroyed) return;
                if (!address.hasFocus() && url != null) address.setText(url);
                updateNavigation();
            }));
            engine.setOnTitleChangedListener(name -> runOnUiThread(() -> {
                if (destroyed) return;
                title.setText("Chromium · " + (name == null || name.isBlank() ? "Loading" : name));
                updateNavigation();
            }));
    
            engine.loadUrl(initialUrl);
            updateNavigation();
            Log.i(TAG, "CEF surface created and page navigation started");
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "CEF Surface startup failure", error);
            if (engine != null) {
                try { engine.close(); } catch (RuntimeException ignored) { }
                engine = null;
            }
            title.setText("Chromium初期化失敗");
            new AlertDialog.Builder(this)
                .setTitle("Chromium起動エラー")
                .setMessage(error.getClass().getSimpleName() + ": " + error.getMessage() +
                    "\\n\\nlogcat: VelocityCefStartup / AndroidRuntime")
                .setPositiveButton("閉じる", null).show();
        }
    }

    private void updateNavigation() {
        if (engine == null || back == null || forward == null) return;
        back.setEnabled(engine.canGoBack());
        // CefriumBrowser does not expose canGoForward in 0.9.4 Javadoc.
        // Keep the forward action enabled; CEF itself handles the no-history case.
        forward.setEnabled(true);
    }

    private void loadAddress() {
        String parsed = NavigationPolicy.parse(address.getText().toString());
        if (parsed == null) {
            Toast.makeText(this, "HTTP(S) URLか検索語を入力", Toast.LENGTH_LONG).show();
            return;
        }
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
        engine.loadUrl(parsed);
    }

    private void showSites() {
        new AlertDialog.Builder(this)
            .setTitle("同一サイトでスクロール比較")
            .setItems(SITE_NAMES, (dialog, item) -> {
                address.clearFocus();
                address.setText(SITE_URLS[item]);
                engine.loadUrl(SITE_URLS[item]);
            })
            .setNegativeButton("閉じる", null).show();
    }

    private void showInfo() {
        new AlertDialog.Builder(this).setTitle("Velocity Chromium 実験版")
            .setMessage("エンジン：Chromium（Cefrium 0.9.4）\n\n" +
                "これはAndroid標準WebViewではありません。\n\n" +
                "Gecko/WPE版とは別アプリとしてインストールできます。\n\n" +
                "固定ツールバーでエンジンのスクロールを比較する初期実験です。" +
                "拡張機能、Passkey、タブ、ダウンロード・権限管理、保存、" +
                "完全なセキュリティ検証は未移植。重要なアカウントにはログインしないでください。")
            .setPositiveButton("OK", null).show();
    }

    @Override protected void onPause() {
        if (engine != null) engine.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (engine != null) engine.onResume();
    }

    @Override @Deprecated protected void onActivityResult(
        int requestCode, int resultCode, Intent data
    ) {
        if (engine == null || !engine.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override @Deprecated public void onBackPressed() {
        if (engine != null && engine.canGoBack()) engine.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (engine != null) {
            engine.close();
            engine = null;
        }
        super.onDestroy();
    }
}
