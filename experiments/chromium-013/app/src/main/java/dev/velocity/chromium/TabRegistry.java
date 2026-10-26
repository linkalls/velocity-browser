package dev.velocity.chromium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Pure-Java tab metadata: Android surfaces and Cefrium belong to the Activity. */
public final class TabRegistry {
    public static final int MAX_TABS = 12;

    public static final class Tab {
        public final String id;
        public final boolean privateMode;
        public volatile String url;
        public String title;
        public boolean loading;
        public boolean canGoBack;
        public boolean canGoForward;

        private Tab(String url, boolean privateMode) {
            this.id = UUID.randomUUID().toString();
            this.url = url;
            this.title = "";
            this.privateMode = privateMode;
        }

        public String displayTitle() {
            return title != null && !title.trim().isEmpty() ? title
                : (url.equals("about:blank") ? "新しいタブ" : url);
        }
    }

    private final ArrayList<Tab> tabs = new ArrayList<>();
    private String selectedId;

    public Tab add(String url, boolean privateMode) {
        if (tabs.size() >= MAX_TABS) return null;
        Tab tab = new Tab(url, privateMode);
        tabs.add(tab);
        selectedId = tab.id;
        return tab;
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
