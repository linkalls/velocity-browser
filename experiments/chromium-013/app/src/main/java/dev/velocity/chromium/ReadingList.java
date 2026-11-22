package dev.velocity.chromium;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/** User-selected page links, not cached documents or browsing history. */
final class ReadingList {
    static final int LIMIT = 100;
    enum AddResult { ADDED, ALREADY_SAVED, FULL, UNSUPPORTED }

    record Item(String url, String title, boolean read) {}
    private final ArrayList<Item> items = new ArrayList<>();

    static boolean supports(String url) {
        if (url == null || url.length() > 4096) return false;
        try {
            URI uri = new URI(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (URISyntaxException invalid) {
            return false;
        }
    }

    AddResult add(String url, String title) {
        if (!supports(url)) return AddResult.UNSUPPORTED;
        if (items.stream().anyMatch(row -> row.url().equals(url))) return AddResult.ALREADY_SAVED;
        if (items.size() == LIMIT) return AddResult.FULL;
        String label = title == null || title.trim().isEmpty() ? url : title.trim();
        items.add(0, new Item(url, label.substring(0, Math.min(180, label.length())), false));
        return AddResult.ADDED;
    }

    void setRead(String url, boolean read) {
        for (int i = 0; i < items.size(); i++) {
            Item row = items.get(i);
            if (row.url().equals(url)) {
                items.set(i, new Item(row.url(), row.title(), read));
                return;
            }
        }
    }

    void remove(String url) { items.removeIf(row -> row.url().equals(url)); }
    List<Item> all() { return java.util.Collections.unmodifiableList(new ArrayList<>(items)); }
}
