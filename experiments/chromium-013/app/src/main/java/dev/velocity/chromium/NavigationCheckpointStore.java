package dev.velocity.chromium;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Makes the newest PageState available before its asynchronous disk write finishes. */
final class NavigationCheckpointStore {
    static final int MAX_BYTES = 256 * 1024;

    private static final class Pending {
        final byte[] bytes; // null explicitly invalidates an older disk checkpoint
        Pending(byte[] bytes) { this.bytes = bytes; }
        byte[] copy() { return bytes == null ? null : bytes.clone(); }
    }

    private final File directory;
    private final Executor io; // must execute in order, as TabPreviewStore's single worker does
    private final Map<String, Pending> pending = new HashMap<>();

    NavigationCheckpointStore(File directory, Executor io) {
        this.directory = directory;
        this.io = io;
    }

    private File file(String id) {
        if (id == null) return null;
        try { return new File(directory, UUID.fromString(id).toString() + ".jpg.history"); }
        catch (IllegalArgumentException error) { return null; }
    }

    synchronized void save(String id, byte[] bytes) {
        File file = file(id);
        if (file == null) return;
        Pending entry = new Pending(bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES
            ? null : bytes.clone());
        pending.put(id, entry);
        io.execute(() -> flush(id, file, entry));
    }

    synchronized byte[] load(String id) {
        Pending entry = pending.get(id);
        if (entry != null) return entry.copy();
        File file = file(id);
        if (file == null || !file.isFile() || file.length() > MAX_BYTES) return null;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return bytes.length == 0 || bytes.length > MAX_BYTES ? null : bytes;
        } catch (IOException error) {
            return null;
        }
    }

    private void flush(String id, File file, Pending entry) {
        synchronized (this) {
            if (pending.get(id) != entry) return; // a newer save/delete is queued
        }
        File temporary = new File(directory, file.getName() + ".tmp");
        boolean durable = false;
        try {
            if (entry.bytes == null) {
                Files.deleteIfExists(file.toPath());
            } else {
                Files.createDirectories(directory.toPath());
                Files.write(temporary.toPath(), entry.bytes);
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            durable = true;
        } catch (IOException error) {
            // Retain the latest in-memory result rather than exposing stale disk state.
        } finally {
            try { Files.deleteIfExists(temporary.toPath()); }
            catch (IOException ignored) { /* partial file is never loaded */ }
            if (durable) synchronized (this) {
                if (pending.get(id) == entry) pending.remove(id);
            }
        }
    }
}
