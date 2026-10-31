package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;

public final class TabRegistryTest {
    @Test public void switchesWithoutDiscardingTabs() {
        TabRegistry model = new TabRegistry();
        TabRegistry.Tab a = model.add("https://a.example/", false);
        TabRegistry.Tab b = model.add("https://b.example/", false);
        assertSame(b, model.current());
        assertTrue(model.select(a.id));
        assertSame(a, model.current());
        assertEquals(2, model.all().size());
        assertFalse(model.select("missing"));
    }

    @Test public void privateTabsNeverEnterPersistenceSnapshot() {
        TabRegistry model = new TabRegistry();
        model.add("https://normal.example/", false);
        model.add("https://secret.example/", true);
        assertEquals(2, model.all().size());
        assertEquals(1, model.regularTabs().size());
        assertEquals("https://normal.example/", model.regularTabs().get(0).url);
    }

    @Test public void backgroundCloseKeepsSelection() {
        TabRegistry model = new TabRegistry();
        TabRegistry.Tab a = model.add("https://a.example/", false);
        TabRegistry.Tab b = model.add("https://b.example/", false);
        model.close(a.id);
        assertSame(b, model.current());
    }

    @Test public void foregroundCloseSelectsNeighbor() {
        TabRegistry model = new TabRegistry();
        TabRegistry.Tab a = model.add("https://a.example/", false);
        TabRegistry.Tab b = model.add("https://b.example/", false);
        model.close(b.id);
        assertSame(a, model.current());
        model.close(a.id);
        assertNull(model.current());
    }

    @Test public void tabCapacityIsStable() {
        TabRegistry model = new TabRegistry();
        for (int i = 0; i < TabRegistry.MAX_TABS; i++) {
            assertNotNull(model.add("about:blank", false));
        }
        TabRegistry.Tab current = model.current();
        assertNull(model.add("about:blank", true));
        assertSame(current, model.current());
    }
    @Test public void startupKeepsGoogleSavedButSelectsOfflineHome() {
        TabRegistry model = new TabRegistry();
        TabRegistry.Tab google = model.restoreRegularTab("https://www.google.com/");
        TabRegistry.Tab home = model.selectStartupHome();
        assertEquals("about:blank", model.current().url);
        assertSame(google, model.find(google.id));
        assertEquals("https://www.google.com/", google.url);
        assertFalse(home.privateMode);
        assertSame(home, model.selectStartupHome());
        assertEquals(2, model.all().size());
        assertTrue(model.select(google.id));
        assertEquals("https://www.google.com/", model.current().url);
    }

    @Test public void fullSavedSessionIsNotDiscardedForStartupHome() {
        TabRegistry model = new TabRegistry();
        for (int i = 0; i < TabRegistry.MAX_TABS; i++) {
            assertNotNull(model.restoreRegularTab("https://saved.example/" + i));
        }
        model.selectStartupHome();
        assertEquals(TabRegistry.MAX_PERSISTED_TABS, model.all().size());
        assertTrue(model.isStartupHomeCurrent());
        TabRegistry restored = new TabRegistry();
        for (TabRegistry.Tab tab : model.regularTabs()) {
            assertNotNull(restored.restoreRegularTab(tab.url, tab.startupHome));
        }
        restored.selectStartupHome();
        assertEquals(TabRegistry.MAX_PERSISTED_TABS, restored.all().size());
        assertEquals("about:blank", restored.current().url);
        long saved = restored.regularTabs().stream().filter(t -> t.url.startsWith("https://saved.example/")).count();
        assertEquals(TabRegistry.MAX_TABS, saved);
        TabRegistry.Tab victim = restored.all().get(1);
        restored.close(victim.id);
        assertNotNull(restored.add("https://new.example/", false));
    }

    @Test public void privateBlankIsNotSelectedAsRegularStartupHome() {
        TabRegistry model = new TabRegistry();
        TabRegistry.Tab secret = model.add("about:blank", true);
        assertNotSame(secret, model.selectStartupHome());
        assertFalse(model.current().privateMode);
        assertEquals(1, model.regularTabs().size());
    }

    @Test public void reservedHomeAllowsTwelveUserTabsAndRemainsReusable() {
        TabRegistry model = new TabRegistry();
        TabRegistry.Tab home = model.selectStartupHome();
        for (int i = 0; i < TabRegistry.MAX_TABS; i++) {
            assertNotNull(model.add("https://saved.example/" + i, false));
        }
        assertNull(model.add("https://overflow.example/", false));
        assertSame(home, model.selectStartupHome());
        assertTrue(model.isStartupHomeCurrent());
    }
}
