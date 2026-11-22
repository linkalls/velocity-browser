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
import android.text.Editable;
import android.text.TextWatcher;
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
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
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
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumContextMenu;
import com.cefrium.CefriumNavigationClient;
import com.cefrium.CefriumResourceInterceptor;
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
    private CefriumDownloadController downloads;
    /** Tabs closed while CEF is downloading keep their engine until completion. */
    private final Set<CefriumBrowser> closingAfterDownload = new HashSet<>();
    // RAM-only history. Private tabs never appear in the recently closed list.
    private final List<TabRegistry.Tab> recentlyClosedRegularTabs = new ArrayList<>();
    private final Map<String, ClosedTab> closedRegularState = new java.util.HashMap<>();
    private ClosedTab reopeningTab;
    private static final class ClosedTab {
        final TabRegistry.Tab tab;
        final int position;
        final byte[] navigation;
        ClosedTab(TabRegistry.Tab tab, int position, byte[] navigation) {
            this.tab = tab;
            this.position = position;
            this.navigation = navigation;
        }
    }
    private static final int IMPORT_BOOKMARKS_REQUEST = 51301;
    private static final int EXPORT_BOOKMARKS_REQUEST = 51302;
    private final BookmarkStore bookmarkStore = new BookmarkStore();
    private final ExecutorService bookmarkFileExecutor = Executors.newSingleThreadExecutor();
    private final ReadingList readingList = new ReadingList();
    private final HistoryStore historyStore = new HistoryStore();
    private OnBackInvokedCallback gestureBackCallback;
    private final Map<String, CefriumBrowser> liveBrowsers = new LinkedHashMap<>(16, .75f, true);
    // A popup's opener must remain alive until window.close() / tab close so
    // OAuth and payment popups can safely report their results to window.opener.
    private final Map<CefriumBrowser, CefriumBrowser> popupOpeners = new java.util.IdentityHashMap<>();
    private static final String TAB_PREFS = "cefrium_tabs_v1";
    private static final long MEMORY_SAMPLE_INTERVAL_MS = 3000L;
    private long lastMemorySampleAt = -MEMORY_SAMPLE_INTERVAL_MS;
    private int residentBrowserTarget = 6;
    private static final boolean DEFAULT_RESTORE_LAST_TAB = true;
    private static final boolean DEFAULT_PULL_TO_REFRESH = false;
    private String lastSavedTabSnapshot;
    private CefriumBrowser engine;
    private LinearLayout browserRoot;
    private FrameLayout browserContent;
    private FrameLayout homeOverlay;
    private View browserHeader;
    private EditText findQuery;
    private TextView findCount;
    private CefriumBrowser findBrowser;
    private String issuedFindQuery = "";
    private final Runnable runPageFind = () -> runPageFind(true, false);
    private View browserFooter;
    private LinearLayout toolbarActions;
    private LinearLayout addressSuggestions;
    private final Runnable refreshAddressSuggestions = this::updateAddressSuggestions;
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
    private ImageView locationIdentity;
    private Button addressAction;
    private boolean destroyed;
    private static final String TAG = "VelocityCefStartup";
    private boolean syntheticSmokeRequested;
    private boolean previewFixtureRequested;
    private boolean previewFixtureSheetShown;
    private long syntheticLoadStartedAtMs = -1L;
    private long syntheticPaintStartedAtMs = -1L;

    // Deliberately restrained palette. No novelty gradients, false neon
    // borders, or extra Material dependencies on the critical launch path.
    private static final int CANVAS = Color.rgb(22, 23, 28);
    private static final int SURFACE = Color.rgb(37, 39, 46);
    private static final int ELEVATED = Color.rgb(48, 51, 59);
    private static final int BORDER = Color.rgb(65, 69, 78);
    private static final int PRIMARY = Color.rgb(245, 246, 249);
    private static final int SECONDARY = Color.rgb(170, 175, 185);
    private static final int ACCENT = Color.rgb(126, 169, 244);

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
        downloads = new CefriumDownloadController(this, browser -> {
            if (closingAfterDownload.remove(browser)) browser.close();
            if (tabRegistry.current() != null) trimInactiveBrowsers(tabRegistry.current().id);
        });

        // Title is retained as a state/accessibility sink. The old always-on
        // top bar wasted viewport height and added no useful browser control.
        LinearLayout header = new LinearLayout(this);
        header.setVisibility(View.GONE);
        title = text("Velocity · Cefrium", 13, SECONDARY, false);
        title.setVisibility(View.GONE);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(4), 0);
        header.addView(title);
        buildPageFindBar(header);
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
        // One Activity-owned listener. Recreating or closing a tab must not
        // leave a callback that retains (and still lays out) its old Surface.
        browserRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) ->
            positionActiveExternalSurface());
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

        addressSuggestions = new LinearLayout(this);
        addressSuggestions.setOrientation(LinearLayout.VERTICAL);
        addressSuggestions.setBackground(rounded(SURFACE, 14, BORDER));
        addressSuggestions.setVisibility(View.GONE);
        footer.addView(addressSuggestions, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout addressRow = new LinearLayout(this);
        addressBar = addressRow;
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(12), 0, dp(5), 0);
        addressRow.setBackground(rounded(SURFACE, 17, BORDER));
        LinearLayout.LayoutParams addressLp = new LinearLayout.LayoutParams(-1, dp(51));
        footer.addView(addressRow, addressLp);

        locationIdentity = iconView(R.drawable.ic_browser_search, SECONDARY);
        locationIdentity.setContentDescription("サイト情報");
        locationIdentity.setOnClickListener(v -> showCurrentSiteInfo());
        addressRow.addView(locationIdentity,
            new LinearLayout.LayoutParams(dp(BrowserUiLayout.MIN_TOUCH_TARGET_DP),
                dp(BrowserUiLayout.MIN_TOUCH_TARGET_DP)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(PRIMARY);
        address.setHintTextColor(SECONDARY);
        address.setHint("検索またはアドレスを入力");
        address.setTextSize(14);
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

        addressAction = iconButton(R.drawable.ic_browser_forward,
            "検索・ページを開く", this::loadAddress);
        addressAction.setVisibility(View.GONE);
        addressRow.addView(addressAction,
            new LinearLayout.LayoutParams(dp(BrowserUiLayout.MIN_TOUCH_TARGET_DP),
                dp(BrowserUiLayout.MIN_TOUCH_TARGET_DP)));

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
        Button home = iconButton(R.drawable.ic_browser_home,
            "ホームを表示", this::goHome);
        tabsButton = iconButton(R.drawable.ic_browser_tabs, "タブ一覧", this::showTabs);
        tabsButton.setTextSize(13);
        tabsButton.setBackground(rounded(SURFACE, 12, BORDER));
        tabsButton.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setItems(new String[]{
                "新しい通常タブ", "新しい非公開タブ"}, (d, index) ->
                createTab("about:blank", index == 1)).show();
            return true;
        });
        Button menu = iconButton(R.drawable.ic_browser_menu, "ブラウザメニュー", this::showMenu);
        toolbarActions.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbarActions.addView(forward, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbarActions.addView(home, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbarActions.addView(tabsButton, new LinearLayout.LayoutParams(0, dp(48), 1.25f));
        toolbarActions.addView(menu, new LinearLayout.LayoutParams(0, dp(48), 1));

        address.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count,
                    int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before,
                    int count) {}
            @Override public void afterTextChanged(Editable s) {
                mainHandler.removeCallbacks(refreshAddressSuggestions);
                if (address.hasFocus()) mainHandler.postDelayed(refreshAddressSuggestions, 100);
            }
        });

        // Focus is the only source of truth for editing mode. When the IME
        // appears, drop the duplicate toolbar instead of hiding the URL input.
        address.setOnFocusChangeListener((v, hasFocus) -> {
            toolbarActions.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
            addressAction.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
            if (hasFocus) {
                TabRegistry.Tab activeTab = tabRegistry.current();
                if (activeTab != null) {
                    address.setText(activeTab.url.equals("about:blank") ? "" : activeTab.url);
                }
                address.selectAll();
                locationIdentity.setImageResource(R.drawable.ic_browser_search);
            } else {
                clearAddressSuggestions();
                updateNavigation();
            }
        });

        // Chrome/Vivaldi-style vertical swipe opens the tab switcher;
        // horizontal swipes continue navigating within the same privacy mode.
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
                    float vertical = up.getY() - down.getY();
                    if (vertical > dp(76) && Math.abs(velocityY) > dp(250)
                            && Math.abs(vertical) > Math.abs(displacement) * 1.25f) {
                        showTabs();
                        return true;
                    }
                    if (Math.abs(displacement) < dp(80)
                            || Math.abs(velocityX) < dp(250)
                            || Math.abs(displacement) < Math.abs(vertical)) return false;
                    switchTabBy(displacement < 0 ? 1 : -1);
                    return true;
                }
            });
        address.setOnTouchListener((v, event) ->
            address.hasFocus() ? false : addressGesture.onTouchEvent(event));
        root.requestFocus();
        // Screenshot-only UI audit hook in DEBUG builds. Never change user
        // preferences in a signed build or on an ordinary app launch.
        String audit = getIntent() == null ? null :
            getIntent().getStringExtra("velocity_debug_ui_audit");
        boolean debugAudit = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && audit != null;
        if (debugAudit) {
            getSharedPreferences(TAB_PREFS, MODE_PRIVATE).edit()
                .putBoolean("address_bar_bottom", !"top-bar".equals(audit)).apply();
        }
        // The toolbar is genuinely reparented. It never duplicates its controls
        // and it preserves the active Cefrium Surface and keyboard focus.
        applyBrowserBarPosition();
        loadBookmarks();
        loadReadingList();
        loadHistory();
        if (debugAudit && ("settings".equals(audit) || "tabs".equals(audit)
                || "tabs-private".equals(audit) || "tabs-groups".equals(audit)
                || "tabs-search".equals(audit) || "address-ime".equals(audit)
                || "settings-tabs".equals(audit) || "settings-media".equals(audit)
                || "home".equals(audit) || "menu".equals(audit)
                || "top-bar".equals(audit))) {
            root.postDelayed(() -> {
                if (destroyed || isFinishing()) return;
                if ("settings".equals(audit)) {
                    showSettings();
                } else if ("settings-tabs".equals(audit)) {
                    showSettingsCategory("タブ");
                } else if ("settings-media".equals(audit)) {
                    showSettingsCategory("メディア");
                } else if ("menu".equals(audit)) {
                    showMenu();
                } else if ("address-ime".equals(audit)) {
                    focusAddress();
                    // Read actual system/IME insets after the keyboard has opened.
                    root.postDelayed(() -> logInsetsForAudit(root), 900L);
                } else if ("home".equals(audit) || "top-bar".equals(audit)) {
                    // Home and toolbar are visible, without site navigation.
                    updateHomeState();
                    logInsetsForAudit(root);
                } else if (chromiumReady) {
                    if ("tabs-private".equals(audit)) {
                        // An isolated, blank private tab: no user content or JPEG.
                        tabRegistry.add("about:blank", true);
                        showTabs(true);
                    } else {
                        // Four deterministic empty tabs demonstrate the 2x2
                        // group collage with no network or personal content.
                        for (int i = 0; i < 5; i++) {
                            TabRegistry.Tab debugTab = tabRegistry.add("about:blank", false);
                            if (i < 4) debugTab.group = "開発グループ";
                        }
                        if ("tabs-groups".equals(audit)) {
                            showTabOverview(TAB_VIEW_GROUPS, null);
                        } else if ("tabs-search".equals(audit)) {
                            // Synthetic, disconnected metadata only. A result
                            // should appear outside the four-tab group mosaic.
                            TabRegistry.Tab found = tabRegistry.add("https://example.com/research", false);
                            found.title = "研究記事のサンプル";
                            showTabOverview(TAB_VIEW_NORMAL, null, "研究");
                        } else {
                            showTabs(false);
                        }
                    }
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
    /** Local shortcuts use a bounded private preference; never fetch favicon URLs. */
    private static final String SPEED_DIALS_KEY = "velocity_speed_dials_v2";

    private static final class SpeedDial {
        final String name;
        final String url;
        SpeedDial(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private List<SpeedDial> readSpeedDials() {
        String saved = getSharedPreferences(TAB_PREFS, MODE_PRIVATE)
            .getString(SPEED_DIALS_KEY, null);
        List<SpeedDial> list = new ArrayList<>();
        if (saved == null) {
            for (int i = 0; i < SITE_URLS.length; i++) {
                list.add(new SpeedDial(SITE_NAMES[i], SITE_URLS[i]));
            }
            return list;
        }
        try {
            JSONArray json = new JSONArray(saved);
            for (int i = 0; i < json.length() && list.size() < 30; i++) {
                JSONObject entry = json.optJSONObject(i);
                if (entry == null) continue;
                String url = entry.optString("url", "");
                String name = entry.optString("name", "");
                if (!name.trim().isEmpty() && isPersistable(url)
                        && !"about:blank".equals(url)) {
                    list.add(new SpeedDial(name.substring(0, Math.min(32, name.length())), url));
                }
            }
        } catch (org.json.JSONException invalid) {
            Log.w(TAG, "Invalid saved Speed Dial configuration; using defaults");
            for (int i = 0; i < SITE_URLS.length; i++) {
                list.add(new SpeedDial(SITE_NAMES[i], SITE_URLS[i]));
            }
        }
        return list;
    }

    private void saveSpeedDials(List<SpeedDial> entries) {
        JSONArray array = new JSONArray();
        for (SpeedDial entry : entries) {
            try {
                array.put(new JSONObject().put("name", entry.name).put("url", entry.url));
            } catch (org.json.JSONException invalid) {
                Log.w(TAG, "Could not encode Speed Dial entry");
            }
        }
        getSharedPreferences(TAB_PREFS, MODE_PRIVATE).edit()
            .putString(SPEED_DIALS_KEY, array.toString()).apply();
        rebuildHome();
    }

    private void rebuildHome() {
        if (browserContent == null) return;
        if (homeOverlay != null) browserContent.removeView(homeOverlay);
        buildHome(browserContent);
        updateHomeState();
    }

    private int shortcutAccent(String name) {
        final int[] swatches = {
            Color.rgb(90, 119, 175), Color.rgb(108, 113, 159),
            Color.rgb(78, 135, 132), Color.rgb(156, 110, 99),
            Color.rgb(126, 104, 153), Color.rgb(126, 132, 98)
        };
        return swatches[(name.hashCode() & 0x7fffffff) % swatches.length];
    }

    private LinearLayout shortcutTile(SpeedDial entry, boolean privateTab) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(3), dp(7), dp(3), dp(4));
        String initial = entry.name.isEmpty() ? "•" : entry.name.substring(0, 1);
        TextView glyph = text(initial, 25, PRIMARY, true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(rounded(shortcutAccent(entry.name), 19, Color.TRANSPARENT));
        cell.addView(glyph, new LinearLayout.LayoutParams(dp(60), dp(60)));
        TextView label = text(entry.name, 12, PRIMARY, false);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(-1, dp(30));
        caption.topMargin = dp(3);
        cell.addView(label, caption);
        cell.setContentDescription(entry.name + " ショートカット");
        cell.setOnClickListener(v -> navigateCurrentTab(entry.url));
        cell.setOnLongClickListener(v -> {
            editSpeedDial(entry);
            return true;
        });
        return cell;
    }

    private LinearLayout addShortcutTile() {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(3), dp(7), dp(3), dp(4));
        ImageView symbol = iconView(R.drawable.ic_browser_plus, SECONDARY);
        symbol.setPadding(dp(17), dp(17), dp(17), dp(17));
        symbol.setBackground(rounded(SURFACE, 19, BORDER));
        cell.addView(symbol, new LinearLayout.LayoutParams(dp(60), dp(60)));
        TextView label = text("追加", 12, SECONDARY, false);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(-1, dp(30));
        labelSize.topMargin = dp(3);
        cell.addView(label, labelSize);
        cell.setContentDescription("ショートカットを追加");
        cell.setOnClickListener(v -> editSpeedDial(null));
        return cell;
    }

    private void editSpeedDial(SpeedDial original) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(22), dp(5), dp(22), dp(2));
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("名前");
        name.setText(original == null ? "" : original.name);
        fields.addView(name, new LinearLayout.LayoutParams(-1, dp(56)));
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("https://example.com/");
        url.setText(original == null ? "" : original.url);
        fields.addView(url, new LinearLayout.LayoutParams(-1, dp(56)));
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setTitle(original == null ? "ショートカットを追加" : "ショートカットを編集")
            .setView(fields)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存", null);
        if (original != null) dialog.setNeutralButton("削除", (d, which) -> {
            List<SpeedDial> entries = readSpeedDials();
            entries.removeIf(e -> e.url.equals(original.url) && e.name.equals(original.name));
            saveSpeedDials(entries);
        });
        AlertDialog popup = dialog.create();
        popup.setOnShowListener(ignored -> popup.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(view -> {
                String trimmedName = name.getText().toString().trim();
                String target = url.getText().toString().trim();
                // A Speed Dial stores only validated HTTP(S) URLs. A search
                // query or JavaScript URL must not become a persistent link.
                if (trimmedName.isEmpty() || !isPersistable(target)
                        || "about:blank".equals(target)) {
                    Toast.makeText(this, "名前とhttp(s)のURLを入力してね",
                        Toast.LENGTH_LONG).show();
                    return;
                }
                List<SpeedDial> entries = readSpeedDials();
                if (original != null) {
                    for (int i = 0; i < entries.size(); i++) {
                        if (entries.get(i).name.equals(original.name)
                                && entries.get(i).url.equals(original.url)) {
                            entries.remove(i);
                            break;
                        }
                    }
                }
                if (entries.size() >= 30) {
                    Toast.makeText(this, "ショートカットは30件まで", Toast.LENGTH_SHORT).show();
                    return;
                }
                entries.add(new SpeedDial(trimmedName.substring(0,
                    Math.min(32, trimmedName.length())), target));
                saveSpeedDials(entries);
                popup.dismiss();
            }));
        popup.show();
    }

    /** Chrome's calm new-tab layout plus Vivaldi's editable Speed Dials. */
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
        body.setPadding(dp(18), dp(46), dp(18), dp(28));
        scroll.addView(body);
        TextView wordmark = text("Velocity", 35, PRIMARY, true);
        wordmark.setLetterSpacing(-0.035f);
        wordmark.setGravity(Gravity.CENTER);
        body.addView(wordmark, new LinearLayout.LayoutParams(-1, dp(70)));
        TextView tagline = text("Browse your way", 12, SECONDARY, false);
        tagline.setGravity(Gravity.CENTER);
        body.addView(tagline, new LinearLayout.LayoutParams(-1, dp(24)));
        Button search = makeButton("検索またはURLを入力", "ウェブを検索", this::focusAddress);
        search.setTextSize(15);
        search.setTextColor(SECONDARY);
        search.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        search.setPadding(dp(18), 0, dp(12), 0);
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_browser_search, 0, 0, 0);
        search.setCompoundDrawableTintList(ColorStateList.valueOf(ACCENT));
        search.setCompoundDrawablePadding(dp(12));
        search.setBackground(rounded(SURFACE, 26, Color.TRANSPARENT));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(56));
        searchLp.topMargin = dp(28);
        body.addView(search, searchLp);

        LinearLayout rowTitle = new LinearLayout(this);
        rowTitle.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-1, dp(60));
        headerLp.topMargin = dp(21);
        body.addView(rowTitle, headerLp);
        TextView section = text("ショートカット", 16, PRIMARY, true);
        rowTitle.addView(section, new LinearLayout.LayoutParams(0, -1, 1));
        Button edit = iconButton(R.drawable.ic_browser_plus, "ショートカットを追加",
            () -> editSpeedDial(null));
        edit.setBackground(rounded(SURFACE, 14, Color.TRANSPARENT));
        rowTitle.addView(edit, new LinearLayout.LayoutParams(dp(42), dp(42)));

        List<SpeedDial> entries = readSpeedDials();
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int columns = widthDp < 360 ? 3 : 4;
        if (getSharedPreferences(TAB_PREFS, MODE_PRIVATE)
                .getBoolean("show_speed_dials", true)) {
            for (int i = 0; i < entries.size() + 1; i += columns) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.TOP);
                body.addView(row, new LinearLayout.LayoutParams(-1, dp(112)));
                for (int col = 0; col < columns; col++) {
                    int index = i + col;
                    View tile = index < entries.size() ? shortcutTile(entries.get(index), false)
                        : index == entries.size() ? addShortcutTile() : new View(this);
                    row.addView(tile, new LinearLayout.LayoutParams(0, dp(108), 1));
                }
            }
        } else {
            TextView empty = text("ショートカットは設定で表示できるよ", 13,
                SECONDARY, false);
            body.addView(empty, new LinearLayout.LayoutParams(-1, dp(55)));
        }
    }
    private void updateHomeState() {
        if (homeOverlay == null) return;
        TabRegistry.Tab current = tabRegistry.current();
        boolean showHome = current != null && "about:blank".equals(current.url);
        homeOverlay.setVisibility(showHome ? View.VISIBLE : View.GONE);
        if (showHome) homeOverlay.bringToFront();
    }

    private void positionActiveExternalSurface() {
        if (destroyed || engine == null || browserHeader == null || browserFooter == null) return;
        FrameLayout surface = engine.getSurfaceContainer();
        if (surface.getParent() != browserContent) {
            positionChromiumSurface(surface, browserHeader, browserFooter);
        }
    }

    private void positionChromiumSurface(FrameLayout surface, View header, View footer) {
        if (destroyed || !(surface.getParent() instanceof FrameLayout)) return;
        boolean footerAbovePage = browserRoot != null && browserContent != null
            && browserRoot.indexOfChild(footer) < browserRoot.indexOfChild(browserContent);
        int top = header.getHeight() + (footerAbovePage ? footer.getHeight() : 0);
        int bottom = footerAbovePage ? 0 : footer.getHeight();
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
                boolean restoreLastTab = getSharedPreferences(TAB_PREFS, MODE_PRIVATE)
                    .getBoolean("restore_last_tab", DEFAULT_RESTORE_LAST_TAB);
                if (restoreLastTab && tabRegistry.current() != null && !tabRegistry.isStartupHomeCurrent()) {
                    // Restore and display the active tab from the previous session.
                } else {
                    tabRegistry.selectStartupHome();
                }
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
                    item.optString("group", null), item.optString("groupId", null));
                if (tab == null) break;
                tab.title = item.optString("title", "");
                tab.desktopMode = item.optBoolean("desktop", false);
                tab.pinned = !tab.startupHome && item.optBoolean("pinned", false);
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
                    .put("groupId", tab.groupKey() == null ? "" : tab.groupKey())
                    .put("desktop", tab.desktopMode)
                    .put("pinned", tab.pinned)
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
        if ("about:blank".equals(url)
                && getSharedPreferences(TAB_PREFS, MODE_PRIVATE)
                    .getBoolean("focus_new_tab", false)) {
            mainHandler.postDelayed(() -> {
                if (!destroyed && !isFinishing()) focusAddress();
            }, 140L);
        }
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
            browserRoot.post(this::positionActiveExternalSurface);
        }
    }

    private CefriumBrowser createEngineFor(TabRegistry.Tab tab) {
        CefriumBrowser browser = CefriumBrowser.createWithSurface(this, tab.privateMode);
        try {
            configureBrowser(browser, tab);
            // A restored tab is instantiated only when the user explicitly
            // selects it. Chromium PageState also restores back/forward history;
            // a dead renderer cannot preserve live JS/DOM state.
            byte[] savedNavigation = reopeningTab != null && reopeningTab.tab.id.equals(tab.id)
                ? reopeningTab.navigation
                : tab.privateMode || tab.startupHome ? null
                    : previewStore.loadNavigationState(tab.id);
            if (savedNavigation != null && savedNavigation.length > 0) {
                try {
                    browser.restoreNavigationState(savedNavigation);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Navigation checkpoint could not be restored", error);
                    if (!tab.privateMode) previewStore.saveNavigationState(tab.id, null);
                    browser.loadUrl(tab.url);
                }
            } else {
                browser.loadUrl(tab.url);
            }
            return browser;
        } catch (RuntimeException | LinkageError error) {
            browser.close();
            throw error;
        }
    }

    /** Configure both ordinary tabs and popup browsers created by Cefrium. */
    private void configureBrowser(CefriumBrowser browser, TabRegistry.Tab tab) {
        attachSurface(browser);
        applyBrowserSettings(browser);
        downloads.bind(browser, tab.privateMode);
        applyTabDisplayPreferences(browser, tab);
        // New 0.21.0 typed request API. Runs on the network thread:
        // never touch UI, and keep matching O(1) / in-memory.
        final AtomicReference<String> currentOriginHost = new AtomicReference<>(
            SiteBlockPolicy.hostOf(tab.url));
        browser.setResourceInterceptor(new CefriumResourceInterceptor() {
            @Override public boolean shouldBlockRequest(CefriumBrowser.ResourceRequest request) {
                if (request.isMainFrame) {
                    currentOriginHost.set(SiteBlockPolicy.hostOf(request.url));
                }
                String topHost = SiteBlockPolicy.hostOf(request.topLevelUrl);
                if (topHost == null) topHost = currentOriginHost.get();
                return blockDomainsEnabled && SiteBlockPolicy.shouldBlockForTopHost(
                    networkRules, request.url, request.isMainFrame, topHost,
                    tab.privateMode ? privateSiteExemptions : normalSiteExemptions);
            }
        });
        // The old URL/loading listeners are deprecated in 0.21.0.
        browser.setNavigationClient(new CefriumNavigationClient() {
            @Override public void onLoadingStateChanged(
                    CefriumBrowser source, boolean loading,
                    boolean canBack, boolean canForward) {
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
                });
            }

            @Override public void onUrlChanged(CefriumBrowser source, String url) {
                runOnUiThread(() -> {
                    if (destroyed || tabRegistry.find(tab.id) != tab || url == null) return;
                    if (source == findBrowser && !url.equals(tab.url)) closePageFind();
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
                        if (!address.hasFocus()) address.setText(displayAddress(url));
                        updateHomeState();
                    }
                    if (!tab.privateMode) {
                        scheduleRegularTabsSave();
                        if (!"about:blank".equals(url)) {
                            historyStore.record(url, tab.displayTitle(), false);
                            saveHistory();
                        }
                    }
                });
            }
        });
        browser.setChromeClient(new CefriumChromeClient() {
            @Override public void onFindResult(CefriumBrowser source, int activeMatch, int matches) {
                runOnUiThread(() -> {
                    if (destroyed || source != findBrowser || source != engine
                            || issuedFindQuery.isEmpty()
                            || !issuedFindQuery.contentEquals(findQuery.getText())) return;
                    if (matches < 0) return;
                    String position = activeMatch > 0 && activeMatch <= matches
                        ? activeMatch + " / " + matches : matches + "件";
                    findCount.setText(position);
                });
            }
            @Override public void onTitleChanged(CefriumBrowser source, String name) {
                runOnUiThread(() -> {
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
                    if (!tab.privateMode && tab.url != null && !tab.url.isEmpty() && !"about:blank".equals(tab.url)) {
                        historyStore.record(tab.url, tab.displayTitle(), false);
                        saveHistory();
                    }
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
                });
            }

            @Override public boolean onCreateWindow(CefriumBrowser source,
                    CefriumBrowser popup, String url, boolean userGesture) {
                return adoptPopupWindow(source, popup, url);
            }

            @Override public void onCloseWindow(CefriumBrowser source,
                    CefriumBrowser popup) {
                popupOpeners.remove(popup);
                String popupId = findTabIdForBrowser(popup);
                if (popupId != null && !destroyed) closeTabDirect(popupId);
            }

            @Override public boolean onContextMenu(CefriumBrowser source,
                    CefriumContextMenu menu) {
                return showNativeContextMenu(source, menu);
            }
        });
    }

    /** Find a currently owned tab without relying on URL equality. */
    private String findTabIdForBrowser(CefriumBrowser browser) {
        for (Map.Entry<String, CefriumBrowser> entry : liveBrowsers.entrySet()) {
            if (entry.getValue() == browser) return entry.getKey();
        }
        return null;
    }

    /**
     * Adopt the engine-provided window, preserving window.opener, the profile
     * and privacy mode. Never loadUrl() here: that would sever popup identity.
     */
    private boolean adoptPopupWindow(CefriumBrowser opener, CefriumBrowser popup,
            String popupUrl) {
        if (destroyed || popup == null || popup.isClosed()) return false;
        String parentId = findTabIdForBrowser(opener);
        TabRegistry.Tab parent = tabRegistry.find(parentId);
        if (parent == null) return false;

        // CEF can initially report a blank URL while the window is opening.
        // Do not persist a site-created non-HTTP URL or turn it into a search.
        String initialUrl = popupUrl == null || popupUrl.isEmpty()
            ? "about:blank" : popupUrl;
        if (!"about:blank".equals(initialUrl)) {
            // OAuth state parameters can produce URLs >2048 characters. Permit
            // a valid HTTP(S) popup in memory even though persistence will
            // intentionally reject an overlong URL.
            try {
                java.net.URI parsed = new java.net.URI(initialUrl);
                if (parsed.getHost() == null || parsed.getUserInfo() != null
                        || (!"https".equalsIgnoreCase(parsed.getScheme())
                            && !"http".equalsIgnoreCase(parsed.getScheme()))) return false;
            } catch (java.net.URISyntaxException invalid) {
                return false;
            }
        }
        TabRegistry.Tab tab = tabRegistry.add(initialUrl, parent.privateMode);
        try {
            configureBrowser(popup, tab);
            liveBrowsers.put(tab.id, popup);
            popupOpeners.put(popup, opener);
            activateCurrentTab();
            saveRegularTabs();
            return true;
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Cannot attach site-created popup", error);
            liveBrowsers.remove(tab.id);
            popupOpeners.remove(popup);
            tabRegistry.close(tab.id);
            activateCurrentTab();
            return false; // Cefrium owns closing a rejected popup.
        }
    }

    /**
     * A contextual bottom sheet for links, images, selections and Cefrium
     * commands. Keep the original engine-owned actions: this is a UI swap,
     * not a JavaScript reimplementation of native edit/media commands.
     */
    private boolean showNativeContextMenu(CefriumBrowser source, CefriumContextMenu menu) {
        if (destroyed || isFinishing()) {
            menu.cancel();
            return true;
        }
        String id = findTabIdForBrowser(source);
        TabRegistry.Tab tab = tabRegistry.find(id);
        if (tab == null) {
            menu.cancel();
            return true;
        }

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        ArrayList<Integer> icons = new ArrayList<>();
        String link = menu.getLinkUrl();
        String imageUrl = menu.getSourceUrl();
        String selection = menu.getSelectionText();

        if (isPersistable(link)) {
            labels.add("リンクを新しいタブで開く");
            actions.add(() -> createTab(link, tab.privateMode));
            icons.add(R.drawable.ic_browser_tabs);
            labels.add("リンクをバックグラウンドで開く");
            actions.add(() -> queueContextLink(link, tab, false));
            icons.add(R.drawable.ic_browser_tabs);
            if (tab.group != null && !tab.group.isBlank()) {
                labels.add("リンクを同じグループで開く");
                actions.add(() -> queueContextLink(link, tab, true));
                icons.add(R.drawable.ic_browser_tabs);
            }
        }
        if (link != null && !link.isEmpty()) {
            labels.add("リンクのURLをコピー");
            actions.add(() -> copyContextValue("URL", link));
            icons.add(R.drawable.ic_browser_copy);
        }
        if (imageUrl != null && !imageUrl.isEmpty()) {
            labels.add("画像・メディアのURLをコピー");
            actions.add(() -> copyContextValue("URL", imageUrl));
            icons.add(R.drawable.ic_browser_image);
        }
        if (selection != null && !selection.isEmpty()) {
            labels.add("選択テキストをコピー");
            actions.add(() -> copyContextValue("テキスト", selection));
            icons.add(R.drawable.ic_browser_copy);
        }
        final int customActionCount = actions.size();

        // Retain every enabled command supplied by Cefrium, including
        // selection/edit operations, saving an image and native copy actions.
        // Avoid exposing engine command IDs or re-fetching any resource URL.
        for (CefriumContextMenu.Item item : menu.getItems()) {
            if (!item.isEnabled()) continue;
            String label = item.getLabel();
            labels.add(label == null || label.isBlank() ? "その他の操作" : label);
            actions.add(item::execute);
            icons.add(R.drawable.ic_browser_menu);
        }
        if (labels.isEmpty()) {
            menu.cancel();
            return true;
        }

        AtomicBoolean settled = new AtomicBoolean(false);
        final Dialog[] dialog = new Dialog[1];
        LinearLayout panel = sheetPanel();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(5), 0, dp(4), dp(5));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(58)));

        String headingText = link != null && !link.isEmpty() ? "リンクの操作"
            : imageUrl != null && !imageUrl.isEmpty() ? "画像の操作"
            : selection != null && !selection.isEmpty() ? "テキストの操作"
            : "ページの操作";
        TextView title = text(headingText, 20, PRIMARY, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        TextView close = text("閉じる", 13, SECONDARY, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("長押しメニューを閉じる");
        close.setMinWidth(dp(48));
        close.setMinHeight(dp(48));
        close.setBackground(new RippleDrawable(
            ColorStateList.valueOf(0x25FFFFFF),
            rounded(ELEVATED, 14, Color.TRANSPARENT), null));
        close.setOnClickListener(v -> dialog[0].dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(62), dp(48)));

        BoundedScrollView scroll = new BoundedScrollView();
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout items = sheetPanel();
        scroll.addView(items);

        if (customActionCount > 0) menuSection(items, "リンク・コンテンツ");
        for (int index = 0; index < labels.size(); index++) {
            if (index == customActionCount && index < labels.size()) {
                menuSection(items, "その他の操作");
            }
            final int actionIndex = index;
            addMenuItem(items, icons.get(index), labels.get(index), null, () -> {
                // Dismissing the sheet triggers the cancel callback. Mark
                // the choice settled first so SDK actions are not canceled.
                if (!settled.compareAndSet(false, true)) return;
                dialog[0].dismiss();
                if (actionIndex < customActionCount) menu.cancel();
                actions.get(actionIndex).run();
            });
        }

        // FLAG_SECURE is installed before the window becomes visible. On
        // outside tap, swipe/back or window teardown, cancel the engine menu
        // exactly once; don't cancel an already-executed Cefrium action.
        dialog[0] = bottomSheet(panel, tab.privateMode, dismissed -> {
            if (settled.compareAndSet(false, true)) menu.cancel();
        });
        return true;
    }

    private void queueContextLink(String link, TabRegistry.Tab opener, boolean sameGroup) {
        if (destroyed || !chromiumReady || !isPersistable(link)
                || (sameGroup && (opener.group == null || opener.group.isBlank()))) return;
        TabRegistry.Tab queued = tabRegistry.addBackground(link, opener, sameGroup);
        if (queued == null) return;
        // No renderer is created and no request is sent until the tab is selected.
        if (!queued.privateMode) saveRegularTabs();
        updateNavigation();
        Toast.makeText(this, sameGroup ? "同じグループに追加しました"
            : "バックグラウンドに追加しました", Toast.LENGTH_SHORT).show();
    }

    private void copyContextValue(String label, String value) {
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
        Toast.makeText(this, label + "をコピーしたよ", Toast.LENGTH_SHORT).show();
    }

    private void activateCurrentTab() {
        if (!chromiumReady || destroyed) return;
        clearAddressSuggestions();
        TabRegistry.Tab current = tabRegistry.current();
        if (current == null) return;
        CefriumBrowser oldEngine = engine;
        CefriumBrowser active = liveBrowsers.get(current.id);
        if (findBrowser != null && (findBrowser != active || current.startupHome)) closePageFind();
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
            positionActiveExternalSurface();
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

    private int residentBrowserTarget() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastMemorySampleAt < MEMORY_SAMPLE_INTERVAL_MS) return residentBrowserTarget;
        lastMemorySampleAt = now;
        android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
        if (manager != null) {
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            residentBrowserTarget = ResidentTabBudget.target(
                info.totalMem, manager.isLowRamDevice(), info.lowMemory);
        }
        return residentBrowserTarget;
    }

    /** Hibernate inactive WebContents, preferring the offline home over a live page. */
    private void trimInactiveBrowsers(String currentId) {
        int target = residentBrowserTarget();
        while (liveBrowsers.size() > target) {
            String oldestId = null;
            for (Map.Entry<String, CefriumBrowser> candidate : liveBrowsers.entrySet()) {
                String id = candidate.getKey();
                CefriumBrowser browser = candidate.getValue();
                if (id.equals(currentId) || downloads.isBusy(browser)
                        || popupOpeners.containsValue(browser) || popupOpeners.containsKey(browser)) {
                    continue;
                }
                if (oldestId == null) oldestId = id;
                TabRegistry.Tab tab = tabRegistry.find(id);
                if (tab != null && tab.startupHome) { oldestId = id; break; }
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

    /** Debug-only read-only WindowInsets evidence: never intercept dispatch or double-pad. */
    private void logInsetsForAudit(View root) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0
                || Build.VERSION.SDK_INT < 30) return;
        android.view.WindowInsets insets = root.getRootWindowInsets();
        if (insets == null) {
            Log.i(TAG, "CEFInsetsAudit unavailable");
            return;
        }
        android.graphics.Insets bars = insets.getInsets(
            android.view.WindowInsets.Type.systemBars()
                | android.view.WindowInsets.Type.displayCutout());
        android.graphics.Insets ime = insets.getInsets(
            android.view.WindowInsets.Type.ime());
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        Log.i(TAG, "CEFInsetsAudit bars=" + bars.toString()
            + " ime=" + ime.toString()
            + " imeVisible=" + insets.isVisible(android.view.WindowInsets.Type.ime())
            + " root=" + location[0] + "," + location[1]
            + "," + root.getWidth() + "x" + root.getHeight());
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
                address.setText(displayAddress(tab.url));
            }
            if (locationIdentity != null && !address.hasFocus()) {
                locationIdentity.setImageResource(tab.url.equals("about:blank")
                    ? R.drawable.ic_browser_search : R.drawable.ic_browser_globe);
                locationIdentity.setColorFilter(tab.privateMode ? SECONDARY : ACCENT);
            }
        }
    }

    /** The location field is readable by default, editable in full on focus. */
    private String displayAddress(String url) {
        if (url == null || url.equals("about:blank")) return "";
        try {
            android.net.Uri parsed = android.net.Uri.parse(url);
            String host = parsed.getHost();
            if (host != null && !host.isEmpty()) {
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (RuntimeException ignored) {
            // Internal synthetic fixtures or partial URLs keep a safe fallback.
        }
        return url.length() > 80 ? url.substring(0, 80) : url;
    }

    private void loadBookmarks() {
        String json = getSharedPreferences(TAB_PREFS, MODE_PRIVATE).getString("bookmarks_v1", "[]");
        bookmarkStore.deserialize(json);
    }

    private void saveBookmarks() {
        getSharedPreferences(TAB_PREFS, MODE_PRIVATE).edit()
            .putString("bookmarks_v1", bookmarkStore.serialize()).apply();
    }

    private long readerRequestId;

    private void showReaderText() {
        TabRegistry.Tab tab = tabRegistry.current();
        if (tab == null || tab.privateMode || !ReadingList.supports(tab.url)) return;
        CefriumBrowser browser = engine;
        if (browser == null) return;
        String url = tab.url;
        String title = tab.displayTitle();
        long request = ++readerRequestId;
        browser.evaluateJavaScript(ReaderText.SCRIPT, result -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || readerRequestId != request
                    || tabRegistry.current() != tab || browser != engine || !url.equals(tab.url)) {
                return;
            }
            if (result == null || "null".equals(result)) {
                Toast.makeText(this, "読み取れる本文が見つかりませんでした", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                org.json.JSONObject value = new org.json.JSONObject(result);
                if (!url.equals(value.optString("url"))) return;
                String prose = value.optString("text");
                if (prose.length() < ReaderText.MIN_TEXT || prose.length() > ReaderText.MAX_TEXT) {
                    return;
                }
                // Render only plain text in a native view. No second web request or page HTML.
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                TextView article = new TextView(this);
                article.setText(prose);
                article.setTextSize(18);
                article.setLineSpacing(dp(5), 1f);
                article.setPadding(dp(20), dp(12), dp(20), dp(24));
                article.setTextIsSelectable(true);
                scroll.addView(article);
                new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                    .setPositiveButton("元のページに戻る", null).show();
            } catch (org.json.JSONException invalid) {
                Toast.makeText(this, "本文を表示できませんでした", Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void loadReadingList() {
        try {
            org.json.JSONArray data = new org.json.JSONArray(
                getSharedPreferences(TAB_PREFS, MODE_PRIVATE).getString("reading_list_v1", "[]"));
            for (int i = Math.min(data.length(), ReadingList.LIMIT) - 1; i >= 0; i--) {
                org.json.JSONObject row = data.optJSONObject(i);
                if (row == null) continue;
                String url = row.optString("url", "");
                if (readingList.add(url, row.optString("title", "")) == ReadingList.AddResult.ADDED) {
                    readingList.setRead(url, row.optBoolean("read", false));
                }
            }
        } catch (org.json.JSONException | ClassCastException invalid) {
            Log.w(TAG, "Invalid reading list");
        }
    }

    private void saveReadingList() {
        org.json.JSONArray data = new org.json.JSONArray();
        try {
            for (ReadingList.Item row : readingList.all()) {
                data.put(new org.json.JSONObject().put("url", row.url())
                    .put("title", row.title()).put("read", row.read()));
            }
            getSharedPreferences(TAB_PREFS, MODE_PRIVATE).edit()
                .putString("reading_list_v1", data.toString()).apply();
        } catch (org.json.JSONException invalid) {
            Log.w(TAG, "Could not save reading list");
        }
    }

    private void addPageToReadingList() {
        TabRegistry.Tab current = tabRegistry.current();
        if (current == null || current.privateMode) return;
        ReadingList.AddResult result = readingList.add(current.url, current.displayTitle());
        if (result == ReadingList.AddResult.ADDED) saveReadingList();
        Toast.makeText(this, switch (result) {
            case ADDED -> "読書リストに追加しました";
            case ALREADY_SAVED -> "読書リストに追加済みです";
            case FULL -> "読書リストは100件まで保存できます";
            case UNSUPPORTED -> "このページは保存できません";
        }, Toast.LENGTH_SHORT).show();
    }

    private void showReadingList() {
        TabRegistry.Tab current = tabRegistry.current();
        if (current != null && current.privateMode) return;
        List<ReadingList.Item> entries = new ArrayList<>(readingList.all());
        entries.sort(java.util.Comparator.comparing(ReadingList.Item::read));
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("読書リスト")
                .setMessage("保存したページはまだありません")
                .setPositiveButton("閉じる", null).show();
            return;
        }
        String[] titles = entries.stream().map(row -> (row.read() ? "既読： " : "") + row.title()
            + "\n" + safeDisplayHost(row.url())).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("読書リスト（この端末）")
            .setItems(titles, (dialog, index) -> showReadingListItem(entries.get(index)))
            .setNegativeButton("閉じる", null).show();
    }

    private String safeDisplayHost(String url) {
        String host = android.net.Uri.parse(url).getHost();
        return host == null ? "" : host;
    }

    private void showReadingListItem(ReadingList.Item row) {
        new AlertDialog.Builder(this).setTitle(row.title())
            .setItems(new String[]{"開く", row.read() ? "未読に戻す" : "既読にする", "リストから外す"},
                (dialog, choice) -> {
                    TabRegistry.Tab current = tabRegistry.current();
                    if (current != null && current.privateMode) return;
                    if (choice == 0) {
                        navigateCurrentTab(row.url());
                    } else {
                        if (choice == 1) readingList.setRead(row.url(), !row.read());
                        else readingList.remove(row.url());
                        saveReadingList();
                        showReadingList();
                    }
                })
            .setNegativeButton("戻る", (d, w) -> showReadingList()).show();
    }

    private void toggleBookmarkCurrent() {
        TabRegistry.Tab current = tabRegistry.current();
        if (current == null || current.url == null || "about:blank".equals(current.url)
                || current.privateMode) {
            Toast.makeText(this, current != null && current.privateMode
                ? "非公開タブのページはブックマークできません"
                : "このページはブックマークできません", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bookmarkStore.isBookmarked(current.url)) {
            bookmarkStore.removeByUrl(current.url);
            saveBookmarks();
            Toast.makeText(this, "ブックマークを解除しました", Toast.LENGTH_SHORT).show();
        } else {
            bookmarkStore.add(current.url, current.displayTitle());
            saveBookmarks();
            Toast.makeText(this, "ブックマークに追加しました", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void pickBookmarkFile(boolean exporting) {
        Intent picker = new Intent(exporting ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        if (exporting) {
            picker.setType("application/json");
            picker.putExtra(Intent.EXTRA_TITLE, "velocity-bookmarks.json");
        } else {
            // Some providers store exported JSON as text/plain or application/octet-stream.
            // Decode checks the file's own format, independently of its MIME type.
            picker.setType("*/*");
        }
        try {
            startActivityForResult(picker, exporting ? EXPORT_BOOKMARKS_REQUEST : IMPORT_BOOKMARKS_REQUEST);
        } catch (android.content.ActivityNotFoundException unavailable) {
            Toast.makeText(this, "ファイル選択を開けませんでした", Toast.LENGTH_SHORT).show();
        }
    }

    private void openBookmarkTransferMenu() {
        new AlertDialog.Builder(this)
            .setTitle("ブックマークのバックアップ")
            .setItems(new String[]{"ファイルから追加", "ファイルに保存"}, (dialog, choice) -> {
                if (choice == 0) {
                    new AlertDialog.Builder(this)
                        .setTitle("ブックマークをファイルから追加")
                        .setMessage("VelocityのJSON、またはブラウザが書き出した対応形式のHTMLを選べます。既存の登録は上書きしません。HTMLからはURLと名前だけを追加し、フォルダ構造は引き継ぎません。")
                        .setPositiveButton("ファイルを選ぶ", (d, w) -> pickBookmarkFile(false))
                        .setNegativeButton("キャンセル", null).show();
                } else {
                    new AlertDialog.Builder(this)
                        .setTitle("ブックマークをファイルに保存")
                        .setMessage("Velocity用のバックアップにURLとタイトルを保存します。ログイン情報や閲覧履歴は含みません。URLに個人情報が入っている場合があるため、共有先に注意してください。")
                        .setPositiveButton("保存先を選ぶ", (d, w) -> pickBookmarkFile(true))
                        .setNegativeButton("キャンセル", null).show();
                }
            }).setNegativeButton("キャンセル", null).show();
    }

    private void transferBookmarkFile(boolean exporting, android.net.Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            Toast.makeText(this, "選択したファイルを開けませんでした", Toast.LENGTH_LONG).show();
            return;
        }
        final List<BookmarkStore.Bookmark> snapshot = exporting ? bookmarkStore.all() : null;
        bookmarkFileExecutor.execute(() -> {
            try {
                if (exporting) {
                    BookmarkTransferFile.Encoded file = BookmarkTransferFile.encode(snapshot);
                    try (java.io.OutputStream stream = getContentResolver().openOutputStream(uri, "w")) {
                        if (stream == null) throw new java.io.IOException("No output stream");
                        stream.write(file.bytes);
                    }
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        Toast.makeText(this, file.count + "件を保存、" + file.skipped + "件は対象外",
                            Toast.LENGTH_LONG).show();
                    });
                } else {
                    BookmarkTransferFile.Parsed parsed;
                    try (java.io.InputStream stream = getContentResolver().openInputStream(uri)) {
                        String contents = BookmarkTransferLimits.readUtf8(stream);
                        parsed = NetscapeBookmarkImport.looksLikeExport(contents)
                            ? NetscapeBookmarkImport.decode(contents) : BookmarkTransferFile.decode(contents);
                    }
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        int added = bookmarkStore.mergeTransferred(parsed.items);
                        if (added > 0) saveBookmarks();
                        int duplicates = parsed.items.size() - added;
                        Toast.makeText(this, added + "件を追加、重複" + duplicates + "件、対象外"
                            + parsed.skipped + "件", Toast.LENGTH_LONG).show();
                        showBookmarks();
                    });
                }
            } catch (java.io.IOException | RuntimeException failure) {
                // The selected URI and bookmark data can be private: do not log the error text.
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    Toast.makeText(this, exporting ? "保存できませんでした。最大1,000件・2 MBです。"
                        : "読み込めませんでした。対応するブックマークのJSONまたはHTML（最大1,000件・2 MB）を選んでください。",
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showBookmarks() {
        showBookmarks("");
    }

    private void showBookmarks(String initialQuery) {
        LinearLayout panel = sheetPanel();
        final Dialog[] dialog = new Dialog[1];

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(56)));

        TextView title = text("ブックマーク", 20, PRIMARY, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        Button close = iconButton(R.drawable.ic_browser_close, "閉じる", () -> dialog[0].dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(PRIMARY);
        search.setHintTextColor(SECONDARY);
        search.setHint("ブックマークを検索");
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.setBackground(rounded(ELEVATED, 10, Color.TRANSPARENT));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(44));
        searchLp.setMargins(dp(12), 0, dp(12), dp(8));
        panel.addView(search, searchLp);

        Button backup = makeButton("バックアップ", "ブックマークの読み込み・保存", () -> {
            dialog[0].dismiss();
            openBookmarkTransferMenu();
        });
        LinearLayout.LayoutParams backupLp = new LinearLayout.LayoutParams(-1, dp(44));
        backupLp.setMargins(dp(12), 0, dp(12), 0);
        panel.addView(backup, backupLp);

        BoundedScrollView scroll = new BoundedScrollView();
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout items = sheetPanel();
        scroll.addView(items);

        Runnable render = () -> {
            items.removeAllViews();
            String q = search.getText().toString();
            List<BookmarkStore.Bookmark> list = bookmarkStore.search(q);
            if (list.isEmpty()) {
                TextView empty = text(q.trim().isEmpty() ? "ブックマークはまだありません" : "一致するブックマークがありません", 14, SECONDARY, false);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(12), dp(40), dp(12), dp(40));
                items.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            } else {
                for (BookmarkStore.Bookmark b : list) {
                    LinearLayout row = new LinearLayout(this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(8), dp(8), dp(8), dp(8));

                    ImageView icon = iconView(R.drawable.ic_browser_globe, SECONDARY);
                    row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

                    LinearLayout labels = new LinearLayout(this);
                    labels.setOrientation(LinearLayout.VERTICAL);
                    labels.setPadding(dp(8), 0, dp(8), 0);

                    TextView name = text(b.title, 14, PRIMARY, false);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    labels.addView(name);

                    TextView urlText = text(b.url, 11, SECONDARY, false);
                    urlText.setSingleLine(true);
                    urlText.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    labels.addView(urlText);

                    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                    row.setOnClickListener(v -> {
                        dialog[0].dismiss();
                        navigateCurrentTab(b.url);
                    });

                    Button del = iconButton(R.drawable.ic_browser_close, "削除", () -> {
                        bookmarkStore.removeById(b.id);
                        saveBookmarks();
                        dialog[0].dismiss();
                        showBookmarks(search.getText().toString());
                    });
                    row.addView(del, new LinearLayout.LayoutParams(dp(48), dp(48)));

                    items.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
            }
        };

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });

        if (initialQuery != null && !initialQuery.isEmpty()) {
            search.setText(initialQuery);
        } else {
            render.run();
        }

        dialog[0] = bottomSheet(panel);
    }

    private void loadHistory() {
        String json = getSharedPreferences(TAB_PREFS, MODE_PRIVATE).getString("history_v1", "[]");
        historyStore.deserialize(json);
    }

    private void saveHistory() {
        getSharedPreferences(TAB_PREFS, MODE_PRIVATE).edit()
            .putString("history_v1", historyStore.serialize()).apply();
    }

    private void showHistory() {
        showHistory("");
    }

    private void showHistory(String initialQuery) {
        LinearLayout panel = sheetPanel();
        final Dialog[] dialog = new Dialog[1];

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(56)));

        TextView title = text("閲覧履歴", 20, PRIMARY, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        Button clear = iconButton(R.drawable.ic_browser_close, "すべて消去", () -> {
            new AlertDialog.Builder(this)
                .setTitle("履歴を消去")
                .setMessage("すべての閲覧履歴を消去する？")
                .setPositiveButton("消去", (d, w) -> {
                    historyStore.clear();
                    saveHistory();
                    dialog[0].dismiss();
                    showHistory();
                })
                .setNegativeButton("キャンセル", null)
                .show();
        });
        heading.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));

        Button close = iconButton(R.drawable.ic_browser_close, "閉じる", () -> dialog[0].dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(PRIMARY);
        search.setHintTextColor(SECONDARY);
        search.setHint("閲覧履歴を検索");
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.setBackground(rounded(ELEVATED, 10, Color.TRANSPARENT));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(44));
        searchLp.setMargins(dp(12), 0, dp(12), dp(8));
        panel.addView(search, searchLp);

        BoundedScrollView scroll = new BoundedScrollView();
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout items = sheetPanel();
        scroll.addView(items);

        Runnable render = () -> {
            items.removeAllViews();
            String q = search.getText().toString();
            List<HistoryStore.HistoryEntry> list = historyStore.search(q);
            if (list.isEmpty()) {
                TextView empty = text(q.trim().isEmpty() ? "履歴はまだありません" : "一致する履歴がありません", 14, SECONDARY, false);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(12), dp(40), dp(12), dp(40));
                items.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            } else {
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault());
                for (HistoryStore.HistoryEntry e : list) {
                    LinearLayout row = new LinearLayout(this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(8), dp(8), dp(8), dp(8));

                    ImageView icon = iconView(R.drawable.ic_browser_history, SECONDARY);
                    row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

                    LinearLayout labels = new LinearLayout(this);
                    labels.setOrientation(LinearLayout.VERTICAL);
                    labels.setPadding(dp(8), 0, dp(8), 0);

                    TextView name = text(e.title, 14, PRIMARY, false);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    labels.addView(name);

                    TextView urlText = text(e.url + " · " + format.format(new java.util.Date(e.visitedAt)), 11, SECONDARY, false);
                    urlText.setSingleLine(true);
                    urlText.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    labels.addView(urlText);

                    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                    row.setOnClickListener(v -> {
                        dialog[0].dismiss();
                        navigateCurrentTab(e.url);
                    });

                    Button del = iconButton(R.drawable.ic_browser_close, "削除", () -> {
                        historyStore.removeById(e.id);
                        saveHistory();
                        dialog[0].dismiss();
                        showHistory(search.getText().toString());
                    });
                    row.addView(del, new LinearLayout.LayoutParams(dp(48), dp(48)));

                    items.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
            }
        };

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });

        if (initialQuery != null && !initialQuery.isEmpty()) {
            search.setText(initialQuery);
        } else {
            render.run();
        }

        dialog[0] = bottomSheet(panel);
    }

    private void showCurrentSiteInfo() {
        TabRegistry.Tab tab = tabRegistry.current();
        if (tab == null || tab.url.equals("about:blank")) {
            focusAddress();
            return;
        }
        android.net.Uri parsed = android.net.Uri.parse(tab.url);
        String host = parsed.getHost() == null ? "Webページ" : parsed.getHost();
        boolean secure = "https".equalsIgnoreCase(parsed.getScheme());
        new AlertDialog.Builder(this).setTitle(host)
            .setMessage((secure ? "HTTPSで接続" : "暗号化されていない可能性がある接続")
                + "\n\nURL: " + tab.url
                + "\n\nサイトの許可と証明書検証はChromium側で処理されるよ。")
            .setPositiveButton("閉じる", null).show();
    }

    private void goHome() {
        if (tabRegistry.selectStartupHome() != null) {
            hideKeyboard();
            address.clearFocus();
            activateCurrentTab();
            saveRegularTabs();
        }
    }

    private boolean addressBarAtBottom() {
        return getSharedPreferences(TAB_PREFS, MODE_PRIVATE)
            .getBoolean("address_bar_bottom", true);
    }

    private void applyBrowserBarPosition() {
        if (browserRoot == null || browserFooter == null || browserContent == null) return;
        int footerIndex = browserRoot.indexOfChild(browserFooter);
        int contentIndex = browserRoot.indexOfChild(browserContent);
        boolean bottom = addressBarAtBottom();
        if (footerIndex < 0 || contentIndex < 0) return;
        boolean alreadyBelow = footerIndex > contentIndex;
        if (alreadyBelow == bottom) return;
        browserRoot.removeView(browserFooter);
        // A hidden internal header occupies index 0.
        browserRoot.addView(browserFooter, bottom ? browserRoot.getChildCount() : 1,
            new LinearLayout.LayoutParams(-1, -2));
        browserRoot.post(this::positionActiveExternalSurface);
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

    private void clearAddressSuggestions() {
        mainHandler.removeCallbacks(refreshAddressSuggestions);
        if (addressSuggestions == null) return;
        addressSuggestions.setVisibility(View.GONE);
        addressSuggestions.removeAllViews();
    }

    private void updateAddressSuggestions() {
        clearAddressSuggestions();
        TabRegistry.Tab current = tabRegistry.current();
        if (destroyed || address == null || !address.hasFocus()
                || current == null || current.privateMode) return;
        String input = address.getText().toString();
        if (input.trim().length() < 2 || input.equals(current.url)) return;
        List<AddressSuggestions.Item> local = new ArrayList<>();
        for (TabRegistry.Tab tab : tabRegistry.regularTabs()) {
            if (tab != current && isPersistable(tab.url)) {
                local.add(new AddressSuggestions.Item(
                    tab.url, tab.displayTitle(), tab.id, "開いているタブ"));
            }
        }
        for (BookmarkStore.Bookmark bookmark : bookmarkStore.all()) {
            if (isPersistable(bookmark.url)) {
                local.add(new AddressSuggestions.Item(
                    bookmark.url, bookmark.title, null, "ブックマーク"));
            }
        }
        for (HistoryStore.HistoryEntry entry : historyStore.all()) {
            if (isPersistable(entry.url)) {
                local.add(new AddressSuggestions.Item(entry.url, entry.title, null, "履歴"));
            }
        }
        List<AddressSuggestions.Item> matches = AddressSuggestions.choose(input, local, false);
        if (matches.isEmpty()) return;
        for (AddressSuggestions.Item match : matches) {
            String label = match.title() == null || match.title().isBlank()
                ? displayAddress(match.url()) : match.title();
            addMenuItem(addressSuggestions, R.drawable.ic_browser_search, label,
                match.source() + " · " + displayAddress(match.url()), () -> {
                    TabRegistry.Tab active = tabRegistry.current();
                    if (active == null || active != current || active.privateMode) return;
                    TabRegistry.Tab open = tabRegistry.find(match.openTabId());
                    if (open != null && !open.privateMode && match.url().equals(open.url)) {
                        hideKeyboard();
                        address.clearFocus();
                        selectTab(open.id);
                    } else if (navigateCurrentTab(match.url())) {
                        hideKeyboard();
                        address.clearFocus();
                        updateNavigation();
                    }
                });
        }
        addressSuggestions.setVisibility(View.VISIBLE);
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
        return bottomSheet(panel, false, null);
    }

    /**
     * Optional privacy and dismissal hooks for engine-owned context menus.
     * Set FLAG_SECURE before show() so a private sheet never flashes in screenshots.
     */
    private Dialog bottomSheet(LinearLayout panel, boolean privateMode,
            android.content.DialogInterface.OnDismissListener onDismiss) {
        Dialog sheet = new Dialog(this);
        if (onDismiss != null) sheet.setOnDismissListener(onDismiss);
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
            if (privateMode) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
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

    // Tab switcher modes are deliberately UI-only. A private tab never enters
    // the normal/recently-closed sections or disk-backed screenshot cache.
    private static final int TAB_VIEW_NORMAL = 0;
    private static final int TAB_VIEW_PRIVATE = 1;
    private static final int TAB_VIEW_GROUPS = 2;
    private static final int TAB_VIEW_RECENT = 3;
    private static final int TAB_SWITCHER_ACCENT = Color.rgb(126, 169, 244);
    private static final int TAB_SWITCHER_BACKGROUND = Color.rgb(22, 23, 28);
    private static final int TAB_SWITCHER_CARD = Color.rgb(48, 51, 59);
    private static final int TAB_SWITCHER_PREVIEW = Color.rgb(37, 39, 46);

    /**
     * A full-height native tab screen, without creating a second Activity or
     * abandoning the current Chromium WebContents. Cards and group mosaics are
     * generated from stable TabRegistry IDs, so 400+ tabs don't force WebViews
     * or page reloads. Only real cached compositor images are ever shown.
     */
    private void showTabs() {
        TabRegistry.Tab current = tabRegistry.current();
        showTabOverview(current != null && current.privateMode
            ? TAB_VIEW_PRIVATE : TAB_VIEW_NORMAL, null);
    }

    private void showTabs(boolean privateMode) {
        showTabOverview(privateMode ? TAB_VIEW_PRIVATE : TAB_VIEW_NORMAL, null);
    }

    private Dialog fullScreenTabsDialog(LinearLayout content) {
        Dialog screen = new Dialog(this);
        screen.requestWindowFeature(Window.FEATURE_NO_TITLE);
        content.setBackgroundColor(TAB_SWITCHER_BACKGROUND);
        screen.setContentView(content);
        Window window = screen.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(TAB_SWITCHER_BACKGROUND));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(TAB_SWITCHER_BACKGROUND);
            window.setNavigationBarColor(TAB_SWITCHER_BACKGROUND);
            if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        }
        screen.show();
        if (window != null) {
            window.setGravity(Gravity.CENTER);
            window.setLayout(-1, -1);
        }
        return screen;
    }

    private record OverviewEntry(String key, java.util.function.Supplier<View> view) {}

    private void showTabOverview(int mode, String expandedGroup) {
        showTabOverview(mode, expandedGroup, "");
    }

    /** Search text survives a close/repaint only within this privacy mode. */
    private void showTabOverview(int mode, String expandedGroup, String initialSearch) {
        if (!chromiumReady || destroyed) return;
        // Do this before opening the dialog. Android PixelCopy must never copy
        // the screen after a sheet, private page, or another tab is on top.
        TabRegistry.Tab focused = tabRegistry.current();
        if (focused != null && !focused.privateMode && !focused.startupHome
                && mode != TAB_VIEW_PRIVATE) {
            captureVisibleTabPreview(focused, null);
        }
        boolean privateMode = mode == TAB_VIEW_PRIVATE;
        int normalCount = tabRegistry.countForMode(false);
        int privateCount = tabRegistry.countForMode(true);
        Map<String, List<TabRegistry.Tab>> namedGroups = new LinkedHashMap<>();
        for (TabRegistry.Tab tab : tabRegistry.tabsForMode(privateMode)) {
            String key = tab.groupKey();
            if (key != null) namedGroups.computeIfAbsent(key, unused -> new ArrayList<>()).add(tab);
        }
        List<TabRegistry.Tab> expandedMembers = namedGroups.get(expandedGroup);
        String headingText = expandedGroup != null
            ? (expandedMembers == null ? "グループ" : expandedMembers.get(0).group)
            : mode == TAB_VIEW_PRIVATE ? "非公開タブ (" + privateCount + ")"
            : mode == TAB_VIEW_GROUPS ? "グループ (" + namedGroups.size() + ")"
            : mode == TAB_VIEW_RECENT ? "最近閉じたタブ"
            : "タブ (" + normalCount + ")";

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] dialog = new Dialog[1];

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(9), dp(11), dp(8));
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));
        if (expandedGroup != null) {
            Button back = iconButton(R.drawable.ic_browser_back, "タブ一覧に戻る", () -> {
                dialog[0].dismiss();
                showTabOverview(mode, null);
            });
            header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        TextView heading = text(headingText, 24, PRIMARY, false);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(0, -1, 1);
        headingLp.leftMargin = dp(4);
        header.addView(heading, headingLp);
        // Search runs on tab metadata only; it never inspects live WebContents,
        // loads pages, or crosses the current privacy-mode boundary.
        EditText searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(15);
        searchInput.setTextColor(PRIMARY);
        searchInput.setHintTextColor(SECONDARY);
        searchInput.setHint("タイトル・URL・グループを検索");
        searchInput.setContentDescription("タブを検索する文字列");
        searchInput.setSelectAllOnFocus(false);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(dp(14), 0, dp(10), 0);
        searchInput.setBackground(rounded(TAB_SWITCHER_CARD, 15, Color.TRANSPARENT));
        searchInput.setVisibility(View.GONE);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, dp(45), 1);
        searchLp.leftMargin = dp(5);
        header.addView(searchInput, searchLp);
        Button options = iconButton(R.drawable.ic_browser_menu, "タブ一覧のメニュー", () ->
            showTabOverviewMenu(dialog[0], mode));
        header.addView(options, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button searchToggle = iconButton(R.drawable.ic_browser_search, "タブを検索", () -> {
            boolean open = searchInput.getVisibility() != View.VISIBLE;
            heading.setVisibility(open ? View.GONE : View.VISIBLE);
            options.setVisibility(open ? View.GONE : View.VISIBLE);
            searchInput.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) {
                searchInput.requestFocus();
                searchInput.post(() -> {
                    InputMethodManager ime = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (ime != null && dialog[0] != null && dialog[0].isShowing()) {
                        ime.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
                    }
                });
            } else {
                searchInput.setText("");
                searchInput.clearFocus();
                InputMethodManager ime = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
                if (ime != null) ime.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
        });
        header.addView(searchToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button dismiss = iconButton(R.drawable.ic_browser_close, "タブ一覧を閉じる", () ->
            dialog[0].dismiss());
        header.addView(dismiss, new LinearLayout.LayoutParams(dp(48), dp(48)));
        // The current query belongs to this one dialog; switching between
        // normal/private tabs creates a fresh, empty search input.
        if (initialSearch != null && !initialSearch.isEmpty()) {
            searchInput.setText(initialSearch);
            heading.setVisibility(View.GONE);
            options.setVisibility(View.GONE);
            searchInput.setVisibility(View.VISIBLE);
        }
        TextView resultCount = text("", 12, SECONDARY, false);
        resultCount.setPadding(dp(17), 0, dp(14), 0);
        resultCount.setGravity(Gravity.CENTER_VERTICAL);
        resultCount.setVisibility(View.GONE);
        page.addView(resultCount, new LinearLayout.LayoutParams(-1, dp(29)));

        FrameLayout body = new FrameLayout(this);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        ListView scroll = new ListView(this);
        scroll.setItemsCanFocus(true);
        scroll.setDivider(null);
        scroll.setDividerHeight(0);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(14), dp(8), dp(14), dp(90));
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        TextView empty = text("", 15, SECONDARY, false);
        empty.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        empty.setPadding(dp(12), dp(80), dp(12), dp(80));
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        scroll.setEmptyView(empty);
        int availableDp = BrowserUiLayout.availableSheetWidthDp(
            getResources().getConfiguration().screenWidthDp);
        int columns = BrowserUiLayout.columnsForAvailableWidthDp(availableDp);
        int previewHeight = BrowserUiLayout.tabPreviewHeightDp(columns);
        // Only the platform's visible/recycled rows contain views. All other
        // results remain metadata and never instantiate browser renderers.
        List<OverviewEntry> entries = new ArrayList<>();
        int cellHeightDp = mode == TAB_VIEW_RECENT ? 80 : previewHeight + 58;
        int rowHeightPx = dp(cellHeightDp + 12);
        BaseAdapter rowsAdapter = new BaseAdapter() {
            @Override public int getCount() { return (entries.size() + columns - 1) / columns; }
            @Override public Object getItem(int row) { return entries.get(row * columns); }
            @Override public long getItemId(int row) { return row; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int row) { return false; }
            @Override public View getView(int rowIndex, View reusable, ViewGroup parent) {
                LinearLayout row;
                if (reusable instanceof LinearLayout) {
                    row = (LinearLayout) reusable;
                } else {
                    row = new LinearLayout(ChromiumTestActivity.this);
                    row.setGravity(Gravity.TOP);
                    row.setLayoutParams(new AbsListView.LayoutParams(-1, rowHeightPx));
                    for (int i = 0; i < columns; i++) {
                        FrameLayout holder = new FrameLayout(ChromiumTestActivity.this);
                        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(cellHeightDp), 1);
                        if (i != 0) cell.leftMargin = dp(BrowserUiLayout.CARD_GAP_DP);
                        row.addView(holder, cell);
                    }
                }
                for (int i = 0; i < columns; i++) {
                    FrameLayout holder = (FrameLayout) row.getChildAt(i);
                    holder.removeAllViews();
                    int entry = rowIndex * columns + i;
                    if (entry < entries.size()) holder.addView(entries.get(entry).view().get(),
                        new FrameLayout.LayoutParams(-1, -1));
                }
                return row;
            }
        };
        scroll.setAdapter(rowsAdapter);
        final boolean[] firstRender = {true};
        Runnable renderGrid = () -> {
            if (destroyed || dialog[0] == null || !dialog[0].isShowing()) return;
            String query = searchInput.getText().toString();
            boolean searching = !query.trim().isEmpty();
            int oldIndex = scroll.getFirstVisiblePosition() * columns;
            String anchor = oldIndex >= 0 && oldIndex < entries.size()
                ? entries.get(oldIndex).key() : null;
            View oldFirstRow = scroll.getChildAt(0);
            int oldOffset = oldFirstRow == null ? 0 : oldFirstRow.getTop() - scroll.getPaddingTop();
            entries.clear();
            int activePosition = -1;
            if (mode == TAB_VIEW_RECENT) {
                for (TabRegistry.Tab closed : TabSearch.filter(recentlyClosedRegularTabs, query)) {
                    entries.add(new OverviewEntry(closed.id, () -> makeRecentTabCard(closed, dialog)));
                }
            } else if (expandedGroup != null) {
                for (TabRegistry.Tab member : namedGroups.getOrDefault(
                        expandedGroup, Collections.emptyList())) {
                    if (!TabSearch.matches(member, query)) continue;
                    entries.add(new OverviewEntry(member.id,
                        () -> makeOverviewTabCard(member, previewHeight, dialog,
                            mode, expandedGroup, query)));
                    if (member == tabRegistry.current()) activePosition = entries.size() - 1;
                }
            } else if (mode == TAB_VIEW_GROUPS) {
                for (Map.Entry<String, List<TabRegistry.Tab>> group : namedGroups.entrySet()) {
                    List<TabRegistry.Tab> members = group.getValue();
                    String groupName = members.get(0).group;
                    if (!TabSearch.matchesGroup(groupName, members, query)) continue;
                    entries.add(new OverviewEntry("group:" + group.getKey(),
                        () -> makeOverviewGroupCard(group.getKey(), groupName, members,
                            previewHeight, dialog, mode)));
                    if (members.contains(tabRegistry.current())) activePosition = entries.size() - 1;
                }
            } else if (searching) {
                // Crucial: return *individual* matching cards from a group,
                // rather than hiding a found page inside its 2x2 group collage.
                for (TabRegistry.Tab tab : tabRegistry.searchForMode(privateMode, query)) {
                    entries.add(new OverviewEntry(tab.id,
                        () -> makeOverviewTabCard(tab, previewHeight, dialog, mode, null, query)));
                    if (tab == tabRegistry.current()) activePosition = entries.size() - 1;
                }
            } else {
                Set<String> seenGroups = new HashSet<>();
                for (TabRegistry.Tab tab : tabRegistry.orderedOverview(privateMode)) {
                    String groupKey = tab.groupKey();
                    if (groupKey != null && !tab.pinned) {
                        if (seenGroups.add(groupKey)) {
                            String groupName = tab.group;
                            List<TabRegistry.Tab> members = namedGroups.get(groupKey);
                            entries.add(new OverviewEntry("group:" + groupKey,
                                () -> makeOverviewGroupCard(groupKey, groupName, members,
                                    previewHeight, dialog, mode)));
                            if (members.contains(tabRegistry.current())) {
                                activePosition = entries.size() - 1;
                            }
                        }
                    } else {
                        entries.add(new OverviewEntry(tab.id,
                            () -> makeOverviewTabCard(tab, previewHeight, dialog, mode, null, query)));
                        if (tab == tabRegistry.current()) activePosition = entries.size() - 1;
                    }
                }
            }
            resultCount.setVisibility(searching ? View.VISIBLE : View.GONE);
            if (searching) resultCount.setText("検索結果 " + entries.size() + "件");
            empty.setText(searching ? "一致するタブがないよ"
                : mode == TAB_VIEW_PRIVATE ? "非公開タブはまだないよ"
                : mode == TAB_VIEW_GROUPS ? "作成したタブグループがここに表示されるよ"
                : mode == TAB_VIEW_RECENT ? "最近閉じたタブはないよ" : "タブはまだないよ");
            int anchorPosition = -1;
            if (anchor != null) {
                for (int i = 0; i < entries.size(); i++) {
                    if (anchor.equals(entries.get(i).key())) { anchorPosition = i; break; }
                }
            }
            int target = anchorPosition >= 0 ? anchorPosition
                : firstRender[0] && !searching && entries.size() > 4 ? activePosition : 0;
            firstRender[0] = false;
            rowsAdapter.notifyDataSetChanged();
            if (!entries.isEmpty()) scroll.setSelectionFromTop(Math.max(0, target) / columns,
                anchorPosition >= 0 ? oldOffset : 0);
        };
        // 100ms debounce keeps large metadata filters off every keystroke.
        Runnable debounceGrid = () -> {
            if (dialog[0] != null && dialog[0].isShowing()) renderGrid.run();
        };
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                mainHandler.removeCallbacks(debounceGrid);
                mainHandler.postDelayed(debounceGrid, 100L);
            }
        });
        if (mode != TAB_VIEW_RECENT) {
            Button add = iconButton(R.drawable.ic_browser_plus,
                privateMode ? "新しい非公開タブ" : "新しいタブ", () -> {
                    dialog[0].dismiss();
                    createTab("about:blank", privateMode);
                });
            add.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                rounded(TAB_SWITCHER_ACCENT, 36, Color.TRANSPARENT), null));
            add.setOnLongClickListener(v -> {
                showTabOverviewMenu(dialog[0], mode);
                return true;
            });
            add.setElevation(dp(8));
            FrameLayout.LayoutParams fab = new FrameLayout.LayoutParams(
                dp(62), dp(62), Gravity.END | Gravity.BOTTOM);
            fab.rightMargin = dp(18);
            fab.bottomMargin = dp(18);
            body.addView(add, fab);
        }
        page.addView(makeTabOverviewBottomBar(dialog, mode),
            new LinearLayout.LayoutParams(-1, dp(66)));
        dialog[0] = fullScreenTabsDialog(page);
        Window screenWindow = dialog[0].getWindow();
        if (screenWindow != null) {
            screenWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog[0].setOnDismissListener(d -> mainHandler.removeCallbacks(debounceGrid));
        renderGrid.run();
    }

    private LinearLayout makeTabOverviewBottomBar(Dialog[] dialog, int mode) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(5));
        bar.setBackgroundColor(SURFACE);
        int[] icons = {R.drawable.ic_browser_tabs, R.drawable.ic_browser_lock,
            R.drawable.ic_browser_layers, R.drawable.ic_browser_history};
        String[] labels = {"通常", "非公開", "グループ", "最近閉じた"};
        for (int index = 0; index < icons.length; index++) {
            final int destination = index;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            int foreground = mode == index ? TAB_SWITCHER_ACCENT : SECONDARY;
            ImageView symbol = iconView(icons[index], foreground);
            symbol.setPadding(dp(6), dp(5), dp(6), dp(3));
            item.addView(symbol, new LinearLayout.LayoutParams(dp(39), dp(35)));
            TextView caption = text(labels[index], 10, foreground, mode == index);
            caption.setGravity(Gravity.CENTER);
            item.addView(caption, new LinearLayout.LayoutParams(-1, dp(18)));
            item.setContentDescription(labels[index] + "タブ画面");
            item.setOnClickListener(v -> {
                dialog[0].dismiss();
                showTabOverview(destination, null);
            });
            bar.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return bar;
    }

    /** A compact site identity, never a network-dependent favicon download. */
    private TextView overviewIdentity(String title, boolean privateMode) {
        String visible = title == null || title.isEmpty() ? "?" : title.substring(0, 1);
        TextView identity = text(privateMode ? "◈" : visible, 15, PRIMARY, true);
        identity.setGravity(Gravity.CENTER);
        identity.setBackground(rounded(privateMode ? Color.rgb(70, 76, 91)
            : Color.rgb(69, 82, 104), 8, Color.TRANSPARENT));
        identity.setContentDescription(privateMode ? "非公開" : "サイトアイコン");
        return identity;
    }

    private FrameLayout makeOverviewPreview(TabRegistry.Tab tab, boolean compact) {
        FrameLayout surface = new FrameLayout(this);
        surface.setBackground(rounded(TAB_SWITCHER_PREVIEW, compact ? 7 : 11,
            Color.TRANSPARENT));
        surface.setClipToOutline(true);
        ImageView image = new ImageView(this);
        image.setContentDescription(compact ? "グループプレビュー" : "ページのプレビュー");
        image.setScaleType(ImageView.ScaleType.MATRIX);
        image.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, orr, ob) ->
            positionTabThumbnail(image));
        surface.addView(image, new FrameLayout.LayoutParams(-1, -1));
        Bitmap bitmap = tab.privateMode ? null : previewStore.load(tab.id);
        if (bitmap != null) {
            displayTabThumbnail(image, bitmap);
        } else {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            ImageView icon = iconView(tab.privateMode ? R.drawable.ic_browser_lock
                : "about:blank".equals(tab.url) ? R.drawable.ic_browser_plus
                : R.drawable.ic_browser_globe, SECONDARY);
            empty.addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));
            if (!compact) {
                String host = SiteBlockPolicy.hostOf(tab.url);
                TextView label = text(tab.privateMode ? "非公開"
                    : "about:blank".equals(tab.url) ? "新しいタブ"
                    : host == null ? "プレビューなし" : host,
                    11, SECONDARY, false);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setGravity(Gravity.CENTER);
                empty.addView(label, new LinearLayout.LayoutParams(-1, dp(27)));
            }
            empty.setTag("velocity-tab-preview-fallback");
            surface.addView(empty, new FrameLayout.LayoutParams(-1, -1));
            if (tab == tabRegistry.current() && !tab.privateMode
                    && !"about:blank".equals(tab.url)) {
                captureVisibleTabPreview(tab, image);
            }
        }
        return surface;
    }

    private View makeOverviewTabCard(TabRegistry.Tab tab, int previewHeight,
                                     Dialog[] dialog, int mode, String expandedGroup,
                                     String searchQuery) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(5), dp(5), dp(5), dp(5));
        card.setBackground(rounded(TAB_SWITCHER_CARD, 19,
            tab == tabRegistry.current() ? TAB_SWITCHER_ACCENT : Color.TRANSPARENT));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        String site = SiteBlockPolicy.hostOf(tab.url);
        TextView identity = overviewIdentity(site == null ? tab.displayTitle() : site,
            tab.privateMode);
        header.addView(identity, new LinearLayout.LayoutParams(dp(29), dp(29)));
        // In search results a group is flattened, so keep its identity visible.
        String cardTitle = (tab.pinned ? "📌 " : "") + tab.displayTitle();
        if (searchQuery != null && !searchQuery.trim().isEmpty()
                && tab.group != null && !tab.group.isEmpty()) {
            cardTitle += " · " + tab.group;
        }
        TextView name = text(cardTitle, 13, PRIMARY, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameSize = new LinearLayout.LayoutParams(0, -1, 1);
        nameSize.leftMargin = dp(7);
        header.addView(name, nameSize);
        Button close = iconButton(R.drawable.ic_browser_close, "タブを閉じる", () -> {
            dialog[0].dismiss();
            closeTabDirect(tab.id);
            showTabOverview(mode, expandedGroup, searchQuery);
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        FrameLayout preview = makeOverviewPreview(tab, false);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(previewHeight));
        card.addView(preview, previewLp);
        card.setContentDescription(tab.displayTitle() + (tab.pinned ? " 固定タブ" : " タブ"));
        card.setOnClickListener(v -> {
            dialog[0].dismiss();
            selectTab(tab.id);
        });
        card.setOnLongClickListener(v -> {
            dialog[0].dismiss();
            showTabCardActions(tab, mode, expandedGroup, searchQuery);
            return true;
        });
        return card;
    }

    private void showTabCardActions(TabRegistry.Tab tab, int mode, String expandedGroup,
                                    String searchQuery) {
        if (tab.startupHome || tabRegistry.find(tab.id) != tab) {
            showTabOverview(mode, expandedGroup, searchQuery);
            return;
        }
        new AlertDialog.Builder(this)
            .setItems(new String[]{tab.pinned ? "固定を解除" : "タブを固定",
                    "前に移動", "後ろに移動", "グループ分け"}, (d, which) -> {
                if (which == 3) {
                    showTabGroupDialog(tab.id);
                    return;
                }
                boolean changed = which == 0 ? tabRegistry.togglePin(tab)
                    : tabRegistry.moveWithinBand(tab, which == 1 ? -1 : 1,
                        expandedGroup != null || (!tab.pinned && tab.groupKey() != null));
                if (changed && !tab.privateMode) saveRegularTabs();
                if (!changed) Toast.makeText(this, "これ以上移動できません",
                    Toast.LENGTH_SHORT).show();
                showTabOverview(mode, expandedGroup, searchQuery);
            }).setOnCancelListener(d -> showTabOverview(mode, expandedGroup, searchQuery)).show();
    }

    private View makeOverviewGroupCard(String key, String name, List<TabRegistry.Tab> members,
                                       int previewHeight, Dialog[] dialog, int mode) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(5), dp(5), dp(5), dp(5));
        card.setBackground(rounded(TAB_SWITCHER_CARD, 19, Color.TRANSPARENT));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView identity = overviewIdentity("▣", false);
        identity.setBackground(rounded(Color.rgb(71, 99, 143), 10, Color.TRANSPARENT));
        header.addView(identity, new LinearLayout.LayoutParams(dp(29), dp(29)));
        TextView title = text(name + " · " + members.size(), 13, PRIMARY, false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -1, 1);
        titleLp.leftMargin = dp(7);
        header.addView(title, titleLp);
        Button menu = iconButton(R.drawable.ic_browser_menu,
            name + "のグループ設定", () -> {
                dialog[0].dismiss();
                showTabGroupOptions(key, name, members, mode);
            });
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout mosaic = new LinearLayout(this);
        mosaic.setOrientation(LinearLayout.VERTICAL);
        card.addView(mosaic, new LinearLayout.LayoutParams(-1, dp(previewHeight)));
        for (int row = 0; row < 2; row++) {
            LinearLayout inner = new LinearLayout(this);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, 0, 1);
            if (row > 0) rowLp.topMargin = dp(5);
            mosaic.addView(inner, rowLp);
            for (int col = 0; col < 2; col++) {
                int position = row * 2 + col;
                View tile;
                if (position == 3 && members.size() > 4) {
                    TextView more = text("+" + (members.size() - 3), 20, PRIMARY, false);
                    more.setGravity(Gravity.CENTER);
                    more.setBackground(rounded(TAB_SWITCHER_PREVIEW, 7,
                        Color.TRANSPARENT));
                    tile = more;
                } else if (position < members.size()) {
                    tile = makeOverviewPreview(members.get(position), true);
                } else {
                    View blank = new View(this);
                    blank.setBackground(rounded(TAB_SWITCHER_PREVIEW, 7,
                        Color.TRANSPARENT));
                    tile = blank;
                }
                LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(0, -1, 1);
                if (col != 0) tileLp.leftMargin = dp(5);
                inner.addView(tile, tileLp);
            }
        }
        card.setContentDescription(name + " グループ " + members.size() + "件");
        card.setOnClickListener(v -> {
            dialog[0].dismiss();
            showTabOverview(mode, key);
        });
        card.setOnLongClickListener(v -> {
            dialog[0].dismiss();
            showTabGroupOptions(key, name, members, mode);
            return true;
        });
        return card;
    }

    private void showTabGroupOptions(String groupId, String groupName, List<TabRegistry.Tab> members,
                                     int mode) {
        // The group is a *metadata* association. Removing a group never
        // closes the underlying tabs or discards page/scroll state.
        new AlertDialog.Builder(this).setTitle(groupName)
            .setItems(new String[]{"グループを開く", "名前を変更", "グループを解除"},
                (unused, selected) -> {
                    if (selected == 0) {
                        showTabOverview(mode, groupId);
                    } else if (selected == 1) {
                        EditText input = new EditText(this);
                        input.setSingleLine(true);
                        input.setText(groupName);
                        new AlertDialog.Builder(this).setTitle("グループ名を変更")
                            .setView(input).setPositiveButton("保存", (d, which) -> {
                                String next = input.getText().toString().trim();
                                if (next.isEmpty()) return;
                                tabRegistry.renameGroup(groupId, mode == TAB_VIEW_PRIVATE, next);
                                saveRegularTabs();
                                showTabOverview(mode, null);
                            }).setNegativeButton("キャンセル", (d, which) ->
                                showTabOverview(mode, null)).show();
                    } else {
                        new AlertDialog.Builder(this).setTitle("グループを解除する？")
                            .setMessage("タブは閉じず、そのまま一覧に戻すよ。")
                            .setNegativeButton("キャンセル", (d, which) ->
                                showTabOverview(mode, null))
                            .setPositiveButton("解除", (d, which) -> {
                                for (TabRegistry.Tab member : members) {
                                    if (groupId.equals(member.groupKey())) tabRegistry.assignGroup(member, null);
                                }
                                saveRegularTabs();
                                showTabOverview(mode, null);
                            }).show();
                    }
                }).setOnCancelListener(d -> showTabOverview(mode, null)).show();
    }

    private View makeRecentTabCard(TabRegistry.Tab closed, Dialog[] dialog) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(12), dp(10), dp(12));
        card.setBackground(rounded(TAB_SWITCHER_CARD, 15, Color.TRANSPARENT));
        ImageView icon = iconView(R.drawable.ic_browser_globe, SECONDARY);
        card.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(closed.displayTitle(), 14, PRIMARY, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(title);
        TextView url = text(closed.url, 11, SECONDARY, false);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(url);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        card.setContentDescription("閉じたタブを復元 " + closed.displayTitle());
        card.setOnClickListener(v -> {
            dialog[0].dismiss();
            ClosedTab checkpoint = closedRegularState.get(closed.id);
            if (checkpoint != null) reopenClosedTab(checkpoint);
        });
        return card;
    }

    private void showTabOverviewMenu(Dialog dialog, int mode) {
        new AlertDialog.Builder(this).setTitle("タブの操作")
            .setItems(new String[]{"新しい通常タブ", "新しい非公開タブ",
                "現在のタブをグループに追加", "この一覧のタブを全て閉じる"},
                (d, option) -> {
                    if (option == 0 || option == 1) {
                        dialog.dismiss();
                        createTab("about:blank", option == 1);
                    } else if (option == 2) {
                        TabRegistry.Tab current = tabRegistry.current();
                        if (current != null) {
                            dialog.dismiss();
                            showTabGroupDialog(current.id);
                        }
                    } else {
                        boolean privateMode = mode == TAB_VIEW_PRIVATE;
                        List<TabRegistry.Tab> candidates = new ArrayList<>(
                            tabRegistry.tabsForMode(privateMode));
                        if (mode == TAB_VIEW_GROUPS || mode == TAB_VIEW_RECENT) {
                            Toast.makeText(this, "通常か非公開の一覧で実行してね",
                                Toast.LENGTH_SHORT).show();
                            return;
                        }
                        candidates.removeIf(tab -> tab.startupHome || tab.pinned);
                        if (candidates.isEmpty()) {
                            Toast.makeText(this, "閉じるタブがありません", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        new AlertDialog.Builder(this).setTitle("全タブを閉じる？")
                            .setMessage(candidates.size() + "件の" +
                                (privateMode ? "非公開" : "通常") +
                                "タブを閉じます。ホームと固定タブは残します。")
                            .setNegativeButton("キャンセル", null)
                            .setPositiveButton("閉じる", (confirm, which) -> {
                                dialog.dismiss();
                                closeTabBatch(candidates);
                            }).show();
                    }
                }).show();
    }

    private void showTabGroupDialog(String id) {
        TabRegistry.Tab tab = tabRegistry.find(id);
        if (tab == null || tab.startupHome) return;
        LinkedHashMap<String, TabRegistry.Tab> groups = new LinkedHashMap<>();
        for (TabRegistry.Tab other : tabRegistry.tabsForMode(tab.privateMode)) {
            String key = other.groupKey();
            if (key != null) groups.putIfAbsent(key, other);
        }
        ArrayList<String> choices = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>(groups.keySet());
        HashMap<String, Integer> nameCounts = new HashMap<>();
        HashMap<String, Integer> sameNameOrdinal = new HashMap<>();
        for (TabRegistry.Tab member : groups.values()) nameCounts.merge(member.group, 1, Integer::sum);
        choices.add("＋ 新しいグループを作成");
        choices.add("グループから外す");
        for (TabRegistry.Tab member : groups.values()) {
            String name = member.group;
            choices.add(nameCounts.get(name) > 1
                ? name + " (" + sameNameOrdinal.merge(name, 1, Integer::sum)
                    + "/" + nameCounts.get(name) + ") · " + member.displayTitle()
                : name);
        }
        new AlertDialog.Builder(this).setTitle("タブのグループ分け")
            .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                if (which == 0) {
                    EditText name = new EditText(this);
                    name.setSingleLine(true);
                    name.setHint("グループ名");
                    new AlertDialog.Builder(this).setTitle("新しいグループ")
                        .setView(name).setPositiveButton("作成", (d, w) -> {
                            String value = name.getText().toString().trim();
                            if (tabRegistry.createGroup(tab, value)) {
                                saveRegularTabs();
                                showTabs(tab.privateMode);
                            }
                        }).setNegativeButton("キャンセル", null).show();
                } else {
                    if (tabRegistry.assignGroup(tab, which == 1 ? null : keys.get(which - 2))) {
                        saveRegularTabs();
                        showTabs(tab.privateMode);
                    }
                }
            }).setNegativeButton("キャンセル", null).show();
    }

    private void reopenClosedTab(ClosedTab closed) {
        reopenClosedTabs(List.of(closed), closed.tab.id);
    }

    private void reopenClosedTabs(List<ClosedTab> entries, String selectedId) {
        ClosedTab selected = null;
        String fallback = tabRegistry.current() == null ? null : tabRegistry.current().id;
        boolean restored = false;
        for (ClosedTab closed : entries) {
            if (!restoreClosedEntry(closed)) continue;
            restored = true;
            if (closed.tab.id.equals(selectedId)) selected = closed;
        }
        if (!restored) return;
        if (selected == null && fallback != null) tabRegistry.select(fallback);
        else if (selected != null) tabRegistry.select(selected.tab.id);
        reopeningTab = selected;
        try { activateCurrentTab(); }
        finally { reopeningTab = null; }
        saveRegularTabs();
    }

    private boolean restoreClosedEntry(ClosedTab closed) {
        if (!tabRegistry.reopenClosed(closed.tab, closed.position)) return false;
        recentlyClosedRegularTabs.remove(closed.tab);
        closedRegularState.remove(closed.tab.id);
        if (!closed.tab.privateMode && closed.navigation != null) {
            previewStore.saveNavigationState(closed.tab.id, closed.navigation);
        }
        return true;
    }

    /** Close back to front so all captured positions refer to the original strip. */
    private void closeTabBatch(List<TabRegistry.Tab> candidates) {
        TabRegistry.Tab formerCurrent = tabRegistry.current();
        String formerId = formerCurrent == null ? null : formerCurrent.id;
        List<ClosedTab> entries = new ArrayList<>();
        // Keep the immediate undo bounded even for very large sessions.
        // Tabs outside this history budget still reopen at their saved URLs.
        int budget = 16;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            TabRegistry.Tab tab = candidates.get(i);
            if (tab.startupHome) continue;
            boolean capture = tab == formerCurrent || (!tab.privateMode && budget > 0);
            ClosedTab closed = detachClosedTab(tab.id, capture);
            if (closed != null) {
                entries.add(closed);
                if (capture && tab != formerCurrent && closed.navigation != null) budget--;
            }
        }
        if (entries.isEmpty()) return;
        java.util.Collections.reverse(entries);
        ensureCurrentTabAfterClose(formerCurrent != tabRegistry.current());
        showUndo(entries, formerId);
    }

    /** Capture navigation before destroying a tab; recently closed entries stay in RAM. */
    private void closeTabDirect(String id) {
        TabRegistry.Tab victim = tabRegistry.find(id);
        if (victim == null) return;
        boolean wasCurrent = victim == tabRegistry.current();
        ClosedTab closed = detachClosedTab(id, true);
        if (closed == null) return;
        ensureCurrentTabAfterClose(wasCurrent);
        showUndo(List.of(closed), closed.tab.id);
    }

    private ClosedTab detachClosedTab(String id, boolean captureNavigation) {
        TabRegistry.Tab victim = tabRegistry.find(id);
        if (victim == null) return null;
        int formerIndex = tabRegistry.all().indexOf(victim);
        CefriumBrowser closing = liveBrowsers.remove(id);
        if (closing != null && closing == findBrowser) closePageFind();
        byte[] checkpoint = null;
        if (captureNavigation && !victim.startupHome) {
            try {
                checkpoint = closing != null ? closing.serializeNavigationState()
                    : victim.privateMode ? null : previewStore.loadNavigationState(id);
            } catch (RuntimeException | LinkageError error) {
                Log.w(TAG, "Could not capture navigation of closed tab", error);
            }
            if (checkpoint != null && (checkpoint.length == 0
                    || checkpoint.length > NavigationCheckpointStore.MAX_BYTES)) {
                checkpoint = null;
            }
        }
        ClosedTab closed = new ClosedTab(victim, formerIndex, checkpoint);
        if (closing != null) {
            popupOpeners.entrySet().removeIf(entry ->
                entry.getKey() == closing || entry.getValue() == closing);
            if (engine == closing) engine = null;
            if (downloads.isBusy(closing)) {
                closing.getSurfaceContainer().setVisibility(View.GONE);
                closingAfterDownload.add(closing);
            } else {
                closing.close();
            }
            // The SDK may keep empty renderer processes around for reuse.
            // Reap only zero-frame children; live renderers are unaffected.
            CefriumBrowser.reapUnusedRenderers();
        }
        tabRegistry.close(id);
        if (!victim.privateMode && !victim.startupHome && isPersistable(victim.url)) {
            recentlyClosedRegularTabs.add(0, victim);
            closedRegularState.put(victim.id, closed);
            if (recentlyClosedRegularTabs.size() > 20) {
                TabRegistry.Tab expired = recentlyClosedRegularTabs.remove(
                    recentlyClosedRegularTabs.size() - 1);
                closedRegularState.remove(expired.id);
            }
        }
        if (!victim.privateMode) previewStore.remove(victim.id);
        return closed;
    }

    private void ensureCurrentTabAfterClose(boolean changed) {
        if (tabRegistry.current() == null) tabRegistry.add("about:blank", false);
        if (changed) activateCurrentTab();
        else updateNavigation();
        saveRegularTabs();
    }

    /** Five-second, non-modal undo. Does not claim to recover form/JS state. */
    private void showUndo(List<ClosedTab> entries, String selectedId) {
        if (undoTimeout != null) mainHandler.removeCallbacks(undoTimeout);
        if (undoPopup != null) undoPopup.dismiss();

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(6), dp(13), dp(6));
        bar.setBackground(rounded(ELEVATED, 16, BORDER));
        TextView message = text(entries.size() == 1 ? "タブを閉じた"
            : entries.size() + "件のタブを閉じた", 14, PRIMARY, false);
        bar.addView(message, new LinearLayout.LayoutParams(0, -1, 1));
        Button undo = makeButton("元に戻す", "閉じたタブを復元する", () -> {
            if (undoPopup != null) undoPopup.dismiss();
            undoPopup = null;
            if (undoTimeout != null) mainHandler.removeCallbacks(undoTimeout);
            reopenClosedTabs(entries, selectedId);
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
            undoTimeout = null;
        };
        mainHandler.postDelayed(undoTimeout, 5000L);
    }

    private void closeCurrentTab() {
        TabRegistry.Tab current = tabRegistry.current();
        if (current != null) closeTabDirect(current.id);
    }

    private void buildPageFindBar(LinearLayout header) {
        EditText query = new EditText(this);
        findQuery = query;
        query.setSingleLine(true);
        query.setHint("ページ内検索");
        query.setTextColor(PRIMARY);
        query.setHintTextColor(SECONDARY);
        query.setTextSize(16);
        query.setSaveEnabled(false);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        header.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        findCount = text("", 12, SECONDARY, false);
        findCount.setSingleLine(true);
        findCount.setGravity(Gravity.CENTER);
        findCount.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(findCount, new LinearLayout.LayoutParams(dp(62), dp(48)));
        addFindButton(header, "‹", "前の一致へ", () -> runPageFind(false, true));
        addFindButton(header, "›", "次の一致へ", () -> runPageFind(true, true));
        addFindButton(header, "×", "ページ内検索を閉じる", this::closePageFind);
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (findBrowser == null) return;
                mainHandler.removeCallbacks(runPageFind);
                findCount.setText(s.length() == 0 ? "" : "…");
                if (s.length() == 0) runPageFind(true, false);
                else mainHandler.postDelayed(runPageFind, 160);
            }
        });
        query.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH && (event == null
                    || event.getKeyCode() != KeyEvent.KEYCODE_ENTER
                    || event.getAction() != KeyEvent.ACTION_UP)) return false;
            runPageFind(true, true);
            hideFindKeyboard();
            return true;
        });
    }

    private void addFindButton(LinearLayout header, String glyph, String label, Runnable action) {
        Button button = makeButton(glyph, label, action);
        button.setTextSize(20);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        header.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private void findInPage() {
        if (engine == null || tabRegistry.isStartupHomeCurrent()) return;
        if (findBrowser != null && findBrowser != engine) closePageFind();
        findBrowser = engine;
        browserHeader.setVisibility(View.VISIBLE);
        findQuery.requestFocus();
        findQuery.selectAll();
        findQuery.post(() -> {
            if (destroyed || findBrowser == null || !findQuery.hasFocus()) return;
            android.view.inputmethod.InputMethodManager ime = getSystemService(
                android.view.inputmethod.InputMethodManager.class);
            if (ime != null) ime.showSoftInput(findQuery,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void runPageFind(boolean forward, boolean advance) {
        mainHandler.removeCallbacks(runPageFind);
        if (destroyed || findBrowser == null || findBrowser != engine) return;
        String needle = findQuery.getText().toString();
        if (needle.isEmpty()) {
            issuedFindQuery = "";
            findBrowser.stopFinding(true);
            return;
        }
        boolean continuing = advance && needle.equals(issuedFindQuery);
        issuedFindQuery = needle;
        findBrowser.find(needle, forward, false, continuing);
    }

    private void hideFindKeyboard() {
        android.view.inputmethod.InputMethodManager ime = getSystemService(
            android.view.inputmethod.InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(findQuery.getWindowToken(), 0);
    }

    private void closePageFind() {
        CefriumBrowser browser = findBrowser;
        if (browser == null) return;
        findBrowser = null;
        issuedFindQuery = "";
        mainHandler.removeCallbacks(runPageFind);
        browser.stopFinding(true);
        if (findQuery.hasFocus()) hideFindKeyboard();
        findQuery.clearFocus();
        findQuery.setText("");
        findCount.setText("");
        browserHeader.setVisibility(View.GONE);
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

    /**
     * A Chrome-style row of high-frequency tools, followed by a compact menu.
     * Diagnostic/experimental controls are one level deeper so a normal user
     * doesn't have to scroll past implementation details to find Settings.
     */
    private void showMenu() {
        LinearLayout panel = sheetPanel();
        final Dialog[] dialog = new Dialog[1];
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(5), 0, dp(5), dp(8));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(49)));
        TextView title = text("Velocity", 21, PRIMARY, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        TextView hint = text("ブラウザメニュー", 11, SECONDARY, false);
        heading.addView(hint, new LinearLayout.LayoutParams(-2, -1));

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams shortcutsLp = new LinearLayout.LayoutParams(-1, dp(75));
        shortcutsLp.bottomMargin = dp(10);
        panel.addView(shortcuts, shortcutsLp);
        int[] quickIcons = {
            R.drawable.ic_browser_forward, R.drawable.ic_browser_refresh,
            R.drawable.ic_browser_plus, R.drawable.ic_browser_share,
            R.drawable.ic_browser_home
        };
        String[] quickNames = {"進む", "更新", "新規", "共有", "ホーム"};
        Runnable[] quickActions = {
            () -> {
                TabRegistry.Tab active = tabRegistry.current();
                if (engine != null && active != null && active.canGoForward) engine.goForward();
            },
            () -> { if (engine != null) engine.reload(); },
            () -> createTab("about:blank", false),
            this::shareCurrentPage,
            this::goHome
        };
        for (int i = 0; i < quickIcons.length; i++) {
            final int index = i;
            LinearLayout action = new LinearLayout(this);
            action.setOrientation(LinearLayout.VERTICAL);
            action.setGravity(Gravity.CENTER);
            int tint = i == 2 ? ACCENT : PRIMARY;
            ImageView icon = iconView(quickIcons[i], tint);
            icon.setBackground(rounded(ELEVATED, 21, Color.TRANSPARENT));
            action.addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));
            TextView label = text(quickNames[i], 11, SECONDARY, false);
            label.setGravity(Gravity.CENTER);
            action.addView(label, new LinearLayout.LayoutParams(-1, dp(24)));
            shortcuts.addView(action, new LinearLayout.LayoutParams(0, -1, 1));
            action.setContentDescription(quickNames[i]);
            action.setOnClickListener(v -> {
                dialog[0].dismiss();
                quickActions[index].run();
            });
        }
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        panel.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        BoundedScrollView scroll = new BoundedScrollView();
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout items = sheetPanel();
        scroll.addView(items);
        // This order mirrors Chrome and Vivaldi's common tasks. Implemented
        // actions only—no fake bookmark/download or sync navigation controls.
        menuSection(items, "タブ");
        addMenuItem(items, R.drawable.ic_browser_tabs, "タブ一覧", null, () -> {
            dialog[0].dismiss();
            showTabs();
        });
        addMenuItem(items, R.drawable.ic_browser_lock, "新しい非公開タブ", null, () -> {
            dialog[0].dismiss();
            createTab("about:blank", true);
        });
        addMenuItem(items, R.drawable.ic_browser_layers, "タブグループ", null, () -> {
            dialog[0].dismiss();
            showTabOverview(TAB_VIEW_GROUPS, null);
        });
        menuSection(items, "ページ");
        addMenuItem(items, R.drawable.ic_browser_search, "ページ内検索", null, () -> {
            dialog[0].dismiss();
            findInPage();
        });
        TabRegistry.Tab pdfTab = tabRegistry.current();
        if (pdfTab != null && PdfLinkPolicy.canPreview(pdfTab.url)) {
            addMenuItem(items, R.drawable.ic_browser_download, "このPDFをプレビュー", null, () -> {
                dialog[0].dismiss();
                if (engine != null) engine.startDownload(pdfTab.url);
            });
        }
        TabRegistry.Tab current = tabRegistry.current();
        boolean isCurrentBookmarked = current != null && bookmarkStore.isBookmarked(current.url);
        addMenuItem(items, R.drawable.ic_browser_speed,
            isCurrentBookmarked ? "ブックマークを解除" : "ブックマークに追加", null, () -> {
                dialog[0].dismiss();
                toggleBookmarkCurrent();
            });
        addMenuItem(items, R.drawable.ic_browser_speed, "ブックマーク一覧", null, () -> {
            dialog[0].dismiss();
            showBookmarks();
        });
        if (current == null || !current.privateMode) {
            if (current != null && ReadingList.supports(current.url)) {
                addMenuItem(items, R.drawable.ic_browser_globe, "本文だけ読む", null, () -> {
                    dialog[0].dismiss();
                    showReaderText();
                });
                addMenuItem(items, R.drawable.ic_browser_speed, "読書リストに追加", null, () -> {
                    dialog[0].dismiss();
                    addPageToReadingList();
                });
            }
            addMenuItem(items, R.drawable.ic_browser_history, "読書リスト", null, () -> {
                dialog[0].dismiss();
                showReadingList();
            });
        }
        addMenuItem(items, R.drawable.ic_browser_history, "閲覧履歴", null, () -> {
            dialog[0].dismiss();
            showHistory();
        });
        boolean desktop = current != null && current.desktopMode;
        addMenuItem(items, R.drawable.ic_browser_globe, "PC版サイト",
            desktop ? "ON" : "OFF", () -> {
                dialog[0].dismiss();
                toggleDesktopMode();
            });
        addMenuItem(items, R.drawable.ic_browser_share, "URLをコピー", null, () -> {
            dialog[0].dismiss();
            copyCurrentPageUrl();
        });
        addMenuItem(items, R.drawable.ic_browser_search, "表示倍率を変更", null, () -> {
            dialog[0].dismiss();
            showPageZoom();
        });
        menuSection(items, "ファイル");
        addMenuItem(items, R.drawable.ic_browser_download, "ダウンロード", null, () -> {
            dialog[0].dismiss();
            TabRegistry.Tab currentTab = tabRegistry.current();
            downloads.show(currentTab != null && currentTab.privateMode);
        });
        menuSection(items, "アプリ");
        addMenuItem(items, R.drawable.ic_browser_settings, "設定", null, () -> {
            dialog[0].dismiss();
            showSettings();
        });
        addMenuItem(items, R.drawable.ic_browser_flask, "詳細ツール", null, () -> {
            dialog[0].dismiss();
            showAdvancedMenu();
        });
        dialog[0] = bottomSheet(panel);
    }

    private void menuSection(LinearLayout items, String label) {
        TextView title = text(label, 12, ACCENT, true);
        title.setPadding(dp(9), dp(8), dp(4), 0);
        items.addView(title, new LinearLayout.LayoutParams(-1, dp(30)));
    }

    private void addMenuItem(LinearLayout items, int iconRes, String name,
                             String state, Runnable click) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(5), 0, dp(7), 0);
        items.addView(item, new LinearLayout.LayoutParams(-1, dp(50)));
        item.addView(iconView(iconRes, SECONDARY),
            new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(name, 14, PRIMARY, false);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0, -1, 1);
        titleSize.leftMargin = dp(7);
        item.addView(title, titleSize);
        if (state != null) item.addView(text(state, 12, ACCENT, true),
            new LinearLayout.LayoutParams(-2, -1));
        item.setContentDescription(name + (state == null ? "" : " " + state));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20FFFFFF),
            rounded(SURFACE, 11, Color.TRANSPARENT), null));
        item.setOnClickListener(v -> click.run());
    }

    private void showAdvancedMenu() {
        LinearLayout panel = sheetPanel();
        TextView title = text("詳細ツール", 20, PRIMARY, true);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        BoundedScrollView scroll = new BoundedScrollView();
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout items = sheetPanel();
        scroll.addView(items);
        final Dialog[] dialog = new Dialog[1];
        addMenuItem(items, R.drawable.ic_browser_globe, "比較サイトを開く", null,
            () -> { dialog[0].dismiss(); runMenuAction(5); });
        addMenuItem(items, R.drawable.ic_browser_shield, "ドメイン遮断を切替", null,
            () -> { dialog[0].dismiss(); runMenuAction(6); });
        addMenuItem(items, R.drawable.ic_browser_eye_off, "広告枠の非表示を切替", null,
            () -> { dialog[0].dismiss(); runMenuAction(7); });
        addMenuItem(items, R.drawable.ic_browser_shield, "サイト別の遮断例外", null,
            () -> { dialog[0].dismiss(); runMenuAction(8); });
        addMenuItem(items, R.drawable.ic_browser_speed, "高速起動の実験", null,
            () -> { dialog[0].dismiss(); runMenuAction(9); });
        addMenuItem(items, R.drawable.ic_browser_download, "APK更新を確認", null,
            () -> { dialog[0].dismiss(); runMenuAction(10); });
        addMenuItem(items, R.drawable.ic_browser_flask, "起動診断", null,
            () -> { dialog[0].dismiss(); runMenuAction(11); });
        addMenuItem(items, R.drawable.ic_browser_info, "このブラウザについて", null,
            () -> { dialog[0].dismiss(); runMenuAction(13); });
        dialog[0] = bottomSheet(panel);
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
        browser.setPullToRefreshEnabled(settings.getBoolean("pull_to_refresh", DEFAULT_PULL_TO_REFRESH));
        browser.setBackgroundColor(CANVAS);
        // Default forbids autoplay without user activation.
        browser.setMediaPlaybackRequiresUserGesture(!settings.getBoolean("auto_play", false));
        // Chromium's media session can hold/request Android audio focus,
        // causing the user's music in another app to duck, even on a page
        // that the user merely opened to read. Opt out by default.
        browser.setMediaSessionEnabled(!externalAudioPriority());
    }

    private void quietBrowserWhenHidden(CefriumBrowser browser) {
        boolean stopOnLeave = getSharedPreferences(TAB_PREFS, MODE_PRIVATE)
            .getBoolean("stop_media_on_leave", false);
        if (stopOnLeave) browser.stopAllMedia();
        if (externalAudioPriority()) {
            // MediaSession is already disabled for external-audio priority.
            // Mark only the page visibility; do not erase the user's playback.
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
            if ("address_bar_bottom".equals(key)) applyBrowserBarPosition();
            if ("show_speed_dials".equals(key)) rebuildHome();
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

    /** Full-screen category navigation rather than a cramped options sheet. */
    private void showSettings() {
        showSettingsCategory("外観");
    }

    private void addSettingsAction(LinearLayout parent, int icon, String title,
                                   String details, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(10), dp(7), dp(11), dp(7));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1DFFFFFF),
            rounded(ELEVATED, 15, Color.TRANSPARENT), null));
        item.addView(iconView(icon, ACCENT), new LinearLayout.LayoutParams(dp(43), dp(43)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(6), 0, dp(3), 0);
        labels.addView(text(title, 14, PRIMARY, true));
        if (details != null && !details.isEmpty()) {
            TextView hint = text(details, 12, SECONDARY, false);
            hint.setMaxLines(2);
            hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(hint);
        }
        item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        item.setContentDescription(title);
        item.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(68));
        layout.bottomMargin = dp(7);
        parent.addView(item, layout);
    }

    private void showSettingsCategory(String category) {
        final String[] categories = {"外観", "タブ", "プライバシー", "メディア", "詳細"};
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(CANVAS);
        final Dialog[] dialog = new Dialog[1];

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), dp(8), dp(12), dp(8));
        page.addView(heading, new LinearLayout.LayoutParams(-1, dp(66)));
        Button back = iconButton(R.drawable.ic_browser_back, "設定を閉じる", () ->
            dialog[0].dismiss());
        heading.addView(back, new LinearLayout.LayoutParams(dp(44), dp(46)));
        TextView title = text("設定", 23, PRIMARY, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -1, 1);
        titleLp.leftMargin = dp(8);
        heading.addView(title, titleLp);
        Button close = iconButton(R.drawable.ic_browser_close, "設定を閉じる", () ->
            dialog[0].dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(44), dp(46)));

        HorizontalScrollView categoryScroll = new HorizontalScrollView(this);
        categoryScroll.setHorizontalScrollBarEnabled(false);
        categoryScroll.setFillViewport(false);
        page.addView(categoryScroll, new LinearLayout.LayoutParams(-1, dp(65)));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(14), dp(6), dp(14), dp(10));
        categoryScroll.addView(tabs, new android.widget.HorizontalScrollView.LayoutParams(
            -2, -1));
        final View[] selectedChip = {null};
        for (String name : categories) {
            boolean selected = name.equals(category);
            TextView chip = text(name, 13, selected ? CANVAS : SECONDARY, selected);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(15), 0, dp(15), 0);
            chip.setBackground(rounded(selected ? ACCENT : SURFACE, 19,
                selected ? Color.TRANSPARENT : BORDER));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, dp(38));
            chipLp.rightMargin = dp(8);
            tabs.addView(chip, chipLp);
            if (selected) selectedChip[0] = chip;
            chip.setContentDescription(name + "設定");
            chip.setOnClickListener(v -> {
                if (selected) return;
                dialog[0].dismiss();
                showSettingsCategory(name);
            });
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(32));
        scroll.addView(content);
        TextView categoryTitle = text(category, 19, PRIMARY, true);
        categoryTitle.setPadding(dp(3), 0, 0, dp(12));
        content.addView(categoryTitle, new LinearLayout.LayoutParams(-1, dp(52)));
        switch (category) {
            case "外観":
                addSettingsToggle(content, "アドレスバーを下に配置",
                    "Chrome・Vivaldiのように上下を選択", "address_bar_bottom", true);
                addSettingsToggle(content, "ショートカットを表示",
                    "ホームに編集可能なSpeed Dialを表示", "show_speed_dials", true);
                addSettingsToggle(content, "Webサイトのダークモード",
                    "Chromiumのカラースキームを変更", "force_dark", false);
                addSettingsToggle(content, "下に引いて更新",
                    "ページ先頭で引き下ろすと再読み込み", "pull_to_refresh", DEFAULT_PULL_TO_REFRESH);
                addSettingsAction(content, R.drawable.ic_browser_plus,
                    "ホームのショートカットを追加", "長押しすると編集・削除できるよ", () -> {
                        dialog[0].dismiss();
                        editSpeedDial(null);
                    });
                break;
            case "タブ":
                addSettingsToggle(content, "前回のタブを復元",
                    "起動時に最後に開いていたタブを表示", "restore_last_tab", DEFAULT_RESTORE_LAST_TAB);
                addSettingsToggle(content, "タブのプレビューを保存",
                    "通常タブだけ、アプリ専用領域に画像を保存", "save_previews", true);
                addSettingsToggle(content, "新しいタブで検索欄を選択",
                    "新規タブを開くとキーボードを表示", "focus_new_tab", false);
                addSettingsAction(content, R.drawable.ic_browser_tabs,
                    "開いているタブ", "通常・非公開・グループを管理", () -> {
                        dialog[0].dismiss();
                        showTabs();
                    });
                break;
            case "プライバシー":
                addSettingsToggle(content, "トラッカー・広告ドメインを遮断",
                    "Velocity Block Lite のネットワーク遮断", "block_domains", true);
                addSettingsToggle(content, "広告枠を非表示",
                    "限定的なCSSルールによる非表示", "cosmetic_elements", true);
                addSettingsAction(content, R.drawable.ic_browser_key,
                    "パスキー・自動入力の設定", "Android標準の認証情報サービスを開く", () -> {
                        dialog[0].dismiss();
                        openCredentialSettings();
                    });
                TextView caveat = text("Googleパスワードマネージャーとの完全な連携には、"
                    + "ブラウザとしての承認も別途必要だよ。", 12, SECONDARY, false);
                caveat.setPadding(dp(10), dp(10), dp(10), dp(10));
                content.addView(caveat, new LinearLayout.LayoutParams(-1, -2));
                break;
            case "メディア":
                addSettingsToggle(content, "動画と音声の自動再生",
                    "音声フォーカスとは別の設定", "auto_play", false);
                addSettingsToggle(content, "他アプリの音声を優先",
                    "他アプリの音楽が小さくなるのを抑制", "external_audio_priority", true);
                addSettingsToggle(content, "画面を離れたらWebメディアを停止",
                    "ページやアプリを切り替える際に再生を終了する", "stop_media_on_leave", false);
                addSettingsAction(content, R.drawable.ic_browser_play,
                    "Webメディアをすべて停止", "不要な再生・音声フォーカスを解放", () -> {
                        dialog[0].dismiss();
                        stopAllWebMedia();
                    });
                addSettingsAction(content, R.drawable.ic_browser_info,
                    "音声フォーカスの診断", "原因調査の手順を確認", () -> {
                        dialog[0].dismiss();
                        showAudioFocusDiagnostic();
                    });
                break;
            case "詳細":
                addSettingsAction(content, R.drawable.ic_browser_download,
                    "更新を確認", "GitHub Actionsの署名済みAPK", () -> {
                        dialog[0].dismiss();
                        openUpdatesPage();
                    });
                addSettingsAction(content, R.drawable.ic_browser_flask,
                    "起動診断", "Chromiumの起動と子プロセスを検証", () -> {
                        dialog[0].dismiss();
                        startActivity(new Intent(this, StartupDiagnosticActivity.class));
                    });
                addSettingsAction(content, R.drawable.ic_browser_info,
                    "このブラウザについて", "実装状況・制限を確認", () -> {
                        dialog[0].dismiss();
                        showInfo();
                    });
                TextView version = text(installedVersionLabel(), 11, SECONDARY, false);
                version.setPadding(dp(9), dp(20), dp(5), dp(10));
                content.addView(version, new LinearLayout.LayoutParams(-1, -2));
                break;
            default:
                break;
        }
        dialog[0] = fullScreenTabsDialog(page);
        // Horizontal categories must keep the currently selected tab visible.
        // Without this, Media/Advanced looked unselected on narrow phones.
        if (selectedChip[0] != null) {
            categoryScroll.post(() -> {
                if (dialog[0] != null && dialog[0].isShowing()) {
                    categoryScroll.smoothScrollTo(Math.max(0, selectedChip[0].getLeft()
                        - dp(16)), 0);
                }
            });
        }
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
            .setMessage("エンジン：Chromium（Cefrium 0.21.0）\n\n" +
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
        if (requestCode == IMPORT_BOOKMARKS_REQUEST || requestCode == EXPORT_BOOKMARKS_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                transferBookmarkFile(requestCode == EXPORT_BOOKMARKS_REQUEST, data.getData());
            }
            return;
        }
        for (CefriumBrowser browser : liveBrowsers.values()) {
            if (browser.onActivityResult(requestCode, resultCode, data)) return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (CefriumBrowser browser : liveBrowsers.values()) {
            if (browser.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
                return;
            }
        }
    }

    /** System Back is navigation, not Activity destruction during page loads. */
    private void handleBrowserBack() {
        if (findBrowser != null) {
            closePageFind();
            return;
        }
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
        closePageFind();
        if (Build.VERSION.SDK_INT >= 33 && gestureBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(gestureBackCallback);
        }
        thumbnailDecodeExecutor.shutdownNow();
        bookmarkFileExecutor.shutdown();
        thumbnailRequestsInFlight.clear();
        pendingThumbnailViews.clear();
        if (previewStore != null) previewStore.close();
        mainHandler.removeCallbacks(deferredTabSave);
        clearAddressSuggestions();
        if (undoTimeout != null) mainHandler.removeCallbacks(undoTimeout);
        if (undoPopup != null) undoPopup.dismiss();
        if (downloads != null) downloads.dispose();
        for (CefriumBrowser browser : closingAfterDownload) browser.close();
        closingAfterDownload.clear();
        for (CefriumBrowser browser : liveBrowsers.values()) browser.close();
        liveBrowsers.clear();
        popupOpeners.clear();
        engine = null;
        if (chromiumReady) CefriumBrowser.reapUnusedRenderers();
        super.onDestroy();
    }
}
