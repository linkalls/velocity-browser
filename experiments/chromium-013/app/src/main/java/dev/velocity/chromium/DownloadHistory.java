package dev.velocity.chromium;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Metadata for completed, regular-mode downloads. No origin or credentials. */
final class DownloadHistory {
    private static final int LIMIT = 30;

    record Entry(String contentUri, String name, String mime, long bytes) {
        Entry {
            Objects.requireNonNull(contentUri);
            Objects.requireNonNull(name);
            Objects.requireNonNull(mime);
            if (!contentUri.matches("content://media/(?:external|external_primary)/downloads/[0-9]+")
                    || name.trim().isEmpty() || name.length() > 255 || mime.length() > 120
                    || bytes < 0) throw new IllegalArgumentException("Invalid download record");
        }
    }

    static List<Entry> insert(List<Entry> current, Entry entry) {
        List<Entry> next = new ArrayList<>(Math.min(LIMIT, current.size() + 1));
        next.add(entry);
        for (Entry old : current) {
            if (next.size() == LIMIT) break;
            if (!old.contentUri().equals(entry.contentUri())) next.add(old);
        }
        return java.util.Collections.unmodifiableList(next);
    }

    static List<Entry> remove(List<Entry> current, String uri) {
        List<Entry> next = new ArrayList<>(current);
        next.removeIf(entry -> entry.contentUri().equals(uri));
        return java.util.Collections.unmodifiableList(next);
    }

    private DownloadHistory() {}
}
