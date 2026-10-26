package dev.velocity.chromium;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.os.Bundle;
import android.os.Build;
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
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

import com.cefrium.Cefrium;
import com.cefrium.CefriumBrowser;
import org.chromium.base.CommandLine;
import org.chromium.content.browser.ChildProcessCreationParamsImpl;

/**
 * A separate Cefrium browser test bed with persistent regular tab metadata,
 * isolated private tabs, navigation, and native Surface rendering.
 *
 * No moving toolbar or page JavaScript instrumentation is introduced.
 * This is not ready as a default browser: permission/download policies,
 * extensions, and physical device validation remain open.
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

    private final TabRegistry tabRegistry = new TabRegistry();
    private final Map<String, CefriumBrowser> liveBrowsers = new LinkedHashMap<>();
    private static final String TAB_PREFS = "cefrium_tabs_v1";
    private CefriumBrowser engine;
    private LinearLayout browserRoot;
    private FrameLayout browserContent;
    private View browserHeader;
    private View browserFooter;
    private boolean chromiumReady;
    private Button tabsButton;
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
        browserRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.TRANSPARENT); // CEF surface may live behind this overlay.
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.rgb(27, 32, 40));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        title = new TextView(this);
        title.setText("Velocity Chromium · 0.13.0 · Experiment");
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
        browserContent = content;
        browserHeader = header;
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));


        LinearLayout footer = new LinearLayout(this);
        browserFooter = footer;
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setBackgroundColor(Color.rgb(27, 32, 40));
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(54)));

        back = makeButton("‹", "戻る", () -> { if (engine != null && engine.canGoBack()) engine.goBack(); });
        forward = makeButton("›", "進む", () -> { if (engine != null) engine.goForward(); });
        Button reload = makeButton("↻", "再読み込み", () -> { if (engine != null) engine.reload(); });
        tabsButton = makeButton("□", "タブの一覧", this::showTabs);
        Button menu = makeButton("⋮", "メニュー", this::showMenu);
        footer.addView(back, new LinearLayout.LayoutParams(0, -1, 1f));
        footer.addView(forward, new LinearLayout.LayoutParams(0, -1, 1f));
        footer.addView(reload, new LinearLayout.LayoutParams(0, -1, 1f));
        footer.addView(tabsButton, new LinearLayout.LayoutParams(0, -1, 1f));
        footer.addView(menu, new LinearLayout.LayoutParams(0, -1, 1f));

        updateNavigation();
        address.clearFocus();
        // The SDK creates WindowAndroid and surfaces only after attachment.
        root.post(this::startChromium);
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


    /** Select the SDK's isolated Java services before lazy native initialization. */
    private void configureRendererLauncher() {
        Cefrium.setupProcess(this);
        // 0.13.0's provider only sets up the process. Native initialization is
        // lazy, so CommandLine may not exist yet when this Activity starts.
        if (!CommandLine.isInitialized()) CommandLine.init(null);
        if (Build.VERSION.SDK_INT >= 37) {
            // The official AAR does not implement the native-only entry point.
            CommandLine.getInstance().appendSwitchWithValue("javaless-renderers", "disabled");
        }
        try {
            PackageManager manager = getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(
                getPackageName(), PackageManager.GET_META_DATA);
            String expected = info.metaData == null ? null : info.metaData.getString(
                "org.chromium.content.browser.SANDBOXED_SERVICES_NAME");
            int count = info.metaData == null ? 0 : info.metaData.getInt(
                "org.chromium.content.browser.NUM_SANDBOXED_SERVICES", 0);
            String selected = ChildProcessCreationParamsImpl.getSandboxedServicesName();
            if (!"com.cefrium.ChildServices$Sandboxed".equals(expected)
                    || !expected.equals(selected) || count != 40) {
                throw new IllegalStateException("Unexpected renderer pool: " + selected
                    + " metadata=" + expected + " count=" + count);
            }
            for (int slot = 0; slot < count; slot++) {
                ServiceInfo service = manager.getServiceInfo(
                    new ComponentName(this, expected + slot), 0);
                if ((service.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0
                        || service.exported || !service.enabled) {
                    throw new IllegalStateException("Unsafe renderer service slot " + slot);
                }
                if (slot == 0 && (service.flags & ServiceInfo.FLAG_USE_APP_ZYGOTE) == 0) {
                    throw new IllegalStateException("Renderer service 0 has no app zygote");
                }
            }
            Log.i(TAG, "Verified Java renderer pool: 40 isolated services; API "
                + Build.VERSION.SDK_INT + "; selected=" + selected);
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IllegalStateException("Missing Chromium renderer service", failure);
        }
    }

    /** The launcher must be verified once before the first Cefrium browser. */
    private void startChromium() {
        if (destroyed || isFinishing()) return;
        try {
            configureRendererLauncher();
            chromiumReady = true;
            restoreRegularTabs();
            if (tabRegistry.current() == null) tabRegistry.add(SITE_URLS[0], false);
            activateCurrentTab();
            Log.i(TAG, "CEF tabbed test bed initialized");
        } catch (RuntimeException | LinkageError error) {
            chromiumReady = false;
            Log.e(TAG, "CEF startup failure", error);
            title.setText("Chromium初期化失敗");
            new AlertDialog.Builder(this).setTitle("Chromium起動エラー")
                .setMessage(error.getClass().getSimpleName() + ": " + error.getMessage())
                .setPositiveButton("閉じる", null).show();
        }
    }

    /** Preserve normal tab URLs only. A private session is never serialized. */
    private void restoreRegularTabs() {
        SharedPreferences prefs = getSharedPreferences(TAB_PREFS, MODE_PRIVATE);
        try {
            JSONObject state = new JSONObject(prefs.getString("state", "{}"));
            JSONArray normal = state.optJSONArray("normal");
            if (normal == null) return;
            int selected = state.optInt("selected", 0);
            String selectedId = null;
            for (int i = 0; i < normal.length() && i < TabRegistry.MAX_TABS; i++) {
                JSONObject item = normal.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "");
                if (!isPersistable(url)) continue;
                TabRegistry.Tab tab = tabRegistry.add(url, false);
                if (tab == null) break;
                tab.title = item.optString("title", "");
                if (i == selected) selectedId = tab.id;
            }
            if (selectedId != null) tabRegistry.select(selectedId);
        } catch (Exception error) {
            Log.w(TAG, "Saved regular-tab metadata is invalid; ignoring", error);
        }
    }

    private static boolean isPersistable(String url) {
        if (url == null || url.length() > 2048) return false;
        if (url.equals("about:blank")) return true;
        String parsed = NavigationPolicy.parse(url);
        return parsed != null && parsed.equals(url)
            && (url.startsWith("https://") || url.startsWith("http://"));
    }

    private void saveRegularTabs() {
        JSONArray normal = new JSONArray();
        int selected = 0;
        int regularIndex = 0;
        TabRegistry.Tab current = tabRegistry.current();
        try {
            for (TabRegistry.Tab tab : tabRegistry.regularTabs()) {
                if (!isPersistable(tab.url)) continue;
                if (tab == current) selected = regularIndex;
                normal.put(new JSONObject()
                    .put("url", tab.url)
                    .put("title", tab.title == null ? "" : tab.title.substring(
                        0, Math.min(tab.title.length(), 120))));
                regularIndex++;
            }
            getSharedPreferences(TAB_PREFS, MODE_PRIVATE).edit()
                .putString("state", new JSONObject()
                    .put("normal", normal)
                    .put("selected", selected).toString()).apply();
        } catch (Exception error) {
            Log.w(TAG, "Could not save regular-tab metadata", error);
        }
    }

    private void createTab(String url, boolean privateMode) {
        if (!chromiumReady) return;
        TabRegistry.Tab tab = tabRegistry.add(url, privateMode);
        if (tab == null) {
            Toast.makeText(this, "タブ上限に達しました", Toast.LENGTH_SHORT).show();
            return;
        }
        activateCurrentTab();
        saveRegularTabs();
    }

    private void selectTab(String id) {
        if (tabRegistry.select(id)) {
            activateCurrentTab();
            saveRegularTabs();
        }
    }

    private void attachSurface(CefriumBrowser browser) {
        FrameLayout surface = browser.getSurfaceContainer();
        // Cefrium sometimes parents its Surface itself; never detach a native
        // SurfaceView from its WindowAndroid to force it into our hierarchy.
        if (surface.getParent() == null) {
            browserContent.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        } else if (surface.getParent() != browserContent) {
            browserRoot.bringToFront();
            browserRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) ->
                positionChromiumSurface(surface, browserHeader, browserFooter));
            surface.post(() -> positionChromiumSurface(surface, browserHeader, browserFooter));
        }
    }

    private CefriumBrowser createEngineFor(TabRegistry.Tab tab) {
        CefriumBrowser browser = CefriumBrowser.createWithSurface(this, tab.privateMode);
        try {
            attachSurface(browser);
            browser.setOnLoadingStateChangedListener((loading, canBack, canForward) ->
                runOnUiThread(() -> {
                    if (destroyed || tabRegistry.find(tab.id) != tab) return;
                    tab.loading = loading;
                    tab.canGoBack = canBack;
                    tab.canGoForward = canForward;
                    if (tabRegistry.current() == tab) updateNavigation();
                }));
            browser.setOnUrlChangedListener(url -> runOnUiThread(() -> {
                if (destroyed || tabRegistry.find(tab.id) != tab || url == null) return;
                tab.url = url;
                if (tabRegistry.current() == tab && !address.hasFocus()) address.setText(url);
                if (!tab.privateMode) saveRegularTabs();
            }));
            browser.setOnTitleChangedListener(name -> runOnUiThread(() -> {
                if (destroyed || tabRegistry.find(tab.id) != tab) return;
                tab.title = name == null ? "" : name;
                if (tabRegistry.current() == tab) updateNavigation();
                if (!tab.privateMode) saveRegularTabs();
            }));
            browser.loadUrl(tab.url);
            return browser;
        } catch (RuntimeException | LinkageError error) {
            browser.close();
            throw error;
        }
    }

    private void activateCurrentTab() {
        if (!chromiumReady || destroyed) return;
        TabRegistry.Tab current = tabRegistry.current();
        if (current == null) return;
        if (engine != null) {
            engine.setPageVisible(false);
            engine.getSurfaceContainer().setVisibility(View.GONE);
        }
        CefriumBrowser active = liveBrowsers.get(current.id);
        try {
            if (active == null) {
                active = createEngineFor(current);
                liveBrowsers.put(current.id, active);
            }
            engine = active;
            engine.getSurfaceContainer().setVisibility(View.VISIBLE);
            engine.setPageVisible(true);
            engine.getSurfaceContainer().bringToFront();
            browserRoot.bringToFront();
            updateNavigation();
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Tab engine failed to open", error);
            engine = null;
            title.setText("タブの起動に失敗");
            Toast.makeText(this, "このタブを開けませんでした", Toast.LENGTH_LONG).show();
        }
    }

    private void updateNavigation() {
        if (back == null || forward == null) return;
        TabRegistry.Tab tab = tabRegistry.current();
        boolean ready = tab != null && engine != null;
        back.setEnabled(ready && tab.canGoBack);
        forward.setEnabled(ready && tab.canGoForward);
        if (tabsButton != null) tabsButton.setText("□ " + tabRegistry.all().size());
        if (tab != null) {
            title.setText((tab.privateMode ? "非公開 · " : "") + tab.displayTitle());
            if (!address.hasFocus()) {
                address.setText(tab.url.equals("about:blank") ? "" : tab.url);
            }
        }
    }

    private void loadAddress() {
        if (engine == null) return;
        String parsed = NavigationPolicy.parse(address.getText().toString());
        if (parsed == null) {
            Toast.makeText(this, "HTTP(S) URLか検索語を入力", Toast.LENGTH_LONG).show();
            return;
        }
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
        TabRegistry.Tab current = tabRegistry.current();
        if (current != null) current.url = parsed;
        engine.loadUrl(parsed);
        saveRegularTabs();
    }

    private void showSites() {
        new AlertDialog.Builder(this)
            .setTitle("同一サイトでスクロール比較")
            .setItems(SITE_NAMES, (dialog, item) -> {
                address.clearFocus();
                address.setText(SITE_URLS[item]);
                if (engine != null) {
                    TabRegistry.Tab current = tabRegistry.current();
                    if (current != null) current.url = SITE_URLS[item];
                    engine.loadUrl(SITE_URLS[item]);
                    saveRegularTabs();
                }
            })
            .setNegativeButton("閉じる", null).show();
    }

    private void showTabs() {
        if (!chromiumReady) return;
        int count = tabRegistry.all().size();
        String[] items = new String[count + 2];
        for (int i = 0; i < count; i++) {
            TabRegistry.Tab tab = tabRegistry.all().get(i);
            items[i] = (tab == tabRegistry.current() ? "● " : "　")
                + (tab.privateMode ? "🔒 " : "") + tab.displayTitle();
        }
        items[count] = "＋ 新しい通常タブ";
        items[count + 1] = "＋ 新しい非公開タブ";
        new AlertDialog.Builder(this).setTitle("タブ")
            .setItems(items, (d, index) -> {
                if (index == count) createTab("about:blank", false);
                else if (index == count + 1) createTab("about:blank", true);
                else selectTab(tabRegistry.all().get(index).id);
            })
            .setNegativeButton("閉じる", null).show();
    }

    private void closeCurrentTab() {
        TabRegistry.Tab current = tabRegistry.current();
        if (current == null) return;
        new AlertDialog.Builder(this).setTitle("このタブを閉じる？")
            .setMessage("入力中のフォームや再生状態は復元できません。")
            .setPositiveButton("閉じる", (d, which) -> {
                CefriumBrowser closing = liveBrowsers.remove(current.id);
                if (closing != null) {
                    if (engine == closing) engine = null;
                    closing.close();
                }
                tabRegistry.close(current.id);
                if (tabRegistry.current() == null) tabRegistry.add("about:blank", false);
                activateCurrentTab();
                saveRegularTabs();
            })
            .setNegativeButton("キャンセル", null).show();
    }

    private void findInPage() {
        if (engine == null) return;
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("ページ内検索");
        new AlertDialog.Builder(this).setTitle("ページ内検索")
            .setView(query).setPositiveButton("検索", (dialog, which) -> {
                CefriumBrowser browser = engine;
                if (browser != null && !query.getText().toString().isEmpty()) {
                    browser.find(query.getText().toString(), true, false, false);
                }
            }).setNegativeButton("キャンセル", null).show();
    }

    private void showMenu() {
        String[] commands = {
            "新しいタブ", "新しい非公開タブ", "現在のタブを閉じる",
            "ページ内検索", "ページを共有", "比較サイト", "実装状況・注意点"
        };
        new AlertDialog.Builder(this).setTitle("Velocity Chromium")
            .setItems(commands, (dialog, index) -> {
                switch (index) {
                    case 0: createTab("about:blank", false); break;
                    case 1: createTab("about:blank", true); break;
                    case 2: closeCurrentTab(); break;
                    case 3: findInPage(); break;
                    case 4:
                        TabRegistry.Tab tab = tabRegistry.current();
                        if (tab != null && isPersistable(tab.url)
                                && !tab.url.equals("about:blank")) {
                            Intent share = new Intent(Intent.ACTION_SEND);
                            share.setType("text/plain");
                            share.putExtra(Intent.EXTRA_TEXT, tab.url);
                            startActivity(Intent.createChooser(share, "共有"));
                        }
                        break;
                    case 5: showSites(); break;
                    case 6: showInfo(); break;
                    default: break;
                }
            }).show();
    }

    private void showInfo() {
        new AlertDialog.Builder(this).setTitle("Velocity Chromium 実験版")
            .setMessage("エンジン：Chromium（Cefrium 0.13.0）\n\n" +
                "これはAndroid標準WebViewではありません。\n\n" +
                "Gecko/WPE版とは別アプリとしてインストールできます。\n\n" +
                "通常・非公開タブ、URL復元、戻る・進む、検索と共有を追加しました。\n\n" +
                "Gecko拡張、Velocity Block、ダウンロードUI、権限UI、" +
                "実機セキュリティ検証は未移植です。重要なアカウントにはログインしないでください。")
            .setPositiveButton("OK", null).show();
    }

    @Override protected void onPause() {
        for (CefriumBrowser browser : liveBrowsers.values()) browser.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        for (CefriumBrowser browser : liveBrowsers.values()) browser.onResume();
    }

    @Override protected void onStop() {
        saveRegularTabs();
        super.onStop();
    }

    @Override @Deprecated protected void onActivityResult(
        int requestCode, int resultCode, Intent data
    ) {
        for (CefriumBrowser browser : liveBrowsers.values()) {
            if (browser.onActivityResult(requestCode, resultCode, data)) return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override @Deprecated public void onBackPressed() {
        if (engine != null && engine.canGoBack()) engine.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        for (CefriumBrowser browser : liveBrowsers.values()) browser.close();
        liveBrowsers.clear();
        engine = null;
        super.onDestroy();
    }
}
