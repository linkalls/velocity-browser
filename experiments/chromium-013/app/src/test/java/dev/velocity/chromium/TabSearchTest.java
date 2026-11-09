package dev.velocity.chromium;

import org.junit.Test;
import java.util.List;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Search is evaluated without starting Chromium or loading any page content. */
public final class TabSearchTest {
    @Test public void matchesTitleUrlAndGroupWithUnicodeNormalization() {
        TabRegistry registry = new TabRegistry();
        TabRegistry.Tab research = registry.add("https://example.com/Reports/Index", false);
        research.title = "研究記事 ２０２６";
        research.group = "日本史ノート";
        assertEquals(List.of(research), registry.searchForMode(false, "研究"));
        assertEquals(List.of(research), registry.searchForMode(false, "ＥＸＡＭＰＬＥ．ＣＯＭ"));
        assertEquals(List.of(research), registry.searchForMode(false, "INDEX"));
        assertEquals(List.of(research), registry.searchForMode(false, "２０２６"));
        assertEquals(List.of(research), registry.searchForMode(false, "日本史"));
        assertEquals(List.of(research), registry.searchForMode(false, "研究 INDEX"));
        assertTrue(registry.searchForMode(false, "研究 数学").isEmpty());
    }

    @Test public void normalAndPrivateSearchNeverCrossBoundaries() {
        TabRegistry registry = new TabRegistry();
        TabRegistry.Tab publicTab = registry.add("https://normal.example/secret-query", false);
        publicTab.title = "secret-query in a public tab";
        TabRegistry.Tab secret = registry.add("https://private.example/secret-query", true);
        secret.title = "secret-query in a private tab";
        assertEquals(List.of(publicTab), registry.searchForMode(false, "secret-query"));
        assertEquals(List.of(secret), registry.searchForMode(true, "secret-query"));
        assertFalse(registry.searchForMode(false, "private.example").contains(secret));
        assertTrue(registry.searchForMode(true, "normal.example").isEmpty());
    }

    @Test public void groupedMemberRemainsDiscoverableInFlattenedResults() {
        TabRegistry registry = new TabRegistry();
        TabRegistry.Tab first = registry.add("https://a.example", false);
        TabRegistry.Tab second = registry.add("https://b.example", false);
        first.group = second.group = "Daily reading";
        second.title = "Advanced research";
        assertEquals(List.of(second), registry.searchForMode(false, "advanced"));
        assertEquals(List.of(first, second), registry.searchForMode(false, "daily"));
        assertTrue(TabSearch.matchesGroup("Daily reading", List.of(first, second), "advanced"));
        assertTrue(TabSearch.matchesGroup("Daily reading", List.of(first, second), "DAILY"));
        assertFalse(TabSearch.matchesGroup("Daily reading", List.of(first, second), "science"));
    }

    @Test public void emptyQueryDoesNotAlterOrderOrCapResults() {
        TabRegistry registry = new TabRegistry();
        List<TabRegistry.Tab> normal = new ArrayList<>();
        List<TabRegistry.Tab> privateTabs = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            TabRegistry.Tab tab = registry.add("https://example.com/article/" + i, i % 5 == 0);
            if (tab.privateMode) privateTabs.add(tab);
            else normal.add(tab);
        }
        assertEquals(normal, registry.searchForMode(false, ""));
        assertEquals(privateTabs, registry.searchForMode(true, "  "));
        assertEquals(1, registry.searchForMode(false, "article/499").size());
        assertEquals(0, registry.searchForMode(true, "article/499").size());
        assertEquals(normal, registry.tabsForMode(false));
    }

    @Test public void recentlyClosedSearchIsPureAndDoesNotRetainMetadata() {
        TabRegistry registry = new TabRegistry();
        TabRegistry.Tab closed = registry.add("https://example.com/history", false);
        closed.title = "Archived article";
        registry.close(closed.id);
        assertEquals(List.of(closed), TabSearch.filter(List.of(closed), "archived"));
        assertTrue(TabSearch.filter(List.of(closed), "missing").isEmpty());
        assertTrue(registry.searchForMode(false, "archived").isEmpty());
    }
}
