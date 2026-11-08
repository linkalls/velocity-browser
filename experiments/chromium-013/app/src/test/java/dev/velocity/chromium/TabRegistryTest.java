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

    @Test public void privateAndRegularTabListsAreDisjoint() {
        TabRegistry model = new TabRegistry();
        TabRegistry.Tab normal = model.add("https://normal.example", false);
        TabRegistry.Tab secret = model.add("https://secret.example", true);
        assertEquals(1, model.countForMode(false));
        assertEquals(1, model.countForMode(true));
        assertEquals(java.util.List.of(normal), model.tabsForMode(false));
        assertEquals(java.util.List.of(secret), model.tabsForMode(true));
        assertFalse(model.regularTabs().contains(secret));
    }

    @Test public void closingPrivateTabSelectsOtherPrivateBeforeNormal() {
        TabRegistry model = new TabRegistry();
        model.add("https://normal.example", false);
        TabRegistry.Tab first = model.add("https://private-1.example", true);
        TabRegistry.Tab second = model.add("https://private-2.example", true);
        model.close(second.id);
        assertSame(first, model.current());
        assertEquals(1, model.countForMode(true));
    }

    @Test public void desktopModeIsTabSpecific() {
        TabRegistry model = new TabRegistry();
        TabRegistry.Tab normal = model.add("https://example.com", false);
        TabRegistry.Tab privateTab = model.add("https://example.com", true);
        normal.desktopMode = true;
        assertFalse(privateTab.desktopMode);
        assertTrue(normal.desktopMode);
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

    @Test public void tabCountIsUnlimitedAndStable() {
        TabRegistry model = new TabRegistry();
        for (int i = 0; i < 250; i++) {
            assertNotNull(model.add("https://example.com/" + i, i % 2 == 0));
        }
        assertEquals(250, model.all().size());
    }
    @Test public void restoredIdsAndGroupsSurvive() {
        TabRegistry model = new TabRegistry();
        String id = java.util.UUID.randomUUID().toString();
        TabRegistry.Tab tab = model.restoreRegularTab("https://example.com", false, id, "Research");
        assertEquals(id, tab.id);
        assertEquals("Research", tab.group);
        assertNotEquals(id, model.restoreRegularTab("https://another.example", false, id, null).id);
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
        for (int i = 0; i < 50; i++) {
            assertNotNull(model.restoreRegularTab("https://saved.example/" + i));
        }
        model.selectStartupHome();
        assertEquals(51, model.all().size());
        assertTrue(model.isStartupHomeCurrent());
        TabRegistry restored = new TabRegistry();
        for (TabRegistry.Tab tab : model.regularTabs()) {
            assertNotNull(restored.restoreRegularTab(tab.url, tab.startupHome));
        }
        restored.selectStartupHome();
        assertEquals(51, restored.all().size());
        assertEquals("about:blank", restored.current().url);
        long saved = restored.regularTabs().stream().filter(t -> t.url.startsWith("https://saved.example/")).count();
        assertEquals(50, saved);
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

    @Test public void reservedHomeAllowsMoreTabsAndRemainsReusable() {
        TabRegistry model = new TabRegistry();
        TabRegistry.Tab home = model.selectStartupHome();
        for (int i = 0; i < 50; i++) {
            assertNotNull(model.add("https://saved.example/" + i, false));
        }
        assertNotNull(model.add("https://overflow.example/", false));
        assertSame(home, model.selectStartupHome());
        assertTrue(model.isStartupHomeCurrent());
    }
}
