package dev.velocity.chromium;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.View;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Trace;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import android.widget.PopupWindow;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.cefrium.Cefrium;
import com.cefrium.CefriumBrowser;
import org.chromium.base.CommandLine;
import org.chromium.content.browser.ChildProcessCreationParamsImpl;

/**
 * A separate Cefrium browser test bed with persistent regular tab metadata,
 * isolated private tabs, navigation, and native Surface rendering.
 *
 * No remote JavaScript instrumentation is introduced.
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
    private String lastSavedTabSnapshot;
    private CefriumBrowser engine;
    private LinearLayout browserRoot;
    private FrameLayout browserContent;
    private FrameLayout homeOverlay;
    private View browserHeader;
    private View browserFooter;
    private LinearLayout toolbarActions;
    private View progressLine;
    private PopupWindow undoPopup;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable deferredTabSave = this::saveRegularTabs;
    private Runnable undoTimeout;
    private boolean chromiumReady;
    private boolean externalBenchmarkRequested;
    private boolean activityResumed;
    private long activityCreateStartedAtMs;
    private StartupResourceProbe.Snapshot activityStartedResources;
    private long uiPreparedAtMs;
    private volatile boolean blockDomainsEnabled;
    private boolean cosmeticEnabled;
    private volatile Set<String> normalSiteExemptions = Collections.emptySet();
    private volatile Set<String> privateSiteExemptions = Collections.emptySet();
    private NetworkRules networkRules = NetworkRules.parse("");
    private Button tabsButton;
    private EditText address;
    private TextView title;
    private Button back;
    private Button forward;
    private boolean destroyed;
    private static final String TAG = "VelocityCefStartup";
    private boolean syntheticSmokeRequested;
    private long syntheticLoadStartedAtMs = -1L;
    private long syntheticPaintStartedAtMs = -1L;

    // Deliberately restrained palette. No novelty gradients, false neon
    // borders, or extra Material dependencies on the critical launch path.
    private static final int CANVAS = Color.rgb(14, 19, 24);
    private static final int SURFACE = Color.rgb(23, 30, 37);
    private static final int ELEVATED = Color.rgb(37, 46, 55);
    private static final int BORDER = Color.rgb(56, 67, 77);
    private static final int PRIMARY = Color.rgb(238, 244, 245);
    private static final int SECONDARY = Color.rgb(161, 176, 187);
    private static final int ACCENT = Color.rgb(107, 221, 176);

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) background.setStroke(dp(1), stroke);
        return background;
    }

    private Button makeButton(String label, String hint, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        b.setTextSize(21);
        b.setContentDescription(hint);
        b.setTextColor(PRIMARY);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25FFFFFF),
            rounded(Color.TRANSPARENT, 14, Color.TRANSPARENT), null));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(sp);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    private void focusAddress() {
        address.requestFocus();
        address.selectAll();
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
            .showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
    }

    private void switchTabBy(int direction) {
        TabRegistry.Tab current = tabRegistry.current();
        if (current == null || address.hasFocus()) return;
        int index = tabRegistry.all().indexOf(current);
        int next = index + direction;
        if (next >= 0 && next < tabRegistry.all().size()) {
            selectTab(tabRegistry.all().get(next).id);
        }
    }

    @Override public void onCreate(Bundle state) {
        syntheticSmokeRequested = getIntent() != null
            && getIntent().getBooleanExtra("cefrium_render_smoke", false);
        externalBenchmarkRequested = getResources().getBoolean(R.bool.velocity_page_benchmark)
            && getIntent() != null && PageTimingProbe.allows(
                getIntent().getStringExtra("velocity_benchmark_url"));
        activityCreateStartedAtMs = SystemClock.elapsedRealtime();
        activityStartedResources = StartupResourceProbe.capture();
        Trace.endAsyncSection("Velocity CEF provider startup", 6421);
        super.onCreate(state);
        // Benchmark-only next hypothesis: initialize at Activity creation,
        // then create the Surface after layout. Background process wakes stay lazy.
        if (getResources().getBoolean(R.bool.velocity_early_engine_init)
                && !Cefrium.initialize(this)) {
            throw new IllegalStateException("Early Activity engine initialization failed");
        }
        getWindow().setStatusBarColor(CANVAS);
        getWindow().setNavigationBarColor(CANVAS);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);

        LinearLayout root = new LinearLayout(this);
        browserRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.setBackgroundColor(CANVAS);
        root.setFitsSystemWindows(true);
        setContentView(root);

        // Title is retained as a state/accessibility sink. The old always-on
        // top bar wasted viewport height and added no useful browser control.
        LinearLayout header = new LinearLayout(this);
        header.setVisibility(View.GONE);
        title = text("Velocity · Cefrium", 13, SECONDARY, false);
        header.addView(title);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout content = new FrameLayout(this);
        browserContent = content;
        browserHeader = header;
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        // The browser starts with a native, zero-network home, not a remote
        // news page. It also makes the current tab's about:blank useful.
        buildHome(content);

        // The page remains the focus. One compact chrome surface is anchored
        // at the bottom, within thumb reach on tall phones.
        LinearLayout footer = new LinearLayout(this);
        browserFooter = footer;
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(10), dp(5), dp(10), dp(5));
        footer.setBackgroundColor(CANVAS);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        progressLine = new View(this);
        progressLine.setBackground(rounded(ACCENT, 2, Color.TRANSPARENT));
        progressLine.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(2));
        progressLp.setMargins(dp(9), 0, dp(9), dp(5));
        footer.addView(progressLine, progressLp);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(12), 0, dp(5), 0);
        addressRow.setBackground(rounded(SURFACE, 17, BORDER));
        LinearLayout.LayoutParams addressLp = new LinearLayout.LayoutParams(-1, dp(51));
        footer.addView(addressRow, addressLp);

        TextView searchMark = text("⌕", 27, ACCENT, true);
        searchMark.setContentDescription("URLと検索");
        addressRow.addView(searchMark, new LinearLayout.LayoutParams(dp(31), -1));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(PRIMARY);
        address.setHintTextColor(SECONDARY);
        address.setHint("検索またはアドレスを入力");
        address.setTextSize(15);
        address.setPadding(0, 0, dp(5), 0);
        address.setBackgroundColor(Color.TRANSPARENT);
        address.setSelectAllOnFocus(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                loadAddress();
                return true;
            }
            return false;
        });
        addressRow.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button go = makeButton("→", "入力したURLを開く", this::loadAddress);
        go.setTextColor(ACCENT);
        addressRow.addView(go, new LinearLayout.LayoutParams(dp(44), dp(44)));

        toolbarActions = new LinearLayout(this);
        toolbarActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolbarLp = new LinearLayout.LayoutParams(-1, dp(49));
        toolbarLp.topMargin = dp(4);
        footer.addView(toolbarActions, toolbarLp);

        back = makeButton("‹", "前のページへ戻る", () -> {
            if (engine != null && engine.canGoBack()) engine.goBack();
        });
        forward = makeButton("›", "次のページへ進む", () -> {
            if (engine != null) engine.goForward();
        });
        Button reload = makeButton("↻", "ページを再読み込み", () -> {
            if (engine != null) engine.reload();
        });
        tabsButton = makeButton("□", "タブ一覧", this::showTabs);
        Button menu = makeButton("⋮", "ブラウザメニュー", this::showMenu);
        toolbarActions.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbarActions.addView(forward, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbarActions.addView(reload, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbarActions.addView(tabsButton, new LinearLayout.LayoutParams(0, dp(48), 1.25f));
        toolbarActions.addView(menu, new LinearLayout.LayoutParams(0, dp(48), 1));

        // Focus is the only source of truth for editing mode. When the IME
        // appears, drop the duplicate toolbar instead of hiding the URL input.
        address.setOnFocusChangeListener((v, hasFocus) -> {
            toolbarActions.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
            go.setText(hasFocus ? "→" : "↗");
            if (hasFocus) address.selectAll();
        });

        // Horizontal swiping on the unfocused address field switches tabs.
        // Once focused, Android's native EditText owns all gestures for
        // selection and cursor manipulation without interception.
        GestureDetector addressGesture = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }
                @Override public boolean onSingleTapUp(MotionEvent event) {
                    focusAddress();
                    return true;
                }
                @Override public boolean onFling(MotionEvent down, MotionEvent up,
                    float velocityX, float velocityY) {
                    if (down == null || up == null) return false;
                    float displacement = up.getX() - down.getX();
                    if (Math.abs(displacement) < dp(80)
                            || Math.abs(velocityX) < dp(250)
                            || Math.abs(displacement) < Math.abs(up.getY() - down.getY())) {
                        return false;
                    }
                    switchTabBy(displacement < 0 ? 1 : -1);
                    return true;
                }
            });
        address.setOnTouchListener((v, event) ->
            address.hasFocus() ? false : addressGesture.onTouchEvent(event));
        root.requestFocus();

        updateNavigation();
        address.clearFocus();
        // The SDK's WindowAndroid requires an attached Activity view. The
        // Android 16 Preview uses the measured attach path by default; a saved
        // user choice overrides it. Other Android versions retain posted startup.
        // Debug smoke requests keep explicit control of post versus attach.
        uiPreparedAtMs = SystemClock.elapsedRealtime();
        boolean explicitlyEnabled = getResources().getBoolean(R.bool.velocity_benchmark_fast_attach)
            || (!syntheticSmokeRequested && isFastAttachEnabled());
        boolean attachRequested = getIntent() != null &&
            "attach".equals(getIntent().getStringExtra("cefrium_start_strategy"));
        if (getResources().getBoolean(R.bool.velocity_early_engine_init)) {
            Log.i(TAG, "CEFStartupStrategy strategy=predraw");
            root.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        root.getViewTreeObserver().removeOnPreDrawListener(this);
                        if (!destroyed) startChromium();
                        return true;
                    }
                });
        } else if (StartupModePolicy.onAttach(
                explicitlyEnabled, syntheticSmokeRequested,
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                attachRequested)) {
            Log.i(TAG, "CEFStartupStrategy strategy=attach");
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    view.removeOnAttachStateChangeListener(this);
                    if (!destroyed) startChromium();
                }

                @Override public void onViewDetachedFromWindow(View view) {}
            });
        } else {
            Log.i(TAG, "CEFStartupStrategy strategy=post");
            root.post(this::startChromium);
        }
    }

    /** A native home never fetches remote data during cold start. */
    private void buildHome(FrameLayout parent) {
        FrameLayout overlay = new FrameLayout(this);
        homeOverlay = overlay;
        overlay.setBackgroundColor(CANVAS);
        parent.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        overlay.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(26), dp(24), dp(24));
        scroll.addView(body);

        TextView wordmark = text("Velocity", 32, PRIMARY, true);
        body.addView(wordmark, new LinearLayout.LayoutParams(-1, dp(62)));

        TextView sub = text("軽く、速く、あなたのウェブへ。", 14, SECONDARY, false);
        body.addView(sub, new LinearLayout.LayoutParams(-1, dp(35)));

        Button search = makeButton("⌕    検索またはURLを入力", "アドレスバーに入力", this::focusAddress);
        search.setTextSize(15);
        search.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        search.setPadding(dp(20), 0, dp(12), 0);
        search.setBackground(rounded(SURFACE, 16, BORDER));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(56));
        searchLp.topMargin = dp(32);
        body.addView(search, searchLp);

        TextView caption = text("クイックアクセス", 13, SECONDARY, true);
        LinearLayout.LayoutParams captionLp = new LinearLayout.LayoutParams(-1, dp(45));
        captionLp.topMargin = dp(23);
        body.addView(caption, captionLp);

        for (int startIndex = 0; startIndex < SITE_NAMES.length; startIndex += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            body.addView(row, new LinearLayout.LayoutParams(-1, dp(65)));
            for (int col = 0; col < 2 && startIndex + col < SITE_NAMES.length; col++) {
                final int index = startIndex + col;
                Button shortcut = makeButton(SITE_NAMES[index], SITE_NAMES[index] + " を開く",
                    () -> openHomeLink(index));
                shortcut.setTextSize(13);
                shortcut.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF),
                    rounded(SURFACE, 14, BORDER), null));
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(55), 1f);
                if (col != 0) cell.leftMargin = dp(9);
                row.addView(shortcut, cell);
            }
        }
        TextView privacy = text("ホーム画面自体はオフラインで使えます。", 12, SECONDARY, false);
        LinearLayout.LayoutParams privacyLp = new LinearLayout.LayoutParams(-1, dp(45));
        privacyLp.topMargin = dp(10);
        body.addView(privacy, privacyLp);
    }

    private void openHomeLink(int index) {
        if (index < 0 || index >= SITE_URLS.length || engine == null) return;
        TabRegistry.Tab current = tabRegistry.current();
        if (current == null) return;
        current.url = SITE_URLS[index];
        updateHomeState();
        engine.loadUrl(current.url);
        saveRegularTabs();
    }

    private void updateHomeState() {
        if (homeOverlay == null) return;
        TabRegistry.Tab current = tabRegistry.current();
        boolean showHome = current != null && "about:blank".equals(current.url);
        homeOverlay.setVisibility(showHome ? View.VISIBLE : View.GONE);
        if (showHome) homeOverlay.bringToFront();
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


    /** Initialize the engine, then verify isolated renderer services before a browser. */
    private void configureRendererLauncher() {
        Cefrium.setupProcess(this);
        // SDK 0.16 no longer initializes the native engine in its provider.
        // Launcher selection is configured during initialization, so complete
        // it before inspecting the selected sandboxed service prefix.
        if (!Cefrium.initialize(this)) {
            throw new IllegalStateException("Cefrium native initialization failed");
        }
        if (!CommandLine.isInitialized()) CommandLine.init(null);
        // Cefrium 0.16 retains native Android 17 renderer entrypoints. Do not
        // force javaless renderers off: doing so would mask SDK integration
        // regressions and prevent testing the current Android process model.
        try {
            PackageManager manager = getPackageManager();
            // A single package-manager IPC returns app metadata and all
            // services. The previous verifier did 40-42 individual IPCs.
            PackageInfo packageInfo = manager.getPackageInfo(
                getPackageName(), PackageManager.GET_META_DATA | PackageManager.GET_SERVICES);
            ApplicationInfo info = packageInfo.applicationInfo;
            if (info == null || packageInfo.services == null) {
                throw new IllegalStateException("Renderer manifest was not resolved");
            }
            Map<String, ServiceInfo> services = new HashMap<>();
            for (ServiceInfo service : packageInfo.services) {
                if (service.name == null || services.put(service.name, service) != null) {
                    throw new IllegalStateException("Duplicate or invalid renderer service");
                }
            }
            String expected = info.metaData == null ? null : info.metaData.getString(
                "org.chromium.content.browser.SANDBOXED_SERVICES_NAME");
            int count = info.metaData == null ? 0 : info.metaData.getInt(
                "org.chromium.content.browser.NUM_SANDBOXED_SERVICES", 0);
            String selected = ChildProcessCreationParamsImpl.getSandboxedServicesName();
            boolean javalessDeclared = info.metaData != null
                && info.metaData.getBoolean("com.cefrium.JAVALESS_RENDERERS", false);
            String nativePrefix = info.metaData == null ? null : info.metaData.getString(
                "org.chromium.content.browser.NATIVE_SANDBOXED_SERVICES_NAME");
            boolean usingNative = RendererPoolSelection.usingNative(
                Build.VERSION.SDK_INT, javalessDeclared, expected,
                nativePrefix, selected, count);
            for (int slot = 0; slot < count; slot++) {
                ServiceInfo service = services.get(expected + slot);
                if (service == null
                        || (service.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0
                        || service.exported || !service.enabled) {
                    throw new IllegalStateException("Unsafe renderer service slot " + slot);
                }
                if (slot == 0 && (service.flags & ServiceInfo.FLAG_USE_APP_ZYGOTE) == 0) {
                    throw new IllegalStateException("Renderer service 0 has no app zygote");
                }
            }
            Log.i(TAG, "Verified Java renderer pool: 40 isolated services; API "
                + Build.VERSION.SDK_INT + "; selected=" + selected);
            // 0.16's Gradle plugin declares native-only child renderer
            // services for compileSdk >= 37. Test the actual packaged
            // package-manager view when running on Android 17+.
            if (usingNative) {
                for (int slot = 0; slot < 2; slot++) {
                    ServiceInfo nativeService = services.get(
                        RendererPoolSelection.NATIVE + slot);
                    if (nativeService == null
                            || (nativeService.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0
                            || nativeService.exported || !nativeService.enabled) {
                        throw new IllegalStateException(
                            "Unsafe Android 17 native renderer slot " + slot);
                    }
                    if (slot == 0
                            && (nativeService.flags & ServiceInfo.FLAG_USE_APP_ZYGOTE) == 0) {
                        throw new IllegalStateException(
                            "Android 17 native renderer slot 0 lacks app zygote");
                    }
                }
                Log.i(TAG, "Verified Android 17 native renderer services: 2 isolated slots");
            }
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IllegalStateException("Missing Chromium renderer service", failure);
        }
    }

    /** Renderer pool must be verified before the first Cefrium browser. */
    private void startChromium() {
        if (destroyed || isFinishing()) return;
        try {
            long layoutReadyAt = SystemClock.elapsedRealtime();
            Trace.beginSection("Velocity CEF renderer pool validation");
            try {
                configureRendererLauncher();
            } finally {
                Trace.endSection();
            }
            long launcherReadyAt = SystemClock.elapsedRealtime();
            Trace.beginSection("Velocity blocking rules load");
            try {
                loadNetworkRules();
            } finally {
                Trace.endSection();
            }
            // Cefrium 0.16 no longer adds YouTube heuristics with the first
            // filter list. Explicitly load these SDK-owned rules before the
            // first navigation, including restored YouTube sessions.
            Cefrium.loadYouTubeAdRules();
            long rulesReadyAt = SystemClock.elapsedRealtime();
            chromiumReady = true;
            Trace.beginSection("Velocity CEF create Surface");
            try {
                restoreRegularTabs();
                // Do not fetch a remote homepage at cold startup.
                if (tabRegistry.current() == null) tabRegistry.add("about:blank", false);
                String benchmarkUrl = getIntent() == null ? null
                    : getIntent().getStringExtra("velocity_benchmark_url");
                if (getResources().getBoolean(R.bool.velocity_page_benchmark)
                        && benchmarkUrl != null
                        && (PageTimingProbe.allows(benchmarkUrl) || benchmarkUrl.matches("http://127\\.0\\.0\\.1:8765/fixture\\?trial=[a-zA-Z0-9_-]{1,80}"))) {
                    tabRegistry.current().url = benchmarkUrl;
                }
                activateCurrentTab();
            } finally {
                Trace.endSection();
            }
            long engineReadyAt = SystemClock.elapsedRealtime();
            StartupResourceProbe.Snapshot engineReadyResources = StartupResourceProbe.capture();
            // Counters are process-wide; no private URLs or profile contents.
            // -1 means unavailable, NEVER "zero page faults" or "zero I/O".
            Log.i(TAG, "CEFStartupResource "
                + StartupResourceProbe.describe(
                    ChromiumDiagnosticApplication.mainProcessStartedResources,
                    activityStartedResources, "provider")
                + " " + StartupResourceProbe.describe(
                    activityStartedResources, engineReadyResources, "activity")
                + " " + StartupResourceProbe.describe(
                    ChromiumDiagnosticApplication.mainProcessStartedResources,
                    engineReadyResources, "total"));
            // Timings are numeric only: never collect private URLs or profile data.
            // Native Surface creation != first page paint.
            long attachedAt = ChromiumDiagnosticApplication.mainProcessAttachedAtMs;
            long providerMs = attachedAt > 0 && activityCreateStartedAtMs >= attachedAt
                ? activityCreateStartedAtMs - attachedAt : -1;
            Log.i(TAG, "CEFStartupTiming provider_ms=" + providerMs
                + " ui_build_ms=" + (uiPreparedAtMs - activityCreateStartedAtMs)
                + " ui_wait_ms=" + (layoutReadyAt - uiPreparedAtMs)
                + " ui_ms=" + (layoutReadyAt - activityCreateStartedAtMs)
                + " pool_ms=" + (launcherReadyAt - layoutReadyAt)
                + " rules_ms=" + (rulesReadyAt - launcherReadyAt)
                + " create_ms=" + (engineReadyAt - rulesReadyAt)
                + " total_ms=" + (engineReadyAt - activityCreateStartedAtMs));
            if (engine != null) {
                // Retain the original startup smoke-test assertion, and emit
                // it only after the real native browser Surface was created.
                Log.i(TAG, "CEF surface created and page navigation started");
                if (syntheticSmokeRequested) {
                    // The local test page must run JavaScript after DOM
                    // is ready before the renderer emits the special title.
                    // This is not a first-paint metric. Do not expose a
                    // page-to-native JavaScript bridge for smoke tests.
                    final CefriumBrowser probe = engine;
                    mainHandler.postDelayed(() -> {
                        if (!destroyed && engine == probe) {
                            syntheticLoadStartedAtMs = SystemClock.elapsedRealtime();
                            syntheticPaintStartedAtMs = syntheticLoadStartedAtMs;
                            probe.loadHtml(SyntheticRenderFixture.html());
                        }
                    }, 450);
                }
            }
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

    private void loadNetworkRules() {
        NetworkRules.Builder builder = new NetworkRules.Builder();
        try {
            for (String file : new String[] {
                "velocity-block/rules.txt", "velocity-block/pgl.txt"
            }) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        getAssets().open(file), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) builder.acceptLine(line);
                }
            }
            networkRules = builder.build();
            SharedPreferences blocking = getSharedPreferences(TAB_PREFS, MODE_PRIVATE);
            blockDomainsEnabled = blocking.getBoolean("block_domains", true);
            cosmeticEnabled = blocking.getBoolean("cosmetic_elements", true);
            normalSiteExemptions = SiteBlockPolicy.normalize(
                blocking.getStringSet("block_site_exceptions", Collections.emptySet()));
            // Private exceptions are deliberately not saved on disk.
            privateSiteExemptions = Collections.emptySet();
            Log.i(TAG, "Velocity network-only rules loaded: " + networkRules.blockedDomains());
        } catch (Exception error) {
            // Fail open for content but leave the feature visibly disabled.
            blockDomainsEnabled = false;
            cosmeticEnabled = false;
            Log.w(TAG, "Cefrium domain rules unavailable", error);
        }
    }

    private void toggleNetworkRules() {
        blockDomainsEnabled = !blockDomainsEnabled;
        getSharedPreferences(TAB_PREFS, MODE_PRIVATE).edit()
            .putBoolean("block_domains", blockDomainsEnabled).apply();
        // Existing per-browser interceptors read the volatile global flag;
        // toggling does not require replacing callback registrations.
        Toast.makeText(this,
            "追加ドメイン遮断: " + (blockDomainsEnabled ? "ON" : "OFF")
                + "（内蔵EasyListとは別）", Toast.LENGTH_LONG).show();
    }

    private boolean cosmeticAllowed(TabRegistry.Tab tab) {
        String host = SiteBlockPolicy.hostOf(tab.url);
        Set<String> exemptions = tab.privateMode ? privateSiteExemptions : normalSiteExemptions;
        // Network blocking and cosmetic hiding can be toggled independently.
        return cosmeticEnabled && (host == null || !exemptions.contains(host));
    }

    private void refreshCosmetics(CefriumBrowser browser, TabRegistry.Tab tab) {
        // JS is a reviewed, constant stylesheet injection. No network code,
        // remote filter scriptlets or native bridge calls are accepted.
        if (cosmeticAllowed(tab)) browser.evaluateJavaScript(CosmeticRules.injectScript());
        else browser.evaluateJavaScript(CosmeticRules.removeScript());
    }

    private void toggleCosmetics() {
        cosmeticEnabled = !cosmeticEnabled;
        getSharedPreferences(TAB_PREFS, MODE_PRIVATE).edit()
            .putBoolean("cosmetic_elements", cosmeticEnabled).apply();
        for (TabRegistry.Tab tab : tabRegistry.all()) {
            CefriumBrowser browser = liveBrowsers.get(tab.id);
            if (browser != null) refreshCosmetics(browser, tab);
        }
        Toast.makeText(this, cosmeticEnabled ? "広告枠の非表示 ON" : "広告枠の非表示 OFF",
            Toast.LENGTH_SHORT).show();
    }

    private void toggleCurrentSiteException() {
        TabRegistry.Tab tab = tabRegistry.current();
        if (tab == null) return;
        String host = SiteBlockPolicy.hostOf(tab.url);
        if (host == null) {
            Toast.makeText(this, "HTTP(S)ページでのみ設定できるよ", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> next;
        if (tab.privateMode) {
            next = SiteBlockPolicy.toggle(privateSiteExemptions, host);
            privateSiteExemptions = next;
        } else {
            next = SiteBlockPolicy.toggle(normalSiteExemptions, host);
            normalSiteExemptions = next;
            getSharedPreferences(TAB_PREFS, MODE_PRIVATE).edit()
                .putStringSet("block_site_exceptions", new HashSet<>(next)).apply();
        }
        CefriumBrowser currentBrowser = liveBrowsers.get(tab.id);
        if (currentBrowser != null) refreshCosmetics(currentBrowser, tab);
        Toast.makeText(this, host + (next.contains(host)
                ? " では追加ブロックを停止" : " で追加ブロックを再開")
                + "（次の通信から適用）", Toast.LENGTH_LONG).show();
    }

    /** Preserve normal tab URLs only. A private session is never serialized. */
    private void restoreRegularTabs() {
        SharedPreferences prefs = getSharedPreferences(TAB_PREFS, MODE_PRIVATE);
        try {
            String rawSnapshot = prefs.getString("state", "{}");
            JSONObject state = new JSONObject(rawSnapshot);
            lastSavedTabSnapshot = rawSnapshot;
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

    /**
     * A single page may report several URL/title updates during redirects.
     * Coalesce disk-bound SharedPreferences writes on the main thread.
     * Tab CRUD and Activity.onStop still flush immediately.
     */
    private void scheduleRegularTabsSave() {
        mainHandler.removeCallbacks(deferredTabSave);
        mainHandler.postDelayed(deferredTabSave, 350L);
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
            String snapshot = new JSONObject()
                .put("normal", normal)
                .put("selected", selected).toString();
            // No preference commit or observer notifications if the data is
            // identical to the last disk-persisted normal-tab snapshot.
            if (!snapshot.equals(lastSavedTabSnapshot)) {
                getSharedPreferences(TAB_PREFS, MODE_PRIVATE).edit()
                    .putString("state", snapshot).apply();
                lastSavedTabSnapshot = snapshot;
            }
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
            // Per-browser origin snapshot avoids carrying a site's exemption
            // across the earliest subrequests of a top-level navigation.
            // Parse the top-level host once per navigation, not for every
            // resource request on the network thread (which can be hundreds
            // per modern page). A redirect updates the snapshot immediately.
            final AtomicReference<String> currentOriginHost = new AtomicReference<>(
                SiteBlockPolicy.hostOf(tab.url));
            browser.setRequestInterceptor((url, method, isMainFrame) -> {
                if (isMainFrame) currentOriginHost.set(SiteBlockPolicy.hostOf(url));
                return blockDomainsEnabled && SiteBlockPolicy.shouldBlockForTopHost(
                    networkRules, url, isMainFrame, currentOriginHost.get(),
                    tab.privateMode ? privateSiteExemptions : normalSiteExemptions);
            });
            browser.setOnLoadingStateChangedListener((loading, canBack, canForward) ->
                runOnUiThread(() -> {
                    if (destroyed || tabRegistry.find(tab.id) != tab) return;
                    tab.loading = loading;
                    tab.canGoBack = canBack;
                    tab.canGoForward = canForward;
                    if (!loading) {
                        refreshCosmetics(browser, tab);
                        if (externalBenchmarkRequested) {
                            browser.evaluateJavaScript(PageTimingProbe.script());
                        }
                    }
                    if (tabRegistry.current() == tab) {
                        if (progressLine != null) progressLine.setVisibility(
                            loading ? View.VISIBLE : View.INVISIBLE);
                        updateNavigation();
                    }
                }));
            browser.setOnUrlChangedListener(url -> runOnUiThread(() -> {
                if (destroyed || tabRegistry.find(tab.id) != tab || url == null) return;
                tab.url = url;
                currentOriginHost.set(SiteBlockPolicy.hostOf(url));
                if (tabRegistry.current() == tab) {
                    if (!address.hasFocus()) address.setText(url);
                    updateHomeState();
                }
                if (!tab.privateMode) scheduleRegularTabsSave();
            }));
            browser.setOnTitleChangedListener(name -> runOnUiThread(() -> {
                if (destroyed || tabRegistry.find(tab.id) != tab) return;
                if (externalBenchmarkRequested) {
                    int[] timings = PageTimingProbe.parse(name);
                    if (timings != null) {
                        externalBenchmarkRequested = false;
                        Log.i(TAG, "CEFExternalTiming fcp_ms=" + timings[0]
                            + " dom_ms=" + timings[1] + " ready_ms=" + timings[2]
                            + " process_ready_ms=" + (SystemClock.elapsedRealtime()
                                - ChromiumDiagnosticApplication.mainProcessAttachedAtMs));
                    }
                }
                tab.title = name == null ? "" : name;
                if (syntheticSmokeRequested && syntheticLoadStartedAtMs > 0
                        && SyntheticRenderFixture.READY_TITLE.equals(name)) {
                    long domReadyMs = SystemClock.elapsedRealtime()
                        - syntheticLoadStartedAtMs;
                    Log.i(TAG, "CEF synthetic document title observed");
                    Log.i(TAG, "CEFPageTiming synthetic_dom_ready_ms=" + domReadyMs);
                    syntheticLoadStartedAtMs = -1L;
                }
                if (syntheticSmokeRequested && syntheticPaintStartedAtMs > 0
                        && name != null && name.startsWith(
                            SyntheticRenderFixture.FCP_TITLE_PREFIX)) {
                    String timing = name.substring(
                        SyntheticRenderFixture.FCP_TITLE_PREFIX.length());
                    // Ignore untrusted / malformed page-title metrics.
                    if (timing.matches("[0-9]{1,6}")) {
                        int fcpMs = Integer.parseInt(timing);
                        Log.i(TAG, "CEFPageTiming synthetic_fcp_browser_ms=" + fcpMs
                            + " synthetic_fcp_observed_ms="
                            + (SystemClock.elapsedRealtime() - syntheticPaintStartedAtMs));
                        syntheticPaintStartedAtMs = -1L;
                    }
                }
                if (tabRegistry.current() == tab) updateNavigation();
                if (!tab.privateMode) scheduleRegularTabsSave();
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
        CefriumBrowser oldEngine = engine;
        CefriumBrowser active = liveBrowsers.get(current.id);
        if (oldEngine != null && oldEngine != active) {
            // Cefrium.onPause() also suspends media, unlike page visibility
            // alone. Inactive tabs must never resume animation/audio on
            // Activity.onResume -- only the foreground engine is resumed.
            oldEngine.onPause();
            oldEngine.setPageVisible(false);
            oldEngine.getSurfaceContainer().setVisibility(View.GONE);
        }
        try {
            if (active == null) {
                active = createEngineFor(current);
                liveBrowsers.put(current.id, active);
            }
            engine = active;
            engine.getSurfaceContainer().setVisibility(View.VISIBLE);
            engine.setPageVisible(true);
            if (activityResumed && oldEngine != active) engine.onResume();
            engine.getSurfaceContainer().bringToFront();
            browserRoot.bringToFront();
            updateHomeState();
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
            updateHomeState();
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
        hideKeyboard();
        address.clearFocus();
        TabRegistry.Tab current = tabRegistry.current();
        if (current != null) current.url = parsed;
        updateHomeState();
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
                    updateHomeState();
                    engine.loadUrl(SITE_URLS[item]);
                    saveRegularTabs();
                }
            })
            .setNegativeButton("閉じる", null).show();
    }

    /** Native compact bottom sheet instead of a full-screen, unlabeled list. */
    private Dialog bottomSheet(LinearLayout panel) {
        Dialog sheet = new Dialog(this);
        panel.setPadding(dp(18), dp(17), dp(18), dp(20));
        panel.setBackground(rounded(SURFACE, 24, BORDER));
        sheet.setContentView(panel);
        Window window = sheet.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.33f);
        }
        sheet.show();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(-1, -2);
        }
        return sheet;
    }

    private LinearLayout sheetPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        return panel;
    }

    private void showTabs() {
        if (!chromiumReady) return;
        LinearLayout panel = sheetPanel();
        TextView heading = text("タブ  " + tabRegistry.all().size(), 19, PRIMARY, true);
        heading.setPadding(dp(6), 0, 0, dp(15));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(49)));

        // The dialog is created before buttons receive listeners. The holder
        // avoids retaining a stale Activity outside the dialog lifetime.
        final Dialog[] shown = new Dialog[1];
        Button addNormal = makeButton("＋ 新しいタブ", "通常タブを新規作成", () -> {
            shown[0].dismiss();
            createTab("about:blank", false);
        });
        addNormal.setTextSize(14);
        addNormal.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addNormal.setBackground(rounded(ELEVATED, 13, BORDER));
        actions.addView(addNormal, new LinearLayout.LayoutParams(0, dp(44), 1));

        Button addPrivate = makeButton("◇ 非公開", "非公開タブを新規作成", () -> {
            shown[0].dismiss();
            createTab("about:blank", true);
        });
        addPrivate.setTextSize(14);
        addPrivate.setTextColor(ACCENT);
        LinearLayout.LayoutParams privateLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        privateLp.leftMargin = dp(9);
        actions.addView(addPrivate, privateLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * .56f);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1,
            Math.min(maxHeight, dp(Math.max(74, tabRegistry.all().size() * 73))));
        scrollLp.topMargin = dp(10);
        panel.addView(scroll, scrollLp);
        LinearLayout rows = sheetPanel();
        scroll.addView(rows);

        for (TabRegistry.Tab tab : tabRegistry.all()) {
            LinearLayout tabRow = new LinearLayout(this);
            tabRow.setGravity(Gravity.CENTER_VERTICAL);
            tabRow.setPadding(dp(12), dp(4), dp(3), dp(4));
            boolean active = tab == tabRegistry.current();
            tabRow.setBackground(rounded(active ? ELEVATED : SURFACE, 14,
                active ? ACCENT : Color.TRANSPARENT));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(67));
            rowLp.bottomMargin = dp(5);
            rows.addView(tabRow, rowLp);

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setGravity(Gravity.CENTER_VERTICAL);
            String display = tab.displayTitle();
            TextView label = text((tab.privateMode ? "◇  " : "") + display, 14,
                PRIMARY, active);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(label, new LinearLayout.LayoutParams(-1, dp(25)));
            TextView subtitle = text(tab.privateMode ? "非公開" : tab.url, 11,
                SECONDARY, false);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(20)));
            tabRow.addView(details, new LinearLayout.LayoutParams(0, -1, 1));
            details.setContentDescription("タブを選択: " + display);
            details.setOnClickListener(v -> {
                shown[0].dismiss();
                selectTab(tab.id);
            });

            Button close = makeButton("×", "このタブを閉じる", () -> {
                shown[0].dismiss();
                closeTabDirect(tab.id);
            });
            close.setTextColor(SECONDARY);
            tabRow.addView(close, new LinearLayout.LayoutParams(dp(43), dp(45)));
        }
        shown[0] = bottomSheet(panel);
    }

    /** Closing is a single operation; reopening navigates back to the URL. */
    private void closeTabDirect(String id) {
        TabRegistry.Tab victim = tabRegistry.find(id);
        if (victim == null) return;
        boolean wasCurrent = victim == tabRegistry.current();
        CefriumBrowser closing = liveBrowsers.remove(id);
        if (closing != null) {
            if (engine == closing) engine = null;
            closing.close();
            // The SDK may keep empty renderer processes around for reuse.
            // Reap only zero-frame children; live renderers are unaffected.
            CefriumBrowser.reapUnusedRenderers();
        }
        tabRegistry.close(id);
        if (tabRegistry.current() == null) tabRegistry.add("about:blank", false);
        if (wasCurrent) activateCurrentTab();
        else updateNavigation();
        saveRegularTabs();
        showUndo(victim);
    }

    /** Five-second, non-modal undo. Does not claim to recover form/JS state. */
    private void showUndo(TabRegistry.Tab closed) {
        if (undoTimeout != null) mainHandler.removeCallbacks(undoTimeout);
        if (undoPopup != null) undoPopup.dismiss();

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(6), dp(13), dp(6));
        bar.setBackground(rounded(ELEVATED, 16, BORDER));
        TextView message = text("タブを閉じた", 14, PRIMARY, false);
        bar.addView(message, new LinearLayout.LayoutParams(0, -1, 1));
        Button undo = makeButton("元に戻す", "URLを再び開く", () -> {
            if (undoPopup != null) undoPopup.dismiss();
            undoPopup = null;
            if (undoTimeout != null) mainHandler.removeCallbacks(undoTimeout);
            createTab(closed.url, closed.privateMode);
        });
        undo.setTextSize(14);
        undo.setTextColor(ACCENT);
        bar.addView(undo, new LinearLayout.LayoutParams(dp(95), -1));
        PopupWindow popup = new PopupWindow(bar, -1, dp(58), false);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(8));
        undoPopup = popup;
        browserRoot.post(() -> {
            if (destroyed || undoPopup != popup) return;
            popup.showAtLocation(browserRoot, Gravity.BOTTOM, dp(12),
                browserFooter.getHeight() + dp(9));
        });
        undoTimeout = () -> {
            if (undoPopup == popup) {
                popup.dismiss();
                undoPopup = null;
            }
        };
        mainHandler.postDelayed(undoTimeout, 5000L);
    }

    private void closeCurrentTab() {
        TabRegistry.Tab current = tabRegistry.current();
        if (current != null) closeTabDirect(current.id);
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

    private boolean isFastAttachEnabled() {
        return getSharedPreferences(StartupModePolicy.PREFS, MODE_PRIVATE).getBoolean(
            StartupModePolicy.KEY_FAST_ATTACH, StartupModePolicy.defaultOnAttach(
                Build.VERSION.SDK_INT, getResources().getBoolean(R.bool.velocity_page_benchmark)));
    }

    private void toggleExperimentalFastStartup() {
        SharedPreferences prefs = getSharedPreferences(StartupModePolicy.PREFS, MODE_PRIVATE);
        boolean enabled = !isFastAttachEnabled();
        prefs.edit().putBoolean(StartupModePolicy.KEY_FAST_ATTACH, enabled).apply();
        Toast.makeText(this, enabled
            ? "高速起動の実験モードを有効化。次回の起動から適用します。起動できなければ診断画面で解除。"
            : "通常の安全な起動方法に戻します。次回の起動から適用します。",
            Toast.LENGTH_LONG).show();
    }

    private void openUpdatesPage() {
        // A plain HTTPS page, never silent APK installation or downloads from
        // unauthenticated third-party hosts.
        Intent browser = new Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse("https://github.com/linkalls/velocity-browser/actions/workflows/chromium-013-signed-preview.yml"));
        try {
            startActivity(browser);
        } catch (RuntimeException error) {
            Toast.makeText(this, "GitHubの更新ページを開けませんでした", Toast.LENGTH_LONG).show();
        }
    }

    private void showMenu() {
        String[] labels = {
            "新しいタブ", "新しい非公開タブ", "現在のタブを閉じる",
            "ページ内検索", "ページを共有", "比較サイト",
            "広告・トラッカードメイン遮断", "広告枠の非表示を切替",
            "このサイトの追加遮断を切替", "高速起動（実験・次回から）",
            "更新APKを確認", "起動診断", "このブラウザについて"
        };
        String[] glyphs = {
            "+", "◇", "×", "⌕", "↗", "▤", "◎", "▧", "◉", "⚡", "↓", "⚙", "ⓘ"
        };
        String[] hints = {
            "通常のブラウジング", "終了時にURLを保存しない", "5秒以内ならURLを再度開ける",
            "現在のページで探す", "Androidの共有メニューへ", "表示・スクロールを比較",
            blockDomainsEnabled ? "追加遮断 ON" : "追加遮断 OFF",
            cosmeticEnabled ? "要素非表示 ON" : "要素非表示 OFF",
            "サイト別の例外設定（非公開モードは保存しない）",
            isFastAttachEnabled()
                    ? "ON · 失敗時は起動診断から解除できる" : "OFF · 標準起動を使用中",
            "GitHub Actionsの署名済みビルド", "クラッシュ・起動状態を確認",
            "Cefrium 0.16.0 · 開発版"
        };
        LinearLayout panel = sheetPanel();
        TextView header = text("Velocity", 21, PRIMARY, true);
        header.setPadding(dp(6), 0, 0, dp(12));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(45)));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,
            Math.min(dp(510), (int) (getResources().getDisplayMetrics().heightPixels * .68f))));
        panel.addView(scroll);
        LinearLayout items = sheetPanel();
        scroll.addView(items);
        final Dialog[] shown = new Dialog[1];

        for (int i = 0; i < labels.length; i++) {
            final int action = i;
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(9), 0, dp(6), 0);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF),
                rounded(SURFACE, 13, Color.TRANSPARENT), null));
            items.addView(item, new LinearLayout.LayoutParams(-1, dp(62)));
            TextView icon = text(glyphs[i], 23, i == 7 ? ACCENT : SECONDARY, false);
            icon.setGravity(Gravity.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(dp(43), -1));
            LinearLayout detail = sheetPanel();
            detail.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = text(labels[i], 15, PRIMARY, false);
            TextView sub = text(hints[i], 11, SECONDARY, false);
            detail.addView(label, new LinearLayout.LayoutParams(-1, dp(24)));
            detail.addView(sub, new LinearLayout.LayoutParams(-1, dp(18)));
            item.addView(detail, new LinearLayout.LayoutParams(0, -1, 1));
            item.setContentDescription(labels[i] + "、" + hints[i]);
            item.setOnClickListener(v -> {
                shown[0].dismiss();
                runMenuAction(action);
            });
        }
        shown[0] = bottomSheet(panel);
    }

    private void runMenuAction(int action) {
        switch (action) {
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
            case 6: toggleNetworkRules(); break;
            case 7: toggleCosmetics(); break;
            case 8: toggleCurrentSiteException(); break;
            case 9: toggleExperimentalFastStartup(); break;
            case 10: openUpdatesPage(); break;
            case 11: startActivity(new Intent(this, StartupDiagnosticActivity.class)); break;
            case 12: showInfo(); break;
            default: break;
        }
    }

    private void showInfo() {
        new AlertDialog.Builder(this).setTitle("Velocity Chromium 実験版")
            .setMessage("エンジン：Chromium（Cefrium 0.16.0）\n\n" +
                "これはAndroid標準WebViewではありません。\n\n" +
                "Gecko/WPE版とは別アプリとしてインストールできます。\n\n" +
                "通常・非公開タブ、URL復元、戻る・進む、検索と共有を追加しました。\n\n" +
                "ドメイン遮断・限定的なCSS要素非表示を移植。Gecko拡張・完全なフィルター互換・ダウンロードUI・権限UI、" +
                "実機セキュリティ検証は未移植です。重要なアカウントにはログインしないでください。")
            .setPositiveButton("OK", null).show();
    }

    @Override protected void onPause() {
        activityResumed = false;
        if (engine != null) engine.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (engine != null) engine.onResume();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (chromiumReady && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Reclaim orphaned renderers without discarding any live WebContents.
            CefriumBrowser.reapUnusedRenderers();
        }
    }

    @Override protected void onStop() {
        mainHandler.removeCallbacks(deferredTabSave);
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
        if (address.hasFocus()) {
            hideKeyboard();
            address.clearFocus();
        } else if (engine != null && engine.canGoBack()) {
            engine.goBack();
        } else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(deferredTabSave);
        if (undoTimeout != null) mainHandler.removeCallbacks(undoTimeout);
        if (undoPopup != null) undoPopup.dismiss();
        for (CefriumBrowser browser : liveBrowsers.values()) browser.close();
        liveBrowsers.clear();
        engine = null;
        if (chromiumReady) CefriumBrowser.reapUnusedRenderers();
        super.onDestroy();
    }
}
