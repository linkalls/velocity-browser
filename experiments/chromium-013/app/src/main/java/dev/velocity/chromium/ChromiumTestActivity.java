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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
    // RAM-only history. Private tabs never appear in the recently closed list.
    private final List<TabRegistry.Tab> recentlyClosedRegularTabs = new ArrayList<>();
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
                    if (!address.hasFocus()) address.setText(displayAddress(url));
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
        if (engine != null) {
            FrameLayout surface = engine.getSurfaceContainer();
            surface.post(() -> positionChromiumSurface(surface, browserHeader, browserFooter));
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
            if (tab.group != null && !tab.group.isEmpty()) {
                namedGroups.computeIfAbsent(tab.group, key -> new ArrayList<>()).add(tab);
            }
        }
        // Only show normal browsing groups in the dedicated Groups section.
        if (mode == TAB_VIEW_GROUPS) {
            namedGroups.clear();
            for (TabRegistry.Tab tab : tabRegistry.regularTabs()) {
                if (tab.group != null && !tab.group.isEmpty()) {
                    namedGroups.computeIfAbsent(tab.group, key -> new ArrayList<>()).add(tab);
                }
            }
        }
        String headingText = expandedGroup != null ? expandedGroup
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(14), dp(8), dp(14), dp(90));
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid);
        int availableDp = BrowserUiLayout.availableSheetWidthDp(
            getResources().getConfiguration().screenWidthDp);
        int columns = BrowserUiLayout.columnsForAvailableWidthDp(availableDp);
        int previewHeight = BrowserUiLayout.tabPreviewHeightDp(columns);
        // The grid uses lightweight holder rows and binds actual preview cards
        // *only near the viewport*. Rebuilding for a search never creates
        // hundreds of ImageViews or instantiates additional browser renderers.
        List<java.util.function.Supplier<View>> entries = new ArrayList<>();
        List<FrameLayout> cellHolders = new ArrayList<>();
        int cellHeightDp = mode == TAB_VIEW_RECENT ? 80 : previewHeight + 58;
        int rowHeightPx = dp(cellHeightDp + 12);
        Runnable bindViewport = () -> {
            if (destroyed || !scroll.isAttachedToWindow()) return;
            int viewport = Math.max(dp(320), scroll.getHeight());
            int visibleStart = Math.max(0, scroll.getScrollY() - viewport);
            int visibleEnd = scroll.getScrollY() + viewport * 2;
            for (int index = 0; index < entries.size(); index++) {
                FrameLayout holder = cellHolders.get(index);
                if (holder == null) continue;
                int rowTop = (index / columns) * rowHeightPx;
                boolean nearby = rowTop + rowHeightPx >= visibleStart
                    && rowTop <= visibleEnd;
                if (nearby && holder.getChildCount() == 0) {
                    holder.addView(entries.get(index).get(),
                        new FrameLayout.LayoutParams(-1, -1));
                } else if (!nearby && holder.getChildCount() > 0) {
                    holder.removeAllViews();
                }
            }
        };
        scroll.setOnScrollChangeListener((View view, int x, int y,
                                           int oldX, int oldY) -> bindViewport.run());
        Runnable renderGrid = () -> {
            if (destroyed || dialog[0] == null || !dialog[0].isShowing()) return;
            String query = searchInput.getText().toString();
            boolean searching = !query.trim().isEmpty();
            grid.removeAllViews();
            entries.clear();
            cellHolders.clear();
            int activePosition = -1;
            if (mode == TAB_VIEW_RECENT) {
                for (TabRegistry.Tab closed : TabSearch.filter(recentlyClosedRegularTabs, query)) {
                    entries.add(() -> makeRecentTabCard(closed, dialog));
                }
            } else if (expandedGroup != null) {
                for (TabRegistry.Tab member : namedGroups.getOrDefault(
                        expandedGroup, Collections.emptyList())) {
                    if (!TabSearch.matches(member, query)) continue;
                    entries.add(() -> makeOverviewTabCard(member, previewHeight, dialog,
                        mode, expandedGroup, query));
                    if (member == tabRegistry.current()) activePosition = entries.size() - 1;
                }
            } else if (mode == TAB_VIEW_GROUPS) {
                for (Map.Entry<String, List<TabRegistry.Tab>> group : namedGroups.entrySet()) {
                    String groupName = group.getKey();
                    List<TabRegistry.Tab> members = group.getValue();
                    if (!TabSearch.matchesGroup(groupName, members, query)) continue;
                    entries.add(() -> makeOverviewGroupCard(groupName, members,
                        previewHeight, dialog, mode));
                    if (members.contains(tabRegistry.current())) activePosition = entries.size() - 1;
                }
            } else if (searching) {
                // Crucial: return *individual* matching cards from a group,
                // rather than hiding a found page inside its 2x2 group collage.
                for (TabRegistry.Tab tab : tabRegistry.searchForMode(privateMode, query)) {
                    entries.add(() -> makeOverviewTabCard(tab, previewHeight, dialog,
                        mode, null, query));
                    if (tab == tabRegistry.current()) activePosition = entries.size() - 1;
                }
            } else {
                Set<String> seenGroups = new HashSet<>();
                for (TabRegistry.Tab tab : tabRegistry.tabsForMode(privateMode)) {
                    if (tab.group != null && !tab.group.isEmpty()) {
                        if (seenGroups.add(tab.group)) {
                            String groupName = tab.group;
                            List<TabRegistry.Tab> members = namedGroups.get(groupName);
                            entries.add(() -> makeOverviewGroupCard(groupName, members,
                                previewHeight, dialog, mode));
                            if (members.contains(tabRegistry.current())) {
                                activePosition = entries.size() - 1;
                            }
                        }
                    } else {
                        entries.add(() -> makeOverviewTabCard(tab, previewHeight, dialog,
                            mode, null, query));
                        if (tab == tabRegistry.current()) activePosition = entries.size() - 1;
                    }
                }
            }
            resultCount.setVisibility(searching ? View.VISIBLE : View.GONE);
            if (searching) resultCount.setText("検索結果 " + entries.size() + "件");
            if (entries.isEmpty()) {
                TextView empty = text(searching ? "一致するタブがないよ"
                    : mode == TAB_VIEW_PRIVATE ? "非公開タブはまだないよ"
                    : mode == TAB_VIEW_GROUPS ? "作成したタブグループがここに表示されるよ"
                    : mode == TAB_VIEW_RECENT ? "最近閉じたタブはないよ"
                    : "タブはまだないよ", 15, SECONDARY, false);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(12), dp(80), dp(12), dp(80));
                grid.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            }
            for (int i = 0; i < entries.size(); i += columns) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.TOP);
                grid.addView(row, new LinearLayout.LayoutParams(-1, rowHeightPx));
                for (int column = 0; column < columns; column++) {
                    int position = i + column;
                    FrameLayout holder = new FrameLayout(this);
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(
                        0, dp(cellHeightDp), 1);
                    if (column != 0) cell.leftMargin = dp(BrowserUiLayout.CARD_GAP_DP);
                    row.addView(holder, cell);
                    cellHolders.add(position < entries.size() ? holder : null);
                }
            }
            final int selection = activePosition;
            grid.post(() -> {
                if (destroyed || !scroll.isAttachedToWindow()) return;
                if (searching || selection < 0 || entries.size() <= 4) {
                    scroll.scrollTo(0, 0);
                } else {
                    int top = (selection / columns) * rowHeightPx;
                    scroll.scrollTo(0, Math.max(0, top - dp(8)));
                }
                bindViewport.run();
            });
        };
        // 100ms debounce prevents repeated 500-holder layout passes when a
        // user types quickly. The active keyboard does not reload WebContents.
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
        String cardTitle = tab.displayTitle();
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
        card.setContentDescription(tab.displayTitle() + " タブ");
        card.setOnClickListener(v -> {
            dialog[0].dismiss();
            selectTab(tab.id);
        });
        card.setOnLongClickListener(v -> {
            dialog[0].dismiss();
            showTabGroupDialog(tab.id);
            return true;
        });
        return card;
    }

    private View makeOverviewGroupCard(String name, List<TabRegistry.Tab> members,
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
                showTabGroupOptions(name, members, mode);
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
            showTabOverview(mode, name);
        });
        card.setOnLongClickListener(v -> {
            dialog[0].dismiss();
            showTabGroupOptions(name, members, mode);
            return true;
        });
        return card;
    }

    private void showTabGroupOptions(String groupName, List<TabRegistry.Tab> members,
                                     int mode) {
        // The group is a *metadata* association. Removing a group never
        // closes the underlying tabs or discards page/scroll state.
        new AlertDialog.Builder(this).setTitle(groupName)
            .setItems(new String[]{"グループを開く", "名前を変更", "グループを解除"},
                (unused, selected) -> {
                    if (selected == 0) {
                        showTabOverview(mode, groupName);
                    } else if (selected == 1) {
                        EditText input = new EditText(this);
                        input.setSingleLine(true);
                        input.setText(groupName);
                        new AlertDialog.Builder(this).setTitle("グループ名を変更")
                            .setView(input).setPositiveButton("保存", (d, which) -> {
                                String next = input.getText().toString().trim();
                                if (next.isEmpty()) return;
                                next = next.substring(0, Math.min(40, next.length()));
                                for (TabRegistry.Tab member : members) member.group = next;
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
                                for (TabRegistry.Tab member : members) member.group = null;
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
            recentlyClosedRegularTabs.remove(closed);
            createTab(closed.url, false);
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
                        new AlertDialog.Builder(this).setTitle("全タブを閉じる？")
                            .setMessage(candidates.size() + "件の" +
                                (privateMode ? "非公開" : "通常") +
                                "タブを閉じるよ。ホームは残すよ。")
                            .setNegativeButton("キャンセル", null)
                            .setPositiveButton("閉じる", (confirm, which) -> {
                                dialog.dismiss();
                                for (TabRegistry.Tab candidate : candidates) {
                                    if (candidate.startupHome) continue;
                                    closeTabDirect(candidate.id);
                                }
                                showTabOverview(mode, null);
                            }).show();
                    }
                }).show();
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
        if (!victim.privateMode && !victim.startupHome && isPersistable(victim.url)) {
            recentlyClosedRegularTabs.add(0, victim);
            if (recentlyClosedRegularTabs.size() > 20) {
                recentlyClosedRegularTabs.remove(recentlyClosedRegularTabs.size() - 1);
            }
        }
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
        TabRegistry.Tab current = tabRegistry.current();
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
                    "ページ先頭で引き下ろすと再読み込み", "pull_to_refresh", true);
                addSettingsAction(content, R.drawable.ic_browser_plus,
                    "ホームのショートカットを追加", "長押しすると編集・削除できるよ", () -> {
                        dialog[0].dismiss();
                        editSpeedDial(null);
                    });
                break;
            case "タブ":
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
