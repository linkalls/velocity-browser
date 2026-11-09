package dev.velocity.chromium;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure Java, data-only search for the native tab overview.
 *
 * Never looks up a global registry, renderer, browser history, clipboard,
 * thumbnail or disk cache. The caller supplies a privacy-scoped list.
 */
final class TabSearch {
    private TabSearch() {}

    /** NFKC unifies full-width Latin letters and numerals for Japanese input. */
    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    }

    static boolean matches(TabRegistry.Tab tab, String query) {
        if (tab == null) return false;
        String normalizedQuery = normalize(query).trim();
        if (normalizedQuery.isEmpty()) return true;
        // Terms can occur across different fields, e.g. "研究 example.com".
        String searchable = normalize(tab.displayTitle()) + " "
            + normalize(tab.url) + " " + normalize(tab.group);
        for (String term : normalizedQuery.split("\s+")) {
            if (!searchable.contains(term)) return false;
        }
        return true;
    }

    static List<TabRegistry.Tab> filter(List<TabRegistry.Tab> scope, String query) {
        ArrayList<TabRegistry.Tab> results = new ArrayList<>();
        for (TabRegistry.Tab tab : scope) {
            if (matches(tab, query)) results.add(tab);
        }
        return results;
    }

    /** Group view matches group names and their contained normal-mode tabs. */
    static boolean matchesGroup(String name, List<TabRegistry.Tab> members, String query) {
        String normalized = normalize(query).trim();
        if (normalized.isEmpty()) return true;
        // Keep a group if the group name alone fully matches or any member
        // matches; never join different private/regular groups in the caller.
        if (normalize(name).contains(normalized)) return true;
        for (TabRegistry.Tab member : members) {
            if (matches(member, query)) return true;
        }
        return false;
    }
}
