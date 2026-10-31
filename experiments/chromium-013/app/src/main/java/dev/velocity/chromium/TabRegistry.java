package dev.velocity.chromium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Pure-Java tab metadata: Android surfaces and Cefrium belong to the Activity. */
public final class TabRegistry {
    public static final int MAX_TABS = 12;
    // One reusable blank home can coexist with a full set of saved tabs.
    public static final int MAX_PERSISTED_TABS = MAX_TABS + 1;

    public static final class Tab {
        public final String id;
        public final boolean privateMode;
        public final boolean startupHome;
        public volatile String url;
        public String title;
        public boolean loading;
        public boolean canGoBack;
        public boolean canGoForward;

        private Tab(String url, boolean privateMode) { this(url, privateMode, false); }

        private Tab(String url, boolean privateMode, boolean startupHome) {
            this.startupHome = startupHome;
            this.id = UUID.randomUUID().toString();
            this.url = url;
            this.title = "";
            this.privateMode = privateMode;
        }

        public String displayTitle() {
            if (startupHome) return "ホーム";
            return title != null && !title.trim().isEmpty() ? title
                : (url.equals("about:blank") ? "新しいタブ" : url);
        }
    }

    private final ArrayList<Tab> tabs = new ArrayList<>();
    private String selectedId;
    private String startupHomeId;

    public Tab add(String url, boolean privateMode) {
        Tab home = find(startupHomeId);
        int capacity = MAX_TABS + (isRegularHome(home) ? 1 : 0);
        if (tabs.size() >= capacity) return null;
        Tab tab = new Tab(url, privateMode);
        tabs.add(tab);
        selectedId = tab.id;
        return tab;
    }

    private static boolean isRegularHome(Tab tab) {
        return tab != null && tab.startupHome && !tab.privateMode && "about:blank".equals(tab.url);
    }

    /** Restore URLs without opening a browser or dropping the reserved home. */
    public Tab restoreRegularTab(String url) { return restoreRegularTab(url, false); }

    public Tab restoreRegularTab(String url, boolean homeFlag) {
        boolean isHome = homeFlag && "about:blank".equals(url);
        if (tabs.size() >= MAX_PERSISTED_TABS) return null;
        boolean hasHome = false;
        for (Tab tab : tabs) if (isRegularHome(tab)) hasHome = true;
        if (tabs.size() >= MAX_TABS && !hasHome && !isHome) return null;
        Tab tab = new Tab(url, false, isHome);
        tabs.add(tab);
        selectedId = tab.id;
        if (isRegularHome(tab) && startupHomeId == null) startupHomeId = tab.id;
        return tab;
    }

    /** Startup selects a local blank home; saved pages remain dormant metadata. */
    public Tab selectStartupHome() {
        for (Tab tab : tabs) {
            if (isRegularHome(tab)) {
                startupHomeId = selectedId = tab.id;
                return tab;
            }
        }
        Tab home = new Tab("about:blank", false, true);
        tabs.add(0, home);
        startupHomeId = selectedId = home.id;
        return home;
    }

    public boolean isStartupHomeCurrent() {
        return isRegularHome(current());
    }

    public List<Tab> all() { return Collections.unmodifiableList(tabs); }
    public Tab current() { return find(selectedId); }

    public Tab find(String id) {
        if (id == null) return null;
        for (Tab tab : tabs) if (tab.id.equals(id)) return tab;
        return null;
    }

    public boolean select(String id) {
        if (find(id) == null) return false;
        selectedId = id;
        return true;
    }

    /** Closing a foreground tab selects its immediate left neighbor. */
    public Tab close(String id) {
        Tab victim = find(id);
        if (victim == null) return null;
        int index = tabs.indexOf(victim);
        tabs.remove(index);
        if (id.equals(startupHomeId)) startupHomeId = null;
        if (id.equals(selectedId)) {
            selectedId = tabs.isEmpty() ? null : tabs.get(Math.max(0, index - 1)).id;
        }
        return victim;
    }

    /** Private titles, URLs and tab identifiers never enter the saved snapshot. */
    public List<Tab> regularTabs() {
        ArrayList<Tab> regular = new ArrayList<>();
        for (Tab tab : tabs) if (!tab.privateMode) regular.add(tab);
        return Collections.unmodifiableList(regular);
    }
}
