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
}
