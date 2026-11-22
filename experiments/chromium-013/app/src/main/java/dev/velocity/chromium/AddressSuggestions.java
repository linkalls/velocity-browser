package dev.velocity.chromium;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Local omnibox candidates only; a query is never sent to a remote suggestion API. */
final class AddressSuggestions {
    record Item(String url, String title, String openTabId, String source) {}
    private record Match(Item item, int score) {}

    static List<Item> choose(String query, List<Item> local, boolean privateMode) {
        String needle = normalize(query);
        if (privateMode || needle.length() < 2 || needle.length() > 512) return List.of();
        ArrayList<Match> matches = new ArrayList<>();
        for (Item item : local) {
            if (item.url() == null || !(item.url().startsWith("https://")
                    || item.url().startsWith("http://"))) continue;
            String url = normalize(item.url());
            String withoutScheme = url.substring(url.indexOf("://") + 3);
            String title = normalize(item.title());
            int how;
            if (url.equals(needle) || withoutScheme.equals(needle)) how = 100;
            else if (withoutScheme.startsWith(needle) || title.startsWith(needle)) how = 70;
            else if (url.contains(needle) || title.contains(needle)) how = 35;
            else continue;
            matches.add(new Match(item, how + (item.openTabId() != null ? 12 : 0)));
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed());
        Set<String> seen = new HashSet<>();
        ArrayList<Item> selected = new ArrayList<>();
        for (Match match : matches) {
            // URL path and query can be case-sensitive. Deduplicate exact strings only.
            if (seen.add(match.item().url())) selected.add(match.item());
            if (selected.size() == 4) break;
        }
        return selected;
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value.trim(),
            Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
