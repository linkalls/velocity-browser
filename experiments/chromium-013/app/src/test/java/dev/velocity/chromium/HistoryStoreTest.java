package dev.velocity.chromium;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public final class HistoryStoreTest {
    @Test public void testRecordAndPrivateFilter() {
        HistoryStore store = new HistoryStore();
        store.record("about:blank", "Blank", false);
        assertEquals(0, store.all().size());

        store.record("https://secret.example", "Secret", true);
        assertEquals(0, store.all().size());

        store.record("https://news.example", "News", false);
        assertEquals(1, store.all().size());
        assertEquals("https://news.example", store.all().get(0).url);
    }

    @Test public void testDeduplicationOfLastUrl() {
        HistoryStore store = new HistoryStore();
        store.record("https://a.com", "A1", false);
        store.record("https://a.com", "A2", false);
        assertEquals(1, store.all().size());
        assertEquals("A2", store.all().get(0).title);
    }

    @Test public void testClearAndSearch() {
        HistoryStore store = new HistoryStore();
        store.record("https://google.com", "Google", false);
        store.record("https://duckduckgo.com", "DuckDuckGo", false);

        List<HistoryStore.HistoryEntry> results = store.search("duck");
        assertEquals(1, results.size());
        assertEquals("DuckDuckGo", results.get(0).title);

        store.clear();
        assertEquals(0, store.all().size());
    }

    @Test public void testSerializationCycle() {
        HistoryStore store = new HistoryStore();
        store.record("https://developer.android.com", "Android", false);

        String json = store.serialize();
        HistoryStore restored = new HistoryStore();
        restored.deserialize(json);

        assertEquals(1, restored.all().size());
        assertEquals("https://developer.android.com", restored.all().get(0).url);
    }
}
