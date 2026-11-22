package dev.velocity.chromium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Pure-Java tab metadata: Android surfaces and Cefrium belong to the Activity. */
public final class TabRegistry {
    // Tabs are metadata, not one renderer each. Do not impose a UI tab cap.

    public static final class Tab {
        public final String id;
        /** A null value means the tab is not assigned to a group. */
        public String group;
        private String groupId;
        public boolean pinned;

        public String groupKey() {
            if (group == null || group.isBlank()) return null;
            if (groupId != null) return groupId;
            return UUID.nameUUIDFromBytes(("velocity-legacy-group:"
                + privateMode + ":" + group).getBytes(StandardCharsets.UTF_8)).toString();
        }
        /** Request a desktop User-Agent for this tab only. */
        public boolean desktopMode;
        /** CEF zoom steps (0 = 100%), per tab. */
        public double zoomLevel;
        public final boolean privateMode;
        public final boolean startupHome;
        public volatile String url;
        public String title;
        public boolean loading;
        public boolean canGoBack;
        public boolean canGoForward;

        private Tab(String url, boolean privateMode) { this(url, privateMode, false); }

        private Tab(String url, boolean privateMode, boolean startupHome) {
            this(url, privateMode, startupHome, UUID.randomUUID().toString());
        }

        private Tab(String url, boolean privateMode, boolean startupHome, String id) {
            this.startupHome = startupHome;
            this.id = id;
            this.url = url;
            this.title = "";
            this.privateMode = privateMode;
        }

        public String displayTitle() {
            if (startupHome) return "ホーム";
            // Chromium may report "about:blank" as a document title. Keep the
            // tab-switcher label friendly rather than exposing an internal URL.
            if ("about:blank".equals(url)) return "新しいタブ";
            return title != null && !title.trim().isEmpty() ? title : url;
        }
    }

    private final ArrayList<Tab> tabs = new ArrayList<>();
    private String selectedId;
    private String startupHomeId;

    public Tab add(String url, boolean privateMode) {
        Tab tab = new Tab(url, privateMode);
        tabs.add(tab);
        selectedId = tab.id;
        return tab;
    }

    /** Queue a link next to a live opener without changing the selected tab. */
    public Tab addBackground(String url, Tab opener, boolean sameGroup) {
        int index = tabs.indexOf(opener);
        if (index < 0) return null;
        Tab tab = new Tab(url, opener.privateMode);
        if (sameGroup) {
            tab.group = opener.group;
            tab.groupId = opener.groupKey();
        }
        tabs.add(index + 1, tab);
        return tab;
    }

    private static boolean isRegularHome(Tab tab) {
        return tab != null && tab.startupHome && !tab.privateMode && "about:blank".equals(tab.url);
    }

    /** Restore URLs without opening a browser or dropping the reserved home. */
    public Tab restoreRegularTab(String url) { return restoreRegularTab(url, false); }

    public Tab restoreRegularTab(String url, boolean homeFlag) {
        return restoreRegularTab(url, homeFlag, null, null);
    }

    /** Stable IDs preserve thumbnail and navigation-state associations across launches. */
    public Tab restoreRegularTab(String url, boolean homeFlag, String persistedId, String group) {
        return restoreRegularTab(url, homeFlag, persistedId, group, null);
    }

    public Tab restoreRegularTab(String url, boolean homeFlag, String persistedId, String group,
                                 String persistedGroupId) {
        boolean isHome = homeFlag && "about:blank".equals(url);
        String id;
        try {
            id = persistedId == null ? UUID.randomUUID().toString()
                : UUID.fromString(persistedId).toString();
        } catch (IllegalArgumentException error) {
            id = UUID.randomUUID().toString();
        }
        if (find(id) != null) id = UUID.randomUUID().toString();
        Tab tab = new Tab(url, false, isHome, id);
        tab.group = group == null || group.trim().isEmpty() ? null : group.trim();
        if (tab.group != null && persistedGroupId != null) {
            try {
                tab.groupId = UUID.fromString(persistedGroupId).toString();
            } catch (IllegalArgumentException ignored) {
                // Recover this entry using the same legacy name migration.
            }
        }
        tabs.add(tab);
        selectedId = tab.id;
        if (isRegularHome(tab) && startupHomeId == null) startupHomeId = tab.id;
        return tab;
    }

    public boolean createGroup(Tab tab, String name) {
        String cleanName = cleanGroupName(name);
        if (!tabs.contains(tab) || cleanName == null || tab.startupHome) return false;
        tab.group = cleanName;
        tab.groupId = UUID.randomUUID().toString();
        return true;
    }

    public boolean assignGroup(Tab tab, String key) {
        if (!tabs.contains(tab)) return false;
        if (key == null) {
            tab.group = null;
            tab.groupId = null;
            return true;
        }
        if (tab.startupHome) return false;
        for (Tab peer : tabs) {
            if (peer.privateMode != tab.privateMode || !key.equals(peer.groupKey())) continue;
            tab.group = peer.group;
            tab.groupId = key;
            return true;
        }
        return false;
    }

    public boolean renameGroup(String key, boolean privateMode, String name) {
        String cleanName = cleanGroupName(name);
        if (key == null || cleanName == null) return false;
        boolean changed = false;
        for (Tab tab : tabs) {
            if (tab.privateMode != privateMode || !key.equals(tab.groupKey())) continue;
            tab.groupId = key;
            tab.group = cleanName;
            changed = true;
        }
        return changed;
    }

    private static String cleanGroupName(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        return trimmed.substring(0, Math.min(40, trimmed.length()));
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

    /** Put pinned pages first without moving unrelated entries in the saved strip. */
    public List<Tab> orderedOverview(boolean privateMode) {
        ArrayList<Tab> result = new ArrayList<>(tabsForMode(privateMode));
        result.sort((a, b) -> Integer.compare(overviewBand(a), overviewBand(b)));
        return result;
    }

    private static int overviewBand(Tab tab) {
        return tab.startupHome ? 0 : tab.pinned ? 1 : 2;
    }

    public boolean togglePin(Tab tab) {
        if (!tabs.contains(tab) || tab.startupHome) return false;
        tab.pinned = !tab.pinned;
        return true;
    }

    /** Swap with the nearest visible peer; never moves a private tab or home as a side effect. */
    public boolean moveWithinBand(Tab tab, int direction, boolean withinGroup) {
        if (direction != -1 && direction != 1) return false;
        int index = tabs.indexOf(tab);
        if (index < 0 || tab.startupHome) return false;
        String group = withinGroup ? tab.groupKey() : null;
        for (int i = index + direction; i >= 0 && i < tabs.size(); i += direction) {
            Tab other = tabs.get(i);
            if (other.startupHome || other.privateMode != tab.privateMode
                    || other.pinned != tab.pinned) continue;
            if (withinGroup && !java.util.Objects.equals(group, other.groupKey())) continue;
            if (!withinGroup && !tab.pinned && (other.groupKey() != null
                    || tab.groupKey() != null)) continue;
            Collections.swap(tabs, index, i);
            return true;
        }
        return false;
    }

    /** Keep private and regular tabs in disjoint UI lists; never merge groups. */
    public List<Tab> tabsForMode(boolean privateMode) {
        ArrayList<Tab> matches = new ArrayList<>();
        for (Tab tab : tabs) if (tab.privateMode == privateMode) matches.add(tab);
        return Collections.unmodifiableList(matches);
    }

    /** Search exclusively within this privacy mode; never reveal other-mode metadata. */
    public List<Tab> searchForMode(boolean privateMode, String query) {
        return Collections.unmodifiableList(TabSearch.filter(tabsForMode(privateMode), query));
    }

    public int countForMode(boolean privateMode) {
        int count = 0;
        for (Tab tab : tabs) if (tab.privateMode == privateMode) count++;
        return count;
    }

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
            // Select a neighbor in the same mode before crossing the privacy boundary.
            Tab fallback = null;
            int distance = Integer.MAX_VALUE;
            for (int i = 0; i < tabs.size(); i++) {
                Tab candidate = tabs.get(i);
                if (candidate.privateMode != victim.privateMode) continue;
                int steps = Math.abs(i - index);
                if (steps < distance) { distance = steps; fallback = candidate; }
            }
            if (fallback == null && !tabs.isEmpty()) {
                fallback = tabs.get(Math.max(0, Math.min(index - 1, tabs.size() - 1)));
            }
            selectedId = fallback == null ? null : fallback.id;
        }
        return victim;
    }

    /** Restore the same metadata and position without duplicating a live tab. */
    public boolean reopenClosed(Tab tab, int formerIndex) {
        if (tab == null || find(tab.id) != null) return false;
        // Fresh callback identity: a delayed event from the closed engine must
        // not mutate the newly reopened tab even though the persisted ID matches.
        Tab restored = new Tab(tab.url, tab.privateMode, tab.startupHome, tab.id);
        restored.title = tab.title;
        restored.group = tab.group;
        restored.groupId = tab.groupKey();
        if (restored.groupId != null) {
            for (Tab peer : tabs) {
                if (peer.privateMode == restored.privateMode
                        && restored.groupId.equals(peer.groupKey())) {
                    restored.group = peer.group;
                    break;
                }
            }
        }
        restored.desktopMode = tab.desktopMode;
        restored.zoomLevel = tab.zoomLevel;
        restored.pinned = tab.pinned;
        tabs.add(Math.max(0, Math.min(formerIndex, tabs.size())), restored);
        if (isRegularHome(restored)) startupHomeId = restored.id;
        selectedId = restored.id;
        return true;
    }

    /** Private titles, URLs and tab identifiers never enter the saved snapshot. */
    public List<Tab> regularTabs() {
        ArrayList<Tab> regular = new ArrayList<>();
        for (Tab tab : tabs) if (!tab.privateMode) regular.add(tab);
        return Collections.unmodifiableList(regular);
    }
}
