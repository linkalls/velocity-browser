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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.util.Base64;
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
import android.view.PixelCopy;
import android.view.SurfaceView;
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
import android.widget.Switch;
import android.widget.PopupWindow;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private TabPreviewStore previewStore;
    private OnBackInvokedCallback gestureBackCallback;
    private final Map<String, CefriumBrowser> liveBrowsers = new LinkedHashMap<>(16, .75f, true);
    private static final String TAB_PREFS = "cefrium_tabs_v1";
    // Unlimited tab metadata, bounded live native renderers on memory-limited phones.
    private static final int MAX_RESIDENT_BROWSERS = 6;
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
    /** Decode CDP JPEGs off the UI thread; never log thumbnail bytes or URLs. */
    private final ExecutorService thumbnailDecodeExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, AtomicBoolean> thumbnailRequestsInFlight = new HashMap<>();
    private final Map<String, ArrayList<ImageView>> pendingThumbnailViews = new HashMap<>();
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
    private LinearLayout addressBar;
    private TextView title;
    private Button back;
    private Button forward;
    private boolean destroyed;
    private static final String TAG = "VelocityCefStartup";
    private boolean syntheticSmokeRequested;
    private boolean previewFixtureRequested;
    private boolean previewFixtureSheetShown;
    private long syntheticLoadStartedAtMs = -1L;
    private long syntheticPaintStartedAtMs = -1L;

    // Deliberately restrained palette. No novelty gradients, false neon
    // borders, or extra Material dependencies on the critical launch path.
    private static final int CANVAS = Color.rgb(12, 16, 22);
    private static final int SURFACE = Color.rgb(24, 31, 42);
    private static final int ELEVATED = Color.rgb(37, 47, 61);
    private static final int BORDER = Color.rgb(58, 71, 85);
    private static final int PRIMARY = Color.rgb(241, 246, 250);
    private static final int SECONDARY = Color.rgb(167, 181, 194);
    private static final int ACCENT = Color.rgb(111, 220, 187);

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

    /** A consistent icon-first toolbar: monochrome 24dp vector artwork. */
    private Button iconButton(int icon, String hint, Runnable action) {
        Button button = makeButton("", hint, action);
        android.graphics.drawable.Drawable drawable = getDrawable(icon);
        if (drawable != null) {
            drawable.setBounds(0, 0, dp(23), dp(23));
            button.setCompoundDrawables(null, drawable, null, null);
        }
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private ImageView iconView(int resource, int color) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(resource);
        icon.setColorFilter(color);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        return icon;
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
        // Address-bar swipes never jump across normal/private browsing contexts.
        List<TabRegistry.Tab> modeTabs = tabRegistry.tabsForMode(current.privateMode);
        int index = modeTabs.indexOf(current);
        int next = index + direction;
        if (next >= 0 && next < modeTabs.size()) {
            selectTab(modeTabs.get(next).id);
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
        previewStore = new TabPreviewStore(this);
        // Android 13+ gestures no longer reliably invoke Activity.onBackPressed().
        if (Build.VERSION.SDK_INT >= 33) {
            gestureBackCallback = this::handleBrowserBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, gestureBackCallback);
        }
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
        addressBar = addressRow;
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(12), 0, dp(5), 0);
        addressRow.setBackground(rounded(SURFACE, 17, BORDER));
        LinearLayout.LayoutParams addressLp = new LinearLayout.LayoutParams(-1, dp(51));
        footer.addView(addressRow, addressLp);

        ImageView searchMark = iconView(R.drawable.ic_browser_search, ACCENT);
        searchMark.setContentDescription("URLと検索");
        addressRow.addView(searchMark, new LinearLayout.LayoutParams(dp(31), dp(44)));

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
            if (!view.hasFocus()) return false;
            if (actionId == EditorInfo.IME_ACTION_GO ||
                event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                loadAddress();
                return true;
            }
            return false;
        });
        addressRow.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button go = iconButton(R.drawable.ic_browser_forward, "入力したURLを開く", this::loadAddress);
        go.setTextColor(ACCENT);
        addressRow.addView(go, new LinearLayout.LayoutParams(dp(44), dp(44)));

        toolbarActions = new LinearLayout(this);
        toolbarActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolbarLp = new LinearLayout.LayoutParams(-1, dp(49));
        toolbarLp.topMargin = dp(4);
        footer.addView(toolbarActions, toolbarLp);

        back = iconButton(R.drawable.ic_browser_back, "前のページへ戻る", () -> {
            if (engine != null && !tabRegistry.isStartupHomeCurrent() && engine.canGoBack()) engine.goBack();
        });
        forward = iconButton(R.drawable.ic_browser_forward, "次のページへ進む", () -> {
            if (engine != null && !tabRegistry.isStartupHomeCurrent()) engine.goForward();
        });
        Button reload = iconButton(R.drawable.ic_browser_refresh, "ページを再読み込み", () -> {
            if (engine != null) engine.reload();
        });
        tabsButton = iconButton(R.drawable.ic_browser_tabs, "タブ一覧", this::showTabs);
        tabsButton.setTextSize(12);
        Button menu = iconButton(R.drawable.ic_browser_menu, "ブラウザメニュー", this::showMenu);
        toolbarActions.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbarActions.addView(forward, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbarActions.addView(reload, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbarActions.addView(tabsButton, new LinearLayout.LayoutParams(0, dp(48), 1.25f));
        toolbarActions.addView(menu, new LinearLayout.LayoutParams(0, dp(48), 1));

        // Focus is the only source of truth for editing mode. When the IME
        // appears, drop the duplicate toolbar instead of hiding the URL input.
        address.setOnFocusChangeListener((v, hasFocus) -> {
            toolbarActions.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
            go.setContentDescription(hasFocus ? "検索・ページを開く" : "アドレスを開く");
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

        // Screenshot-only UI audit hook in DEBUG builds. This never ships in a
        // signed release or handles remote instructions in the production app.
        String audit = getIntent() == null ? null :
            getIntent().getStringExtra("velocity_debug_ui_audit");
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && ("settings".equals(audit) || "tabs".equals(audit))) {
            root.postDelayed(() -> {
                if (destroyed || isFinishing()) return;
                if ("settings".equals(audit)) {
                    showSettings();
                } else if (chromiumReady) {
                    // Additional empty tab metadata demonstrates 2-row grid,
                    // no network access and no private-page screenshot data.
                    for (int i = 0; i < 3; i++) {
                        tabRegistry.add("about:blank", false);
                    }
                    showTabs(false);
                }
            }, 9000L);
        }

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
            // A recreated Activity can receive an already attached decor tree.
            // Do not wait for an attachment event that has already happened.
            if (root.isAttachedToWindow()) {
                startChromium();
            } else {
                root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View view) {
                        view.removeOnAttachStateChangeListener(this);
                        if (!destroyed) startChromium();
                    }

                    @Override public void onViewDetachedFromWindow(View view) {}
                });
            }
        } else {
            Log.i(TAG, "CEFStartupStrategy strategy=post");
            root.post(this::startChromium);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Re-launching an existing task must retain the in-memory WebContents.
        // Do not navigate back to Home and re-create the active page.
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

        TextView eyebrow = text("YOUR BROWSER · YOUR SPACE", 11, ACCENT, true);
        eyebrow.setLetterSpacing(0.09f);
        body.addView(eyebrow, new LinearLayout.LayoutParams(-1, dp(30)));

        TextView wordmark = text("Velocity", 36, PRIMARY, true);
        wordmark.setLetterSpacing(-0.025f);
        body.addView(wordmark, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView sub = text("必要なものへ、最短距離で。", 14, SECONDARY, false);
        body.addView(sub, new LinearLayout.LayoutParams(-1, dp(32)));

        Button search = makeButton("⌕    検索またはURLを入力", "アドレスバーに入力", this::focusAddress);
        search.setTextSize(15);
        search.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        search.setPadding(dp(20), 0, dp(12), 0);
        search.setBackground(rounded(SURFACE, 16, BORDER));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(56));
        searchLp.topMargin = dp(32);
        body.addView(search, searchLp);

        TextView caption = text("クイックアクセス", 14, PRIMARY, true);
        LinearLayout.LayoutParams captionLp = new LinearLayout.LayoutParams(-1, dp(45));
        captionLp.topMargin = dp(23);
        body.addView(caption, captionLp);

        final int[] shortcutIcons = {
            R.drawable.ic_browser_globe, R.drawable.ic_browser_search,
            R.drawable.ic_browser_info, R.drawable.ic_browser_globe,
            R.drawable.ic_browser_play, R.drawable.ic_browser_folder
        };
        for (int startIndex = 0; startIndex < SITE_NAMES.length; startIndex += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            body.addView(row, new LinearLayout.LayoutParams(-1, dp(65)));
            for (int col = 0; col < 2 && startIndex + col < SITE_NAMES.length; col++) {
                final int index = startIndex + col;
                Button shortcut = makeButton(SITE_NAMES[index], SITE_NAMES[index] + " を開く",
                    () -> openHomeLink(index));
                shortcut.setTextSize(13);
                shortcut.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                shortcut.setPadding(dp(12), 0, dp(8), 0);
                shortcut.setCompoundDrawablesWithIntrinsicBounds(
                    shortcutIcons[index], 0, 0, 0);
                shortcut.setCompoundDrawableTintList(ColorStateList.valueOf(ACCENT));
                shortcut.setCompoundDrawablePadding(dp(10));
                shortcut.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF),
                    rounded(SURFACE, 15, BORDER), null));
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
        if (index < 0 || index >= SITE_URLS.length) return;
        navigateCurrentTab(SITE_URLS[index]);
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
                // Restoring metadata must not automatically navigate the last
                // selected Google/search/other remote page when opening the app.
                tabRegistry.selectStartupHome();
                // DEBUG-ONLY screenshot test: a distinctive offline page
                // with blue/orange/green regions. No internet, cookies or
                // private content. CI must verify a REAL captured JPEG.
                boolean debugAudit = (getApplicationInfo().flags
                    & ApplicationInfo.FLAG_DEBUGGABLE) != 0 && getIntent() != null;
                String auditMode = debugAudit
                    ? getIntent().getStringExtra("velocity_debug_ui_audit") : null;
                boolean debugPreview = "preview".equals(auditMode);
                boolean debugRealSite = "real-site".equals(auditMode);
                if (debugRealSite) {
                    // Optional diagnostic only: public website, separate emulator
                    // profile, never any user cookies, passwords, or private tabs.
                    tabRegistry.add("https://news.yahoo.co.jp/", false);
                }
                if (debugPreview) {
                    previewFixtureRequested = true;
                    String previewFixture = "<!doctype html><html><head>"
                        + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<title>Velocity preview fixture</title></head>"
                        + "<body style='margin:0;background:#1369cf;color:white;font:28px sans-serif'>"
                        + "<div style='height:180px;background:#f38328'>ORANGE TEST REGION</div>"
                        + "<div style='height:260px;background:#189e74'>GREEN TEST REGION</div>"
                        + "<h1>REAL CHROMIUM PIXELS</h1></body></html>";
                    tabRegistry.add("data:text/html;charset=utf-8,"
                        + android.net.Uri.encode(previewFixture), false);
                }
                String benchmarkUrl = getIntent() == null ? null
                    : getIntent().getStringExtra("velocity_benchmark_url");
                if (getResources().getBoolean(R.bool.velocity_page_benchmark)
                        && benchmarkUrl != null
                        && (PageTimingProbe.allows(benchmarkUrl) || benchmarkUrl.matches("http://127\\.0\\.0\\.1:8765/fixture\\?trial=[a-zA-Z0-9_-]{1,80}"))) {
                    if (tabRegistry.add(benchmarkUrl, false) == null) {
                        throw new IllegalStateException("No room for controlled benchmark page");
                    }
                }
                activateCurrentTab();
                if (debugRealSite) {
                    // Capture the native tab sheet even if site ads keep loading.
                    mainHandler.postDelayed(() -> {
                        if (!destroyed && !isFinishing()) showTabs(false);
                    }, 16000L);
                }
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
            for (int i = 0; i < normal.length(); i++) {
                JSONObject item = normal.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "");
                if (!isPersistable(url)) continue;
                TabRegistry.Tab tab = tabRegistry.restoreRegularTab(
                    url, item.optBoolean("home", false), item.optString("id", null),
                    item.optString("group", null));
                if (tab == null) break;
                tab.title = item.optString("title", "");
                tab.desktopMode = item.optBoolean("desktop", false);
                tab.zoomLevel = Math.max(-3, Math.min(4, item.optDouble("zoom", 0)));
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
                    .put("id", tab.id)
                    .put("group", tab.group == null ? "" : tab.group)
                    .put("desktop", tab.desktopMode)
                    .put("zoom", tab.zoomLevel)
                    .put("home", tab.startupHome)
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
        TabRegistry.Tab previous = tabRegistry.current();
        if (previous != null && !previous.id.equals(id) && !previous.privateMode
                && !previous.startupHome) {
            // Request the old compositor frame while the old tab is still
            // foreground. Once hidden, PixelCopy cannot safely capture it.
            captureVisibleTabPreview(previous, null);
        }
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
            applyBrowserSettings(browser);
            applyTabDisplayPreferences(browser, tab);
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
                        // A loading-state callback can happen before the native
                        // Surface has painted its first frame. Capture after paint
                        // has had a chance to finish, not synchronously here.
                        if (tabRegistry.current() == tab && !tab.privateMode) {
                            mainHandler.postDelayed(() -> captureVisibleTabPreview(tab, null), 650L);
                            mainHandler.postDelayed(() -> captureVisibleTabPreview(tab, null), 1800L);
                        }
                        browser.setDarkMode(getSharedPreferences(TAB_PREFS, MODE_PRIVATE)
                            .getBoolean("force_dark", false));
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
                // Reusing a UUID across navigations must not show a previous
                // site's screenshot while a new page is still loading.
                if (!url.equals(tab.url) && !tab.privateMode) {
                    previewStore.invalidatePreview(tab.id);
                    // Old CDP responses must not block a new navigation's
                    // snapshot or overwrite new live card ImageViews.
                    thumbnailRequestsInFlight.remove(tab.id);
                    pendingThumbnailViews.remove(tab.id);
                }
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
                // Some real sites keep loading advertisements indefinitely.
                // Their document title is available long before loading=false;
                // schedule one best-effort capture even during those loads.
                if (!tab.privateMode && tabRegistry.current() == tab
                        && name != null && !name.trim().isEmpty()) {
                    mainHandler.postDelayed(() -> captureVisibleTabPreview(tab, null), 1100L);
                }
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
            // A restored tab is instantiated only when the user explicitly
            // selects it. Chromium PageState also restores back/forward history;
            // a dead renderer cannot preserve live JS/DOM state.
            byte[] savedNavigation = tab.privateMode || tab.startupHome ? null
                : previewStore.loadNavigationState(tab.id);
            if (savedNavigation != null && savedNavigation.length > 0) {
                browser.restoreNavigationState(savedNavigation);
            } else {
                browser.loadUrl(tab.url);
            }
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
            quietBrowserWhenHidden(oldEngine);
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
            if (activityResumed && oldEngine != active) {
                // Reopen a previously paused WebContents, without a reload.
                foregroundBrowserWithoutStealingAudio(engine);
            }
            engine.getSurfaceContainer().bringToFront();
            browserRoot.bringToFront();
            updateHomeState();
            updateNavigation();
            trimInactiveBrowsers(current.id);
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Tab engine failed to open", error);
            engine = null;
            title.setText("タブの起動に失敗");
            Toast.makeText(this, "このタブを開けませんでした", Toast.LENGTH_LONG).show();
        }
    }

    /** Hibernate oldest inactive WebContents while preserving visible tab cards. */
    private void trimInactiveBrowsers(String currentId) {
        while (liveBrowsers.size() > MAX_RESIDENT_BROWSERS) {
            String oldestId = null;
            for (String id : liveBrowsers.keySet()) {
                if (!id.equals(currentId)) { oldestId = id; break; }
            }
            if (oldestId == null) break;
            CefriumBrowser idle = liveBrowsers.remove(oldestId);
            TabRegistry.Tab tab = tabRegistry.find(oldestId);
            if (idle == null) continue;
            if (tab != null && !tab.privateMode && !tab.startupHome) {
                try {
                    previewStore.saveNavigationState(tab.id, idle.serializeNavigationState());
                } catch (RuntimeException error) {
                    Log.w(TAG, "Failed to hibernate tab navigation", error);
                }
            }
            idle.close();
        }
        CefriumBrowser.reapUnusedRenderers();
    }

    /** A per-tab desktop mode using Chromium's UA override; no fake viewport injection. */
    private static final String DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36";

    private void applyTabDisplayPreferences(CefriumBrowser browser, TabRegistry.Tab tab) {
        if (tab.desktopMode) browser.setUserAgentOverride(DESKTOP_USER_AGENT);
        browser.setZoomLevel(tab.zoomLevel);
    }

    private void toggleDesktopMode() {
        TabRegistry.Tab tab = tabRegistry.current();
        if (tab == null || tab.startupHome || engine == null) return;
        tab.desktopMode = !tab.desktopMode;
        try {
            // CDP treats empty userAgent as clearing the custom UA override.
            engine.setUserAgentOverride(tab.desktopMode ? DESKTOP_USER_AGENT : "");
            // Changing a User-Agent cannot retroactively update an already loaded document.
            engine.reload();
            if (!tab.privateMode) saveRegularTabs();
            Toast.makeText(this, tab.desktopMode ? "PC版サイトをリクエスト" : "スマホ版サイトをリクエスト",
                Toast.LENGTH_SHORT).show();
        } catch (RuntimeException exception) {
            tab.desktopMode = !tab.desktopMode;
            Toast.makeText(this, "表示モードの変更に失敗", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "User-Agent override failed", exception);
        }
    }

    private void showPageZoom() {
        TabRegistry.Tab tab = tabRegistry.current();
        if (tab == null || engine == null || tab.startupHome) return;
        final double[] zoom = { -1.22, 0, 1.22, 2.22 };
        final String[] labels = { "80%", "100%", "125%", "150%" };
        new AlertDialog.Builder(this).setTitle("ページの表示倍率")
            .setSingleChoiceItems(labels, Math.abs(tab.zoomLevel) < 0.05 ? 1 : -1,
                (dialog, which) -> {
                    tab.zoomLevel = zoom[which];
                    engine.setZoomLevel(tab.zoomLevel);
                    if (!tab.privateMode) saveRegularTabs();
                    dialog.dismiss();
                }).setNegativeButton("キャンセル", null).show();
    }

    private void copyCurrentPageUrl() {
        TabRegistry.Tab tab = tabRegistry.current();
        if (tab == null || !isPersistable(tab.url) || "about:blank".equals(tab.url)) return;
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", tab.url));
            Toast.makeText(this, "URLをコピーした", Toast.LENGTH_SHORT).show();
        }
    }

    /** Share the current URL through Android without touching the Chromium Surface. */
    private void shareCurrentPage() {
        TabRegistry.Tab tab = tabRegistry.current();
        if (tab == null || !isPersistable(tab.url) || "about:blank".equals(tab.url)) {
            Toast.makeText(this, "共有できるページがないよ", Toast.LENGTH_SHORT).show();
            return;
        }
        final String url = tab.url;
        final Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, url);
        send.putExtra(Intent.EXTRA_SUBJECT, tab.displayTitle());
        try {
            // Let the menu sheet finish dismissing before the system chooser takes focus.
            browserRoot.post(() -> {
                if (destroyed || isFinishing()) return;
                try {
                    startActivity(Intent.createChooser(send, "ページを共有"));
                } catch (RuntimeException error) {
                    Log.e(TAG, "Share chooser unavailable", error);
                    Toast.makeText(this, "共有メニューを開けなかった", Toast.LENGTH_LONG).show();
                }
            });
        } catch (RuntimeException error) {
            Log.e(TAG, "Share intent error", error);
        }
    }

    private void updateNavigation() {
        if (back == null || forward == null) return;
        TabRegistry.Tab tab = tabRegistry.current();
        boolean ready = tab != null && engine != null;
        back.setEnabled(ready && !tab.startupHome && tab.canGoBack);
        forward.setEnabled(ready && !tab.startupHome && tab.canGoForward);
        if (tabsButton != null && tab != null) {
            int count = tabRegistry.countForMode(tab.privateMode);
            tabsButton.setText((tab.privateMode ? "◇ " : "") + count);
            tabsButton.setContentDescription(tab.privateMode ? "非公開タブ一覧" : "通常タブ一覧");
        }
        if (addressBar != null && tab != null) {
            addressBar.setBackground(rounded(tab.privateMode ? ELEVATED : SURFACE, 17,
                tab.privateMode ? SECONDARY : BORDER));
        }
        if (tab != null) {
            updateHomeState();
            title.setText((tab.privateMode ? "非公開 · " : "") + tab.displayTitle());
            if (!address.hasFocus()) {
                address.setText(tab.url.equals("about:blank") ? "" : tab.url);
            }
        }
    }

    private boolean navigateCurrentTab(String url) {
        TabRegistry.Tab current = tabRegistry.current();
        if (engine == null || current == null) return false;
        if (current.startupHome) {
            // Keep a reusable offline home and every saved page. Navigation from
            // home is a new user tab, never an automatic restored-page load.
            if (tabRegistry.add(url, false) == null) {
                Toast.makeText(this, "タブがいっぱいです。不要なタブを閉じてから開いてください。",
                    Toast.LENGTH_LONG).show();
                return false;
            }
            activateCurrentTab();
        } else {
            // Direct address/search navigation changes tab.url before Cefrium
            // emits onUrlChanged. Invalidate here too: otherwise the previous
            // page remains in the memory cache and shows in the tab sheet.
            if (!current.privateMode && !url.equals(current.url)) {
                previewStore.invalidatePreview(current.id);
                thumbnailRequestsInFlight.remove(current.id);
                pendingThumbnailViews.remove(current.id);
            }
            current.url = url;
            updateHomeState();
            engine.loadUrl(url);
        }
        saveRegularTabs();
        return true;
    }

    private void loadAddress() {
        String parsed = NavigationPolicy.parse(address.getText().toString());
        if (parsed == null) {
            Toast.makeText(this, "HTTP(S) URLか検索語を入力", Toast.LENGTH_LONG).show();
            return;
        }
        if (!navigateCurrentTab(parsed)) return;
        hideKeyboard();
        address.clearFocus();
        updateNavigation();
    }

    private void showSites() {
        new AlertDialog.Builder(this)
            .setTitle("同一サイトでスクロール比較")
            .setItems(SITE_NAMES, (dialog, item) -> {
                address.clearFocus();
                navigateCurrentTab(SITE_URLS[item]);
            })
            .setNegativeButton("閉じる", null).show();
    }

    /**
     * A ScrollView that measures to its CONTENT height until the screen limit.
     * Unlike hard-coded heights, this remains correct at 125%-200% font scale,
     * in landscape, and when the keyboard changes available window height.
     */
    private final class BoundedScrollView extends ScrollView {
        BoundedScrollView() {
            super(ChromiumTestActivity.this);
            setFillViewport(false);
            setVerticalScrollBarEnabled(false);
            setClipToPadding(false);
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int activityHeight = browserRoot == null ? 0 : browserRoot.getHeight();
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int available = activityHeight > 0 ? activityHeight : screenHeight;
            int maximum = BrowserUiLayout.sheetMaxHeightPx(
                Math.max(1, available - dp(160)), available, dp(130));
            // Always measure with a bounded AT_MOST: an unconstrained
            // ScrollView within a wrap-content Dialog can otherwise consume
            // the whole display, leaving blank space below its actual rows.
            int cap = maximum;
            if (View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
                cap = Math.min(cap, View.MeasureSpec.getSize(heightMeasureSpec));
            }
            super.onMeasure(widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(Math.max(dp(96), cap), View.MeasureSpec.AT_MOST));
        }
    }

    /** Native compact bottom sheet instead of a full-screen, unlabeled list. */
    private Dialog bottomSheet(LinearLayout panel) {
        Dialog sheet = new Dialog(this);
        panel.setPadding(dp(BrowserUiLayout.SHEET_SIDE_PADDING_DP), dp(7),
            dp(BrowserUiLayout.SHEET_SIDE_PADDING_DP), dp(15));
        View dragHandle = new View(this);
        dragHandle.setBackground(rounded(BORDER, 3, Color.TRANSPARENT));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(12);
        panel.addView(dragHandle, 0, handleLp);
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
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setLayout(-1, -2);
        }
        return sheet;
    }

    private LinearLayout sheetPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        return panel;
    }

    /** Find the actual Chromium Surface, not its empty FrameLayout shell. */
    private static SurfaceView findBrowserSurface(View root) {
        if (root instanceof SurfaceView) {
            SurfaceView surface = (SurfaceView) root;
            if (surface.getHolder().getSurface() != null
                    && surface.getHolder().getSurface().isValid()
                    && surface.getWidth() > 40 && surface.getHeight() > 40) {
                return surface;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                SurfaceView found = findBrowserSurface(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Reject an empty/uninitialized compositor buffer. Pages may be mostly
     * white or dark; only reject exact uniform output, not legitimate themes.
     */
    private static boolean isUsableTabBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() < 40 || bitmap.getHeight() < 40) return false;
        try {
            int baseline = bitmap.getPixel(bitmap.getWidth() / 2,
                bitmap.getHeight() / 2);
            for (int x = 1; x <= 6; x++) {
                for (int y = 1; y <= 6; y++) {
                    int sample = bitmap.getPixel(bitmap.getWidth() * x / 7,
                        bitmap.getHeight() * y / 7);
                    if (sample != baseline) return true;
                }
            }
        } catch (IllegalStateException incompatibleBitmap) {
            // HARDWARE bitmaps are not directly pixel-readable. The fallback
            // uses an explicit software ARGB_8888 bitmap for PixelCopy.
            return false;
        }
        return false;
    }

    /**
     * Cefrium's captureBitmap uses PixelCopy for Surface mode, but can return
     * null before its internal surface is ready. Direct PixelCopy of the actual
     * SurfaceView is an independent path and yields a smaller preview bitmap.
     */
    private void captureWithAndroidPixelCopy(CefriumBrowser browser, TabRegistry.Tab tab,
                                             java.util.function.Consumer<Bitmap> done) {
        // This fallback reads Android window pixels, which may contain a
        // different tab. Never allow a capture after the user switched tabs.
        if (tab.privateMode || tabRegistry.current() != tab || engine != browser) {
            done.accept(null);
            return;
        }
        FrameLayout container = browser.getSurfaceContainer();
        SurfaceView surface = findBrowserSurface(container);
        if (surface == null) surface = findBrowserSurface(browserContent);
        // Do NOT PixelCopy the Activity window after a dialog is shown: its
        // pixels may include private content, dim scrims or a different tab.
        if (surface == null) {
            Log.w(TAG, "TABSHOT no valid native Chromium SurfaceView");
            done.accept(null);
            return;
        }
        View sourceView = surface;
        if (sourceView.getWidth() < 40 || sourceView.getHeight() < 40
                || !sourceView.isAttachedToWindow()) {
            Log.w(TAG, "Preview PixelCopy source not ready");
            done.accept(null);
            return;
        }
        int width = Math.min(420, sourceView.getWidth());
        int height = Math.max(70, Math.min(800,
            Math.round(width * (sourceView.getHeight() / (float) sourceView.getWidth()))));
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            if (surface != null) {
                PixelCopy.request(surface, result, status -> {
                    if (status == PixelCopy.SUCCESS && isUsableTabBitmap(result)) {
                        done.accept(result);
                    } else {
                        Log.w(TAG, "Surface PixelCopy failed: " + status);
                        result.recycle();
                        done.accept(null);
                    }
                }, mainHandler);
                return;
            }
            // Only the tab's SurfaceView is safe to use here. The Chromium
            // DevTools route above works even without a native SurfaceView.
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not request PixelCopy", error);
            result.recycle();
            done.accept(null);
        }
    }

    /**
     * A Chromium compositor snapshot is independent of Android's SurfaceView.
     * The official Cefrium CDP bridge returns the Page.captureScreenshot
     * result object (base64 JPEG) on the UI thread. Decode on a worker.
     * No remote debugging, extra browser instance or external network call.
     */
    private void captureTabWithDevTools(CefriumBrowser browser, boolean fromSurface,
                                        java.util.function.Consumer<Bitmap> onSuccess,
                                        Runnable onFailure) {
        if (destroyed) return;
        try {
            String request = "{\"format\":\"jpeg\",\"quality\":58,"
                + "\"captureBeyondViewport\":false,\"fromSurface\":"
                + (fromSurface ? "true" : "false") + "}";
            browser.executeDevToolsMethod("Page.captureScreenshot", request,
                (success, resultJson) -> {
                    if (destroyed) return;
                    if (!success || resultJson == null) {
                        Log.w(TAG, "TABSHOT CDP failed, fromSurface=" + fromSurface);
                        onFailure.run();
                        return;
                    }
                    String encoded;
                    try {
                        encoded = new JSONObject(resultJson).optString("data", "");
                    } catch (org.json.JSONException invalidResponse) {
                        Log.w(TAG, "TABSHOT CDP result was not valid JSON");
                        onFailure.run();
                        return;
                    }
                    // Bound memory: Base64 input includes only image pixels.
                    // Fail closed on unexpectedly large/empty DevTools results.
                    if (encoded.length() < 400 || encoded.length() > 6_000_000) {
                        Log.w(TAG, "TABSHOT CDP returned no usable image data; chars="
                            + encoded.length());
                        onFailure.run();
                        return;
                    }
                    thumbnailDecodeExecutor.execute(() -> {
                        Bitmap decoded = null;
                        try {
                            byte[] jpeg = Base64.decode(encoded, Base64.DEFAULT);
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inSampleSize = 2;
                            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                            decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
                        } catch (RuntimeException | OutOfMemoryError error) {
                            Log.w(TAG, "TABSHOT CDP JPEG decode failed", error);
                        }
                        final Bitmap result = decoded;
                        mainHandler.post(() -> {
                            if (destroyed) return;
                            if (isUsableTabBitmap(result)) {
                                Log.i(TAG, "TABSHOT CDP JPEG decoded, fromSurface="
                                    + fromSurface + ", dimensions="
                                    + result.getWidth() + "x" + result.getHeight());
                                onSuccess.accept(result);
                            } else {
                                Log.w(TAG, "TABSHOT CDP decoded an empty/invalid frame");
                                onFailure.run();
                            }
                        });
                    });
                });
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "TABSHOT CDP screenshot request unavailable", error);
            onFailure.run();
        }
    }

    /**
     * Last resort: PixelCopy of actual surface. Do not request a screenshot
     * after switching into a private tab or another browser Activity.
     */
    private void captureTabWithSdkFallback(CefriumBrowser browser, TabRegistry.Tab tab,
                                           java.util.function.Consumer<Bitmap> onSuccess) {
        AtomicBoolean directCopyStarted = new AtomicBoolean(false);
        Runnable pixelCopy = () -> {
            if (!directCopyStarted.compareAndSet(false, true) || destroyed) return;
            captureWithAndroidPixelCopy(browser, tab, bitmap -> {
                if (isUsableTabBitmap(bitmap)) {
                    Log.i(TAG, "TABSHOT Android PixelCopy succeeded");
                    onSuccess.accept(bitmap);
                } else {
                    Log.w(TAG, "TABSHOT all image capture paths failed");
                }
            });
        };
        try {
            browser.captureBitmap(bitmap -> {
                if (isUsableTabBitmap(bitmap)) {
                    Log.i(TAG, "TABSHOT Cefrium Bitmap API succeeded");
                    onSuccess.accept(bitmap);
                } else {
                    Log.w(TAG, "TABSHOT Cefrium Bitmap API returned empty frame");
                    pixelCopy.run();
                }
            });
            mainHandler.postDelayed(pixelCopy, 450L);
        } catch (RuntimeException error) {
            Log.w(TAG, "TABSHOT Cefrium bitmap capture failed", error);
            pixelCopy.run();
        }
    }

    /**
     * DEBUG-only image-content assertion. A JPEG of a blank screen or the
     * previous smoke page MUST NOT count as a screenshot of this fixture.
     * Color checks intentionally read only 2 pixels; no private page data.
     */
    private static boolean hasOfflineFixtureColors(Bitmap image) {
        if (image == null || image.isRecycled()
                || image.getWidth() < 40 || image.getHeight() < 80) return false;
        int orange = image.getPixel(image.getWidth() / 2, image.getHeight() / 6);
        int green = image.getPixel(image.getWidth() / 2, image.getHeight() * 3 / 4);
        return Color.red(orange) > Color.green(orange) + 28
            && Color.green(orange) > Color.blue(orange) + 28
            && Color.green(green) > Color.red(green) + 25
            && Color.green(green) > Color.blue(green) + 20;
    }

    /**
     * A webpage screenshot is portrait, while overview cards are landscape.
     * CENTER_CROP displays the *middle* of a page, often a blank article body,
     * losing the page header entirely. Match Chrome's top-of-page preview:
     * fill card width and clip from the TOP, without distorting its aspect ratio.
     */
    private void positionTabThumbnail(ImageView preview) {
        if (preview.getDrawable() == null || preview.getWidth() < 1) return;
        int sourceWidth = preview.getDrawable().getIntrinsicWidth();
        if (sourceWidth < 1) return;
        float scale = preview.getWidth() / (float) sourceWidth;
        Matrix transform = new Matrix();
        transform.setScale(scale, scale);
        preview.setImageMatrix(transform);
    }

    private void displayTabThumbnail(ImageView preview, Bitmap image) {
        if (image == null) return;
        preview.setImageBitmap(image);
        positionTabThumbnail(preview);
        // A newly created bottom-sheet ImageView has no measured width yet.
        // Its one layout listener will update the matrix after measuring.
    }

    /**
     * Acquire and persist actual page pixels for a REGULAR tab only.
     * Multiple load/title/sheet events for the same tab share one request.
     * Asynchronous results are rejected after navigation or closing.
     */
    private void captureVisibleTabPreview(TabRegistry.Tab tab, ImageView target) {
        if (destroyed || tab == null || tab.privateMode
                || "about:blank".equals(tab.url)
                || tabRegistry.find(tab.id) != tab || tabRegistry.current() != tab
                || !getSharedPreferences(TAB_PREFS, MODE_PRIVATE)
                    .getBoolean("save_previews", true)) return;
        CefriumBrowser visible = liveBrowsers.get(tab.id);
        if (visible == null || visible != engine) {
            Log.w(TAG, "TABSHOT deferred: no foreground WebContents");
            return;
        }
        if (target != null) {
            pendingThumbnailViews.computeIfAbsent(tab.id, key -> new ArrayList<>()).add(target);
        }
        if (thumbnailRequestsInFlight.containsKey(tab.id)) return;

        final String capturedUrl = tab.url;
        final String tabId = tab.id;
        final AtomicBoolean done = new AtomicBoolean(false);
        thumbnailRequestsInFlight.put(tabId, done);
        java.util.function.Consumer<Bitmap> complete = bitmap -> {
            if (!done.compareAndSet(false, true)) return;
            if (thumbnailRequestsInFlight.get(tabId) != done) return;
            thumbnailRequestsInFlight.remove(tabId);
            List<ImageView> views = pendingThumbnailViews.remove(tabId);
            if (destroyed || tab.privateMode || tabRegistry.find(tabId) != tab
                    || !capturedUrl.equals(tab.url) || bitmap == null) return;
            // In the offline emulator fixture, prove the *pixels* belong to
            // the colored test page, not an earlier synthetic smoke page.
            if (previewFixtureRequested
                    && "Velocity preview fixture".equals(tab.title)) {
                if (!hasOfflineFixtureColors(bitmap)) {
                    Log.e(TAG, "TABSHOT fixture JPEG colors did not match");
                    return;
                }
                Log.i(TAG, "TABSHOT fixture orange and green pixels verified");
            }
            // Only the regular tab that produced this frame can cache it.
            previewStore.save(tabId, bitmap);
            // Smoke must validate the *card*, not just an orphan JPEG file.
            // This path is reachable only for the debug-only offline fixture.
            if (previewFixtureRequested && !previewFixtureSheetShown
                    && "Velocity preview fixture".equals(tab.title)) {
                previewFixtureSheetShown = true;
                mainHandler.postDelayed(() -> {
                    if (!destroyed && !isFinishing()) showTabs(false);
                }, 400L);
            }
            if (views != null) for (ImageView image : views) {
                if (!image.isAttachedToWindow()) continue;
                displayTabThumbnail(image, bitmap);
                image.setVisibility(View.VISIBLE);
                if (image.getParent() instanceof ViewGroup) {
                    View fallback = ((ViewGroup) image.getParent())
                        .findViewWithTag("velocity-tab-preview-fallback");
                    if (fallback != null) fallback.setVisibility(View.GONE);
                }
            }
            Log.i(TAG, "TABSHOT persisted live regular-tab screenshot");
        };
        AtomicBoolean fallbackStarted = new AtomicBoolean(false);
        Runnable fallback = () -> {
            if (!done.get() && fallbackStarted.compareAndSet(false, true)) {
                Log.i(TAG, "TABSHOT falling back to Android surface capture");
                captureTabWithSdkFallback(visible, tab, complete);
            }
        };
        Runnable cdpSecondary = () -> {
            if (!done.get()) {
                captureTabWithDevTools(visible, false, complete, fallback);
            }
        };
        // Try Chromium's compositor before any Android Surface PixelCopy.
        captureTabWithDevTools(visible, true, complete, cdpSecondary);
        // A platform/CEF callback may never arrive; never leave tab locked.
        mainHandler.postDelayed(fallback, 2700L);
        mainHandler.postDelayed(() -> {
            if (!done.get()) {
                if (thumbnailRequestsInFlight.get(tabId) == done) {
                    thumbnailRequestsInFlight.remove(tabId);
                    pendingThumbnailViews.remove(tabId);
                    Log.w(TAG, "TABSHOT timeout: no usable thumbnail in 6 seconds");
                }
            }
        }, 6000L);
    }

    /** Chrome/Vivaldi-style visual tab overview with persistent named groups. */
    private void showTabs() {
        TabRegistry.Tab current = tabRegistry.current();
        showTabs(current != null && current.privateMode);
    }

    private void showTabs(boolean privateMode) {
        if (!chromiumReady) return;
        // Trigger a fresh screenshot while the webpage is still visible.
        TabRegistry.Tab focusedTab = tabRegistry.current();
        if (focusedTab != null && !focusedTab.privateMode) {
            captureVisibleTabPreview(focusedTab, null);
        }
        LinearLayout panel = sheetPanel();
        TextView heading = text(privateMode ? "非公開タブ" : "タブ一覧", 21, PRIMARY, true);
        heading.setMinHeight(dp(43));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        final Dialog[] shown = new Dialog[1];
        LinearLayout actions = new LinearLayout(this);
        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(46)));
        Button normal = iconButton(R.drawable.ic_browser_tabs,
            "通常タブの一覧", () -> {
                shown[0].dismiss();
                showTabs(false);
            });
        normal.setText("通常  " + tabRegistry.countForMode(false));
        normal.setTextSize(14);
        normal.setTextColor(privateMode ? SECONDARY : ACCENT);
        normal.setBackground(rounded(privateMode ? SURFACE : ELEVATED, 10, BORDER));
        actions.addView(normal, new LinearLayout.LayoutParams(0, -1, 1));
        Button incognito = iconButton(R.drawable.ic_browser_lock,
            "非公開タブの一覧", () -> {
                shown[0].dismiss();
                showTabs(true);
            });
        incognito.setText("非公開  " + tabRegistry.countForMode(true));
        incognito.setTextSize(14);
        incognito.setTextColor(privateMode ? ACCENT : SECONDARY);
        incognito.setBackground(rounded(privateMode ? ELEVATED : SURFACE, 10, BORDER));
        actions.addView(incognito, new LinearLayout.LayoutParams(0, -1, 1));
        Button add = iconButton(R.drawable.ic_browser_plus,
            privateMode ? "新しい非公開タブ" : "新しい通常タブ", () -> {
                shown[0].dismiss();
                createTab("about:blank", privateMode);
            });
        actions.addView(add, new LinearLayout.LayoutParams(dp(47), -1));

        // Content-sized for 1-3 tabs, scrolling only when enough cards exist.
        BoundedScrollView scroll = new BoundedScrollView();
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout groups = sheetPanel();
        scroll.addView(groups);

        // Preserve stable group insertion order, and a dedicated ungrouped section.
        Map<String, List<TabRegistry.Tab>> groupMap = new LinkedHashMap<>();
        for (TabRegistry.Tab tab : tabRegistry.tabsForMode(privateMode)) {
            String label = tab.group == null ? "タブ" : tab.group;
            groupMap.computeIfAbsent(label, key -> new ArrayList<>()).add(tab);
        }
        if (groupMap.isEmpty()) {
            TextView empty = text(privateMode ? "非公開タブはまだないよ" : "通常タブはまだないよ",
                16, SECONDARY, false);
            empty.setPadding(dp(8), dp(38), dp(8), dp(38));
            empty.setGravity(Gravity.CENTER);
            groups.addView(empty);
        }
        // Use weighted rows rather than GridLayout's fixed pixel widths. The
        // dialog's measured inner width is the source of truth, not full screen
        // metrics (which ignore split screen, panel padding and font scaling).
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int columns = BrowserUiLayout.columnsForAvailableWidthDp(
            BrowserUiLayout.availableSheetWidthDp(widthDp));
        int previewHeightDp = columns == 1 ? 168 : columns == 2 ? 137 : 122;
        for (Map.Entry<String, List<TabRegistry.Tab>> group : groupMap.entrySet()) {
            TextView section = text(group.getKey() + "  ·  " + group.getValue().size(),
                15, ACCENT, true);
            section.setPadding(dp(5), dp(10), 0, dp(5));
            section.setMinHeight(dp(42));
            groups.addView(section, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout row = null;
            int itemIndex = 0;
            for (TabRegistry.Tab tab : group.getValue()) {
                if (itemIndex % columns == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.TOP);
                    groups.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
                LinearLayout card = sheetPanel();
                card.setBackground(rounded(SURFACE, 16,
                    tab == tabRegistry.current() ? ACCENT : BORDER));
                card.setMinimumHeight(dp(previewHeightDp + 99));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    0, -2, 1f);
                cardLp.setMargins(dp(itemIndex % columns == 0 ? 0 :
                    BrowserUiLayout.CARD_GAP_DP), dp(3), 0, dp(5));
                row.addView(card, cardLp);
                itemIndex++;

                FrameLayout screenshot = new FrameLayout(this);
                screenshot.setBackground(rounded(ELEVATED, 13, Color.TRANSPARENT));
                screenshot.setClipToOutline(true);
                card.addView(screenshot, new LinearLayout.LayoutParams(
                    -1, dp(previewHeightDp)));
                ImageView preview = new ImageView(this);
                preview.setContentDescription("ページのプレビュー");
                preview.setScaleType(ImageView.ScaleType.MATRIX);
                preview.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, orr, ob) ->
                    positionTabThumbnail(preview));
                screenshot.addView(preview, new FrameLayout.LayoutParams(-1, -1));
                Bitmap bitmap = tab.privateMode ? null : previewStore.load(tab.id);
                if (!tab.privateMode && !"about:blank".equals(tab.url)) {
                    Log.i(TAG, "TABSHOT sheet card cached=" + (bitmap != null)
                        + " foreground=" + (tab == tabRegistry.current()));
                }
                if (bitmap != null) {
                    displayTabThumbnail(preview, bitmap);
                } else {
                    // A small site identity is more useful than a giant
                    // "preview unavailable" warning. It is not a fake screenshot.
                    String host = SiteBlockPolicy.hostOf(tab.url);
                    boolean blank = "about:blank".equals(tab.url);
                    FrameLayout placeholder = new FrameLayout(this);
                    placeholder.setTag("velocity-tab-preview-fallback");
                    screenshot.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
                    TextView fallback = text(tab.privateMode ? "非公開" :
                        blank ? "新しいタブ" : host == null ? "Webページ" : host,
                        12, SECONDARY, false);
                    fallback.setGravity(Gravity.CENTER);
                    fallback.setPadding(dp(6), 0, dp(6), 0);
                    fallback.setMaxLines(2);
                    FrameLayout.LayoutParams fallbackLp = new FrameLayout.LayoutParams(
                        -1, dp(43), Gravity.BOTTOM);
                    fallbackLp.bottomMargin = dp(11);
                    placeholder.addView(fallback, fallbackLp);
                    ImageView symbol = iconView(tab.privateMode ? R.drawable.ic_browser_lock
                        : blank ? R.drawable.ic_browser_plus : R.drawable.ic_browser_globe,
                        SECONDARY);
                    FrameLayout.LayoutParams mark = new FrameLayout.LayoutParams(dp(44), dp(44),
                        Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                    mark.topMargin = dp(21);
                    placeholder.addView(symbol, mark);
                    if (tab == tabRegistry.current() && !blank && !tab.privateMode) {
                        captureVisibleTabPreview(tab, preview);
                    }
                }
                TextView name = text(tab.displayTitle(), 14, PRIMARY, true);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setPadding(dp(8), 0, dp(8), 0);
                name.setMinHeight(dp(35));
                card.addView(name, new LinearLayout.LayoutParams(-1, -2));
                TextView url = text(tab.privateMode ? "非公開" : tab.url,
                    11, SECONDARY, false);
                url.setSingleLine(true);
                url.setEllipsize(android.text.TextUtils.TruncateAt.END);
                url.setPadding(dp(8), 0, dp(8), 0);
                url.setMinHeight(dp(25));
                card.addView(url, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout actionsRow = new LinearLayout(this);
                actionsRow.setGravity(Gravity.CENTER_VERTICAL);
                card.addView(actionsRow, new LinearLayout.LayoutParams(-1, dp(40)));
                Button groupButton = makeButton("グループ", "タブをグループ分け", () -> {
                    shown[0].dismiss();
                    showTabGroupDialog(tab.id);
                });
                groupButton.setTextSize(12);
                groupButton.setSingleLine(true);
                groupButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
                groupButton.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_browser_folder, 0, 0, 0);
                groupButton.setCompoundDrawablePadding(dp(4));
                groupButton.setPadding(dp(5), 0, 0, 0);
                actionsRow.addView(groupButton, new LinearLayout.LayoutParams(0, dp(40), 1));
                Button close = iconButton(R.drawable.ic_browser_close, "タブを閉じる", () -> {
                    shown[0].dismiss();
                    closeTabDirect(tab.id);
                });
                actionsRow.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
                screenshot.setOnClickListener(v -> {
                    shown[0].dismiss();
                    selectTab(tab.id);
                });
                card.setOnClickListener(v -> {
                    shown[0].dismiss();
                    selectTab(tab.id);
                });
                card.setOnLongClickListener(v -> {
                    shown[0].dismiss();
                    showTabGroupDialog(tab.id);
                    return true;
                });
            }
            // Reserve unused columns in the last row so a single final card
            // remains the same width as the cards above it.
            if (row != null && itemIndex % columns != 0) {
                for (int col = itemIndex % columns; col < columns; col++) {
                    View spacer = new View(this);
                    LinearLayout.LayoutParams empty = new LinearLayout.LayoutParams(
                        0, 1, 1f);
                    empty.leftMargin = dp(BrowserUiLayout.CARD_GAP_DP);
                    row.addView(spacer, empty);
                }
            }
        }
        shown[0] = bottomSheet(panel);
    }

    private void showTabGroupDialog(String id) {
        TabRegistry.Tab tab = tabRegistry.find(id);
        if (tab == null) return;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (TabRegistry.Tab other : tabRegistry.tabsForMode(tab.privateMode)) {
            if (other.group != null) names.add(other.group);
        }
        ArrayList<String> choices = new ArrayList<>();
        choices.add("＋ 新しいグループを作成");
        choices.add("グループから外す");
        choices.addAll(names);
        new AlertDialog.Builder(this).setTitle("タブのグループ分け")
            .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                if (which == 0) {
                    EditText name = new EditText(this);
                    name.setSingleLine(true);
                    name.setHint("グループ名");
                    new AlertDialog.Builder(this).setTitle("新しいグループ")
                        .setView(name).setPositiveButton("作成", (d, w) -> {
                            String value = name.getText().toString().trim();
                            if (!value.isEmpty()) {
                                tab.group = value.substring(0, Math.min(40, value.length()));
                                saveRegularTabs();
                                showTabs(tab.privateMode);
                            }
                        }).setNegativeButton("キャンセル", null).show();
                } else {
                    tab.group = which == 1 ? null : choices.get(which);
                    saveRegularTabs();
                    showTabs(tab.privateMode);
                }
            }).setNegativeButton("キャンセル", null).show();
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
        if (!victim.privateMode) previewStore.remove(victim.id);
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
            android.net.Uri.parse("https://github.com/linkalls/velocity-browser/actions/workflows/velocity-signed-apk.yml"));
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
            "更新APKを確認", "起動診断", "設定", "このブラウザについて",
            "PC版サイト / スマホ版サイト", "URLをコピー", "ページの表示倍率"
        };
        int[] icons = {
            R.drawable.ic_browser_plus, R.drawable.ic_browser_lock,
            R.drawable.ic_browser_close, R.drawable.ic_browser_search,
            R.drawable.ic_browser_share, R.drawable.ic_browser_globe,
            R.drawable.ic_browser_shield, R.drawable.ic_browser_eye_off,
            R.drawable.ic_browser_shield, R.drawable.ic_browser_speed,
            R.drawable.ic_browser_download, R.drawable.ic_browser_flask,
            R.drawable.ic_browser_settings, R.drawable.ic_browser_info,
            R.drawable.ic_browser_globe, R.drawable.ic_browser_share, R.drawable.ic_browser_search
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
            "ページ・プライバシー・Googleパスキーの設定",
            "Cefrium 0.18.0 · 開発版",
            tabRegistry.current() != null && tabRegistry.current().desktopMode
                ? "PC版リクエスト ON · スマホ版に戻す" : "スマホ版リクエスト ON · PC版に切替",
            "ページのURLをクリップボードにコピー", "80% / 100% / 125% / 150%"
        };
        LinearLayout panel = sheetPanel();
        TextView header = text("Velocity", 21, PRIMARY, true);
        header.setPadding(dp(6), 0, 0, dp(12));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(45)));
        BoundedScrollView scroll = new BoundedScrollView();
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout items = sheetPanel();
        scroll.addView(items);
        final Dialog[] shown = new Dialog[1];

        // Group actions by user intent instead of chronological implementation order.
        final int[] menuOrder = {0, 1, 2, 3, 14, 16, 4, 15, 5, 6, 7, 8, 9, 10, 11, 12, 13};
        for (int i : menuOrder) {
            final int action = i;
            String section = null;
            if (i == 0) section = "タブ";
            else if (i == 3) section = "ページ";
            else if (i == 6) section = "プライバシーと保護";
            else if (i == 9) section = "ブラウザ";
            if (section != null) {
                TextView sectionHeader = text(section, 13, ACCENT, true);
                sectionHeader.setPadding(dp(11), dp(8), 0, dp(2));
                items.addView(sectionHeader, new LinearLayout.LayoutParams(-1, dp(35)));
            }
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(9), 0, dp(6), 0);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF),
                rounded(SURFACE, 13, Color.TRANSPARENT), null));
            item.setMinimumHeight(dp(63));
            item.setPadding(dp(9), dp(8), dp(8), dp(8));
            items.addView(item, new LinearLayout.LayoutParams(-1, -2));
            item.addView(iconView(icons[i], i == 12 ? ACCENT : SECONDARY),
                new LinearLayout.LayoutParams(dp(43), dp(43)));
            LinearLayout detail = sheetPanel();
            detail.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = text(labels[i], 15, PRIMARY, false);
            TextView sub = text(hints[i], 11, SECONDARY, false);
            label.setMinHeight(dp(23));
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.addView(label, new LinearLayout.LayoutParams(-1, -2));
            detail.addView(sub, new LinearLayout.LayoutParams(-1, -2));
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
            case 4: shareCurrentPage(); break;
            case 5: showSites(); break;
            case 6: toggleNetworkRules(); break;
            case 7: toggleCosmetics(); break;
            case 8: toggleCurrentSiteException(); break;
            case 9: toggleExperimentalFastStartup(); break;
            case 10: openUpdatesPage(); break;
            case 11: startActivity(new Intent(this, StartupDiagnosticActivity.class)); break;
            case 12: showSettings(); break;
            case 13: showInfo(); break;
            case 14: toggleDesktopMode(); break;
            case 15: copyCurrentPageUrl(); break;
            case 16: showPageZoom(); break;
            default: break;
        }
    }

    /** Prefer an already-playing external music/podcast app over web audio. */
    private boolean externalAudioPriority() {
        return getSharedPreferences(TAB_PREFS, MODE_PRIVATE)
            .getBoolean("external_audio_priority", true);
    }

    private void applyBrowserSettings(CefriumBrowser browser) {
        SharedPreferences settings = getSharedPreferences(TAB_PREFS, MODE_PRIVATE);
        browser.setPullToRefreshEnabled(settings.getBoolean("pull_to_refresh", true));
        // Default forbids autoplay without user activation.
        browser.setMediaPlaybackRequiresUserGesture(!settings.getBoolean("auto_play", false));
        // Chromium's media session can hold/request Android audio focus,
        // causing the user's music in another app to duck, even on a page
        // that the user merely opened to read. Opt out by default.
        browser.setMediaSessionEnabled(!externalAudioPriority());
    }

    private void quietBrowserWhenHidden(CefriumBrowser browser) {
        if (externalAudioPriority()) {
            // stopAllMedia() releases web media instead of leaving a suspended
            // MediaSession that onResume() may automatically resume/focus.
            // The user can start a video's audio again with a page gesture.
            browser.stopAllMedia();
            browser.setPageVisible(false);
        } else {
            browser.onPause();
        }
    }

    private void foregroundBrowserWithoutStealingAudio(CefriumBrowser browser) {
        if (!externalAudioPriority()) {
            browser.onResume();
        }
        browser.setAudioMuted(false);
        browser.setPageVisible(true);
        if (externalAudioPriority() && tabRegistry.isStartupHomeCurrent()) {
            // A blank home has no user-requested audio to keep active.
            // stopMediaSession operates at WebContents level; disabling system
            // notification controls alone does NOT release Android audio focus.
            browser.stopMediaSession();
        }
    }

    private void addSettingsToggle(LinearLayout panel, String titleText, String hint,
                                   String key, boolean defaultValue) {
        SharedPreferences preferences = getSharedPreferences(TAB_PREFS, MODE_PRIVATE);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams rowSpace = new LinearLayout.LayoutParams(-1, -2);
        rowSpace.bottomMargin = dp(5);
        row.setBackground(rounded(ELEVATED, 13, Color.TRANSPARENT));
        int optionIcon;
        switch (key) {
            case "pull_to_refresh": optionIcon = R.drawable.ic_browser_refresh; break;
            case "force_dark": optionIcon = R.drawable.ic_browser_moon; break;
            case "auto_play": optionIcon = R.drawable.ic_browser_play; break;
            case "save_previews": optionIcon = R.drawable.ic_browser_image; break;
            case "block_domains": optionIcon = R.drawable.ic_browser_shield; break;
            case "cosmetic_elements": optionIcon = R.drawable.ic_browser_eye_off; break;
            case "external_audio_priority": optionIcon = R.drawable.ic_browser_play; break;
            default: optionIcon = R.drawable.ic_browser_settings; break;
        }
        row.addView(iconView(optionIcon, ACCENT), new LinearLayout.LayoutParams(dp(41), dp(41)));
        LinearLayout body = sheetPanel();
        body.setPadding(dp(4), dp(1), dp(8), dp(1));
        TextView titleView = text(titleText, 14, PRIMARY, true);
        TextView description = text(hint, 12, SECONDARY, false);
        description.setMaxLines(3);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        body.addView(description, new LinearLayout.LayoutParams(-1, -2));
        row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(preferences.getBoolean(key, defaultValue));
        toggle.setMinWidth(dp(50));
        row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(50)));
        row.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            preferences.edit().putBoolean(key, enabled).apply();
            if ("external_audio_priority".equals(key) && enabled) {
                for (CefriumBrowser browser : liveBrowsers.values()) {
                    browser.stopAllMedia();
                }
            }
            if ("block_domains".equals(key)) blockDomainsEnabled = enabled;
            if ("cosmetic_elements".equals(key)) cosmeticEnabled = enabled;
            for (CefriumBrowser browser : liveBrowsers.values()) {
                if ("pull_to_refresh".equals(key)) browser.setPullToRefreshEnabled(enabled);
                if ("auto_play".equals(key)) browser.setMediaPlaybackRequiresUserGesture(!enabled);
                if ("force_dark".equals(key)) browser.setDarkMode(enabled);
                if ("external_audio_priority".equals(key)) {
                    browser.setMediaSessionEnabled(!enabled);
                    if (enabled) browser.setMediaPlaybackRequiresUserGesture(true);
                }
            }
            if ("cosmetic_elements".equals(key)) {
                for (TabRegistry.Tab tab : tabRegistry.all()) {
                    CefriumBrowser live = liveBrowsers.get(tab.id);
                    if (live != null) refreshCosmetics(live, tab);
                }
            }
        });
        row.setMinimumHeight(dp(70));
        panel.addView(row, rowSpace);
    }

    private void showSettings() {
        LinearLayout panel = sheetPanel();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(iconView(R.drawable.ic_browser_settings, ACCENT),
            new LinearLayout.LayoutParams(dp(43), dp(43)));
        heading.addView(text("Velocity 設定", 21, PRIMARY, true));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(45)));
        TextView version = text(installedVersionLabel(), 11, SECONDARY, false);
        version.setPadding(dp(7), 0, 0, dp(6));
        panel.addView(version, new LinearLayout.LayoutParams(-1, -2));
        BoundedScrollView scroll = new BoundedScrollView();
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout items = sheetPanel();
        scroll.addView(items);
        TextView displaySection = text("表示と操作", 14, ACCENT, true);
        displaySection.setPadding(dp(5), dp(14), 0, dp(10));
        items.addView(displaySection);
        addSettingsToggle(items, "下に引いて更新", "ページの先頭から引き下ろして再読み込み",
            "pull_to_refresh", true);
        addSettingsToggle(items, "Webサイトのダークモード", "Chromiumのカラースキームを変更",
            "force_dark", false);
        addSettingsToggle(items, "動画と音声の自動再生", "自動再生の設定。音声フォーカスとは別",
            "auto_play", false);
        addSettingsToggle(items, "他アプリの音声を優先", "標準ON。ブラウザ復帰時の音声再開を抑制",
            "external_audio_priority", true);
        Button stopAudio = makeButton("Webメディアを停止・音声フォーカスを解放", "不要なWeb音声セッションを停止",
            this::stopAllWebMedia);
        stopAudio.setTextSize(13);
        stopAudio.setSingleLine(false);
        stopAudio.setMinHeight(dp(48));
        stopAudio.setPadding(dp(12), dp(7), dp(10), dp(7));
        stopAudio.setCompoundDrawablesWithIntrinsicBounds(
            R.drawable.ic_browser_play, 0, 0, 0);
        items.addView(stopAudio, new LinearLayout.LayoutParams(-1, -2));
        Button audioDiagnostic = makeButton("音声フォーカスの原因を調査", "Androidの音声フォーカス診断手順",
            this::showAudioFocusDiagnostic);
        audioDiagnostic.setTextSize(13);
        audioDiagnostic.setMinHeight(dp(48));
        audioDiagnostic.setPadding(dp(12), dp(7), dp(10), dp(7));
        audioDiagnostic.setCompoundDrawablesWithIntrinsicBounds(
            R.drawable.ic_browser_info, 0, 0, 0);
        items.addView(audioDiagnostic, new LinearLayout.LayoutParams(-1, -2));
        TextView privacySection = text("タブとプライバシー", 14, ACCENT, true);
        privacySection.setPadding(dp(5), dp(18), 0, dp(10));
        items.addView(privacySection);
        addSettingsToggle(items, "タブのプレビューを保存", "通常タブのみ、アプリ内に小さい画像を保存",
            "save_previews", true);
        addSettingsToggle(items, "追加の広告ドメイン遮断", "Velocity Block Lite のネットワーク遮断",
            "block_domains", true);
        addSettingsToggle(items, "広告枠を非表示", "静的CSSルールによる要素の非表示",
            "cosmetic_elements", true);
        Button provider = makeButton("Googleパスキーの設定を開く", "Android標準の認証情報サービスを設定",
            this::openCredentialSettings);
        provider.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_browser_key, 0, 0, 0);
        provider.setCompoundDrawablePadding(dp(12));
        provider.setTextSize(15);
        provider.setPadding(dp(10), dp(6), dp(10), dp(6));
        provider.setMinHeight(dp(58));
        provider.setTextSize(14);
        provider.setSingleLine(false);
        items.addView(provider, new LinearLayout.LayoutParams(-1, -2));
        TextView caveat = text("Googleパスワードマネージャーを外部サイトで使うには、" +
            "ブラウザに対するGoogle側の承認も必要。Androidの設定変更だけでは解除できません。",
            12, SECONDARY, false);
        caveat.setPadding(dp(9), dp(9), dp(9), dp(9));
        items.addView(caveat, new LinearLayout.LayoutParams(-1, -2));
        TextView sessionInfo = text("タブ数は無制限。終了後は通常タブの一覧・履歴を保存し、" +
            "非公開タブは保存しません。プロセス終了後のWebページは再生成が必要です。",
            12, SECONDARY, false);
        sessionInfo.setPadding(dp(9), dp(9), dp(9), dp(9));
        items.addView(sessionInfo, new LinearLayout.LayoutParams(-1, -2));
        bottomSheet(panel);
    }

    /** Surface the actual installed APK build, not a hardcoded marketing label. */
    private String installedVersionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "APK " + info.versionName + "  ·  build " + info.getLongVersionCode()
                + "  ·  " + getPackageName();
        } catch (PackageManager.NameNotFoundException error) {
            return "APK版を取得できませんでした";
        }
    }

    /** Only Cefrium itself can abandon focus held by its WebContents. */
    private void stopAllWebMedia() {
        int stopped = 0;
        for (CefriumBrowser browser : liveBrowsers.values()) {
            try {
                browser.stopMediaSession();
                browser.stopAllMedia();
                stopped++;
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not release WebContents media session", error);
            }
        }
        Toast.makeText(this, stopped + "件のWebメディアを停止したよ", Toast.LENGTH_SHORT).show();
    }

    private void showAudioFocusDiagnostic() {
        new AlertDialog.Builder(this)
            .setTitle("音声が小さくなる原因を調べる")
            .setMessage("『自動再生OFF』は音声フォーカス取得の禁止ではないよ。"
                + "\n\nまず、この画面に表示されているAPKのbuild番号を確認。"
                + "\n\n音楽を再生した状態で、PCからUSBデバッグを使い："
                + "\n\n① Velocityを開く前\nadb shell dumpsys audio"
                + "\n\n② Velocityを開いて音が小さくなった直後\nadb shell dumpsys audio"
                + "\n\n『Audio Focus stack entries』のpackage名・clientId・gainTypeを比較してね。"
                + "\n\nAndroidの制限で、このアプリ自身が別アプリのフォーカス履歴を読めるわけではない。"
                + "\n\nパスワードや個人情報を伏せて共有してね。")
            .setPositiveButton("了解", null).show();
    }

    private void openCredentialSettings() {
        try {
            startActivity(new Intent("android.settings.CREDENTIAL_PROVIDER"));
        } catch (RuntimeException error) {
            Toast.makeText(this, "端末の『パスワードと自動入力』を確認してね",
                Toast.LENGTH_LONG).show();
        }
    }

    private void showInfo() {
        new AlertDialog.Builder(this).setTitle("Velocity Browser")
            .setMessage("エンジン：Chromium（Cefrium 0.18.0）\n\n" +
                "これはAndroid標準WebViewではありません。\n\n" +
                "Gecko/WPE版とは別アプリとしてインストールできます。\n\n" +
                "通常・非公開タブ、URL復元、戻る・進む、検索と共有を追加しました。\n\n" +
                "ドメイン遮断・限定的なCSS要素非表示を移植。Gecko拡張・完全なフィルター互換・ダウンロードUI・権限UI、" +
                "実機セキュリティ検証は未移植です。重要なアカウントにはログインしないでください。")
            .setPositiveButton("OK", null).show();
    }

    @Override protected void onPause() {
        activityResumed = false;
        if (engine != null) quietBrowserWhenHidden(engine);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (engine != null) {
            // The chooser or credential provider temporarily takes focus.
            // Restore the same Surface without automatically restarting any
            // media session that would duck music from an unrelated app.
            foregroundBrowserWithoutStealingAudio(engine);
            engine.getSurfaceContainer().setVisibility(View.VISIBLE);
            engine.getSurfaceContainer().requestLayout();
            engine.getSurfaceContainer().invalidate();
            if (browserRoot != null) browserRoot.bringToFront();
            updateHomeState();
        }
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
        // Serialize only existing regular renderers. Private history stays RAM-only.
        for (TabRegistry.Tab tab : tabRegistry.regularTabs()) {
            CefriumBrowser live = liveBrowsers.get(tab.id);
            if (live == null || tab.startupHome) continue;
            try {
                previewStore.saveNavigationState(tab.id, live.serializeNavigationState());
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not capture back-forward stack", error);
            }
        }
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

    /** System Back is navigation, not Activity destruction during page loads. */
    private void handleBrowserBack() {
        if (address != null && address.hasFocus()) {
            hideKeyboard();
            address.clearFocus();
            return;
        }
        if (engine != null && !tabRegistry.isStartupHomeCurrent() && engine.canGoBack()) {
            engine.goBack();
            return;
        }
        TabRegistry.Tab current = tabRegistry.current();
        if (current != null && current.loading && engine != null) {
            // A brand-new navigation can report canGoBack=false until committed.
            // Cancel it rather than erroneously finishing the Activity.
            engine.stopLoading();
            return;
        }
        if (!tabRegistry.isStartupHomeCurrent()) {
            tabRegistry.selectStartupHome();
            activateCurrentTab();
            saveRegularTabs();
            return;
        }
        // Preserve all browser objects and history for an ordinary app re-launch.
        moveTaskToBack(true);
    }

    @Override @Deprecated public void onBackPressed() { handleBrowserBack(); }

    @Override protected void onDestroy() {
        destroyed = true;
        if (Build.VERSION.SDK_INT >= 33 && gestureBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(gestureBackCallback);
        }
        thumbnailDecodeExecutor.shutdownNow();
        thumbnailRequestsInFlight.clear();
        pendingThumbnailViews.clear();
        if (previewStore != null) previewStore.close();
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
