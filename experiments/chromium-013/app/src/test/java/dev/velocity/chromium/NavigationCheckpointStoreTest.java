package dev.velocity.chromium;

import static org.junit.Assert.*;
import java.util.ArrayDeque;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class NavigationCheckpointStoreTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void latestSnapshotIsAvailableBeforeDiskAndAfterRestart() {
        var jobs = new ArrayDeque<Runnable>();
        var store = new NavigationCheckpointStore(temp.getRoot(), jobs::add);
        String id = UUID.randomUUID().toString();
        store.save(id, new byte[]{1});
        drain(jobs);
        byte[] state = {2};
        store.save(id, state);
        state[0] = 9;
        assertArrayEquals(new byte[]{2}, store.load(id));
        store.load(id)[0] = 9;
        assertArrayEquals(new byte[]{2}, store.load(id));
        store.save(id, new byte[]{3});
        drain(jobs);
        assertArrayEquals(new byte[]{3},
            new NavigationCheckpointStore(temp.getRoot(), jobs::add).load(id));
    }

    @Test public void invalidationNeverReturnsAnOlderSnapshot() {
        var jobs = new ArrayDeque<Runnable>();
        var store = new NavigationCheckpointStore(temp.getRoot(), jobs::add);
        String id = UUID.randomUUID().toString();
        store.save(id, new byte[]{1});
        drain(jobs);
        store.save(id, new byte[NavigationCheckpointStore.MAX_BYTES + 1]);
        assertNull(store.load(id));
        drain(jobs);
        assertNull(store.load(id));
        store.save(id, new byte[]{2});
        store.save(id, null);
        drain(jobs);
        assertNull(store.load(id));
    }

    private static void drain(ArrayDeque<Runnable> jobs) {
        while (!jobs.isEmpty()) jobs.remove().run();
    }
}
