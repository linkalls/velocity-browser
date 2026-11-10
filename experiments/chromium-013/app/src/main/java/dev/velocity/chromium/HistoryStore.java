package dev.velocity.chromium;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure data model and JSON serializer for Velocity browsing history.
 * Bound to 500 items to avoid unbounded memory consumption.
 * Strictly ignores private tabs and about:blank.
 */
public final class HistoryStore {
    public static final int MAX_ENTRIES = 500;

    public static final class HistoryEntry {
        public final String id;
        public final String url;
        public final String title;
        public final long visitedAt;

        public HistoryEntry(String id, String url, String title, long visitedAt) {
            this.id = id != null ? id : java.util.UUID.randomUUID().toString();
            this.url = url != null ? url : "";
            this.title = title != null && !title.isEmpty() ? title : url;
            this.visitedAt = visitedAt > 0 ? visitedAt : System.currentTimeMillis();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HistoryEntry)) return false;
            HistoryEntry that = (HistoryEntry) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private final List<HistoryEntry> entries = new ArrayList<>();

    public HistoryStore() {}

    public synchronized List<HistoryEntry> all() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized void record(String url, String title, boolean privateMode) {
        if (privateMode || url == null || url.trim().isEmpty()
                || "about:blank".equalsIgnoreCase(url)
                || url.startsWith("data:")) {
            return;
        }
        String cleanUrl = url.trim();
        String cleanTitle = title != null && !title.trim().isEmpty() ? title.trim() : cleanUrl;
        // If the most recent entry has the same URL, update its visit time
        if (!entries.isEmpty() && entries.get(0).url.equalsIgnoreCase(cleanUrl)) {
            entries.remove(0);
        }
        entries.add(0, new HistoryEntry(null, cleanUrl, cleanTitle, System.currentTimeMillis()));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }

    public synchronized boolean removeById(String id) {
        if (id == null) return false;
        return entries.removeIf(e -> id.equals(e.id));
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized List<HistoryEntry> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return all();
        }
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        List<HistoryEntry> result = new ArrayList<>();
        for (HistoryEntry e : entries) {
            if (e.title.toLowerCase(java.util.Locale.ROOT).contains(q)
                    || e.url.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                result.add(e);
            }
        }
        return result;
    }

    public synchronized String serialize() {
        JSONArray array = new JSONArray();
        for (HistoryEntry e : entries) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", e.id);
                obj.put("url", e.url);
                obj.put("title", e.title);
                obj.put("visitedAt", e.visitedAt);
                array.put(obj);
            } catch (JSONException ignored) {}
        }
        return array.toString();
    }

    public synchronized void deserialize(String json) {
        entries.clear();
        if (json == null || json.trim().isEmpty()) return;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String url = obj.optString("url", "");
                if (url.isEmpty()) continue;
                String id = obj.optString("id", null);
                String title = obj.optString("title", "");
                long visitedAt = obj.optLong("visitedAt", 0);
                entries.add(new HistoryEntry(id, url, title, visitedAt));
            }
        } catch (JSONException ignored) {}
    }
}
