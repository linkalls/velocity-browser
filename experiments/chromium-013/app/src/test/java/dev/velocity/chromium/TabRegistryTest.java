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

    @Test public void blankTabTitleUsesReadableLabel() {
        TabRegistry model = new TabRegistry();
        TabRegistry.Tab blank = model.add("about:blank", false);
        blank.title = "about:blank";
        assertEquals("新しいタブ", blank.displayTitle());
        blank.title = "";
        assertEquals("新しいタブ", blank.displayTitle());
        assertEquals("ホーム", model.selectStartupHome().displayTitle());
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
    @Test public void reopenPreservesPositionAndPreferencesButNotOldCallbackIdentity() {
        TabRegistry registry = new TabRegistry();
        TabRegistry.Tab home = registry.selectStartupHome();
        TabRegistry.Tab saved = registry.add("https://example.test/notes", false);
        TabRegistry.Tab other = registry.add("https://example.test/other", false);
        saved.group = "notes";
        saved.desktopMode = true;
        saved.zoomLevel = 2;
        saved.loading = true;
        registry.close(saved.id);
        assertTrue(registry.reopenClosed(saved, 1));
        assertFalse(registry.reopenClosed(saved, 1));
        TabRegistry.Tab reopened = registry.current();
        assertNotSame(saved, reopened);
        assertEquals(saved.id, reopened.id);
        assertEquals("notes", reopened.group);
        assertTrue(reopened.desktopMode);
        assertEquals(2.0, reopened.zoomLevel, 0.01);
        assertFalse(reopened.loading);
        assertEquals(java.util.List.of(home, reopened, other), registry.all());
    }

    @Test public void batchCloseAndUndoRestoresInterleavedTabsWithoutTouchingOtherMode() {
        TabRegistry registry = new TabRegistry();
        TabRegistry.Tab home = registry.selectStartupHome();
        TabRegistry.Tab one = registry.add("https://example.test/one", false);
        TabRegistry.Tab secret = registry.add("https://private.example/", true);
        TabRegistry.Tab two = registry.add("https://example.test/two", false);
        assertTrue(registry.select(one.id));
        int later = registry.all().indexOf(two);
        registry.close(two.id);
        int earlier = registry.all().indexOf(one);
        registry.close(one.id);
        assertEquals(java.util.List.of(home, secret), registry.all());
        assertTrue(registry.reopenClosed(one, earlier));
        assertTrue(registry.reopenClosed(two, later));
        assertTrue(registry.select(one.id));
        assertEquals(java.util.List.of(home.id, one.id, secret.id, two.id),
            registry.all().stream().map(tab -> tab.id).collect(java.util.stream.Collectors.toList()));
        assertSame(secret, registry.all().get(2));
        assertFalse(registry.regularTabs().contains(secret));
        assertEquals(one.id, registry.current().id);
    }

    @Test public void queuedLinksRespectOpenerPrivacyGroupAndSelection() {
        TabRegistry registry = new TabRegistry();
        TabRegistry.Tab opener = registry.add("https://private.example/", true);
        opener.group = "research";
        TabRegistry.Tab publicTab = registry.add("https://public.example/", false);
        TabRegistry.Tab grouped = registry.addBackground("https://private.example/b", opener, true);
        TabRegistry.Tab ungrouped = registry.addBackground("https://private.example/c", opener, false);
        assertSame(publicTab, registry.current());
        assertTrue(grouped.privateMode);
        assertEquals("research", grouped.group);
        assertTrue(ungrouped.privateMode);
        assertNull(ungrouped.group);
        assertEquals(java.util.List.of(opener, ungrouped, grouped, publicTab), registry.all());
        assertEquals(java.util.List.of(publicTab), registry.regularTabs());
        registry.close(opener.id);
        assertNull(registry.addBackground("https://private.example/d", opener, true));
    }

    @Test public void legacyGroupMigrationAndSeparateSameNameGroups() {
        TabRegistry registry = new TabRegistry();
        TabRegistry.Tab a = registry.restoreRegularTab("https://one.test/", false, null, "Notes");
        TabRegistry.Tab b = registry.restoreRegularTab("https://two.test/", false, null, "Notes");
        assertEquals(a.groupKey(), b.groupKey());
        String original = a.groupKey();
        TabRegistry.Tab c = registry.add("https://three.test/", false);
        assertTrue(registry.createGroup(c, "Notes"));
        assertNotEquals(original, c.groupKey());
        TabRegistry.Tab secret = registry.add("https://private.test/", true);
        assertFalse(registry.assignGroup(secret, original));
        registry.close(b.id);
        assertTrue(registry.renameGroup(original, false, "Changed"));
        assertTrue(registry.reopenClosed(b, 1));
        assertEquals("Changed", registry.current().group);
        assertEquals(original, registry.current().groupKey());
        assertEquals("Notes", c.group);
        TabRegistry restored = new TabRegistry();
        TabRegistry.Tab restoredC = restored.restoreRegularTab(c.url, false, c.id, c.group, c.groupKey());
        assertEquals(c.groupKey(), restoredC.groupKey());
    }

    @Test public void pinsSortAheadAndReorderOnlyWithinTheVisibleBand() {
        TabRegistry registry = new TabRegistry();
        TabRegistry.Tab home = registry.selectStartupHome();
        TabRegistry.Tab a = registry.add("https://first.test/", false);
        TabRegistry.Tab p = registry.add("https://private.test/", true);
        TabRegistry.Tab b = registry.add("https://second.test/", false);
        TabRegistry.Tab c = registry.add("https://third.test/", false);
        assertFalse(registry.togglePin(home));
        assertTrue(registry.togglePin(b));
        assertTrue(registry.togglePin(c));
        assertEquals(java.util.List.of(home, b, c, a), registry.orderedOverview(false));
        assertTrue(registry.moveWithinBand(c, -1, false));
        assertEquals(java.util.List.of(home, c, b, a), registry.orderedOverview(false));
        assertSame(p, registry.all().get(2));
        assertSame(c, registry.current());
        assertFalse(registry.moveWithinBand(c, -1, false));
        registry.close(c.id);
        assertTrue(registry.reopenClosed(c, 3));
        assertTrue(registry.current().pinned);
    }
}
