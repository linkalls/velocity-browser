package dev.velocity.chromium;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public final class BookmarkStoreTest {
    @Test public void testAddAndDeduplicate() {
        BookmarkStore store = new BookmarkStore();
        assertNull(store.add("about:blank", "Blank"));
        assertNull(store.add("", "Empty"));

        BookmarkStore.Bookmark b1 = store.add("https://example.com", "Example");
        assertNotNull(b1);
        assertEquals("https://example.com", b1.url);
        assertEquals("Example", b1.title);
        assertTrue(store.isBookmarked("https://example.com"));
        assertFalse(store.isBookmarked("https://other.com"));

        // Deduplication
        BookmarkStore.Bookmark b2 = store.add("https://example.com", "Example 2");
        assertSame(b1, b2);
        assertEquals(1, store.all().size());
    }

    @Test public void testRemoveAndSearch() {
        BookmarkStore store = new BookmarkStore();
        store.add("https://news.ycombinator.com", "Hacker News");
        store.add("https://github.com", "GitHub");

        assertEquals(2, store.all().size());
        List<BookmarkStore.Bookmark> search1 = store.search("hacker");
        assertEquals(1, search1.size());
        assertEquals("Hacker News", search1.get(0).title);

        store.removeByUrl("https://github.com");
        assertEquals(1, store.all().size());
        assertFalse(store.isBookmarked("https://github.com"));
    }

    @Test public void testSerializationCycle() {
        BookmarkStore store = new BookmarkStore();
        store.add("https://developer.android.com", "Android Developers");
        store.add("https://chromium.org", "Chromium");

        String json = store.serialize();
        BookmarkStore restored = new BookmarkStore();
        restored.deserialize(json);

        assertEquals(2, restored.all().size());
        assertTrue(restored.isBookmarked("https://developer.android.com"));
        assertTrue(restored.isBookmarked("https://chromium.org"));
    }
}
