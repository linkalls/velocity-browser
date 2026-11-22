package dev.velocity.chromium;

import org.junit.Test;

import static org.junit.Assert.*;

public class ReadingListTest {
    @Test public void onlyNormalHttpLinksWithoutUserInfo() {
        ReadingList list = new ReadingList();
        for (String invalid : new String[]{null, "about:blank", "javascript:alert(1)", "file:///tmp/file",
                 "https://user:pass@example.org/page", "https:///missing-host"}) {
            assertEquals(ReadingList.AddResult.UNSUPPORTED, list.add(invalid, "invalid"));
        }
        assertEquals(ReadingList.AddResult.ADDED, list.add("https://example.org/page", "Page"));
    }

    @Test public void caseSensitivePathUnreadAndRemoval() {
        ReadingList list = new ReadingList();
        String a = "https://example.org/a", b = "https://example.org/A";
        assertEquals(ReadingList.AddResult.ADDED, list.add(a, "lower"));
        assertEquals(ReadingList.AddResult.ADDED, list.add(b, "upper"));
        list.setRead(a, true);
        assertEquals(ReadingList.AddResult.ALREADY_SAVED, list.add(a, "different"));
        assertEquals(b, list.all().get(0).url());
        assertTrue(list.all().get(1).read());
        list.remove(b);
        assertEquals(1, list.all().size());
        assertEquals("lower", list.all().get(0).title());
    }

    @Test public void fullListDoesNotEvictSavedPages() {
        ReadingList list = new ReadingList();
        for (int i = 0; i < ReadingList.LIMIT; i++) {
            assertEquals(ReadingList.AddResult.ADDED, list.add("https://example.org/" + i, null));
        }
        assertEquals(ReadingList.AddResult.FULL, list.add("https://example.org/next", null));
        assertEquals("https://example.org/0", list.all().get(ReadingList.LIMIT - 1).url());
    }
}
