package dev.velocity.chromium;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure data model and JSON serializer for Velocity bookmarks.
 * Decoupled from Android Context for direct JVM unit testability.
 */
public final class BookmarkStore {
    public static final class Bookmark {
        public final String id;
        public final String url;
        public final String title;
        public final long addedAt;

        public Bookmark(String id, String url, String title, long addedAt) {
            this.id = id != null ? id : java.util.UUID.randomUUID().toString();
            this.url = url != null ? url : "";
            this.title = title != null && !title.isEmpty() ? title : url;
            this.addedAt = addedAt > 0 ? addedAt : System.currentTimeMillis();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Bookmark)) return false;
            Bookmark bookmark = (Bookmark) o;
            return Objects.equals(id, bookmark.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private final List<Bookmark> items = new ArrayList<>();

    public BookmarkStore() {}

    public synchronized List<Bookmark> all() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    public synchronized boolean isBookmarked(String url) {
        if (url == null || url.isEmpty()) return false;
        for (Bookmark b : items) {
            if (url.equalsIgnoreCase(b.url)) return true;
        }
        return false;
    }

    public synchronized Bookmark add(String url, String title) {
        if (url == null || url.trim().isEmpty() || "about:blank".equalsIgnoreCase(url)) {
            return null;
        }
        for (Bookmark b : items) {
            if (url.equalsIgnoreCase(b.url)) {
                return b;
            }
        }
        Bookmark bookmark = new Bookmark(null, url.trim(), title != null ? title.trim() : "", System.currentTimeMillis());
        items.add(0, bookmark);
        return bookmark;
    }

    public synchronized boolean removeByUrl(String url) {
        if (url == null) return false;
        return items.removeIf(b -> url.equalsIgnoreCase(b.url));
    }

    public synchronized boolean removeById(String id) {
        if (id == null) return false;
        return items.removeIf(b -> id.equals(b.id));
    }

    public synchronized List<Bookmark> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return all();
        }
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        List<Bookmark> result = new ArrayList<>();
        for (Bookmark b : items) {
            if (b.title.toLowerCase(java.util.Locale.ROOT).contains(q)
                    || b.url.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                result.add(b);
            }
        }
        return result;
    }

    public synchronized String serialize() {
        JSONArray array = new JSONArray();
        for (Bookmark b : items) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", b.id);
                obj.put("url", b.url);
                obj.put("title", b.title);
                obj.put("addedAt", b.addedAt);
                array.put(obj);
            } catch (JSONException ignored) {}
        }
        return array.toString();
    }

    public synchronized void deserialize(String json) {
        items.clear();
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
                long addedAt = obj.optLong("addedAt", 0);
                items.add(new Bookmark(id, url, title, addedAt));
            }
        } catch (JSONException ignored) {}
    }
}
