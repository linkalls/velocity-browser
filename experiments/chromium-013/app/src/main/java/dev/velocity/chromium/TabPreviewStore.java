package dev.velocity.chromium;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Private, size-bounded tab thumbnails. Never persist private-browsing pixels.
 * Backups are disabled in the manifest; these JPEG files are app-private.
 * Work is serialized so tab-close deletion cannot race with a pending write.
 */
final class TabPreviewStore {
    private static final String TAG = "VelocityTabPreview";
    private final File directory;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Keep recent real thumbnails in memory so opening the tab sheet does not
    // race the asynchronous JPEG write. Cache only normal-tab images.
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };

    TabPreviewStore(Context context) {
        directory = new File(context.getFilesDir(), "tab_previews");
    }

    private File path(String id) {
        // No page-supplied URL or string can be used as a filename.
        try { return new File(directory, UUID.fromString(id).toString() + ".jpg"); }
        catch (IllegalArgumentException error) { return null; }
    }

    Bitmap load(String id) {
        Bitmap cached = memory.get(id);
        if (cached != null && !cached.isRecycled()) return cached;
        File file = path(id);
        if (file == null || !file.isFile()) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (decoded != null) memory.put(id, decoded);
        return decoded;
    }

    /** Remove only screenshot pixels, never the tab's back/forward PageState. */
    void invalidatePreview(String id) {
        memory.remove(id);
        File file = path(id);
        if (file != null) io.execute(file::delete);
    }

    void save(String id, Bitmap source) {
        if (source == null || source.getWidth() < 1 || source.getHeight() < 1) return;
        File file = path(id);
        if (file == null) return;
        // Cap both disk and live thumbnail memory, without keeping full-page screenshots.
        int width = 320;
        int height = Math.max(100, Math.min(450,
            (int) (source.getHeight() * (320.0 / source.getWidth()))));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        // Critical: the UI sheet can open before the background JPEG finishes.
        // Never recycle a bitmap still held by an ImageView / LruCache.
        memory.put(id, scaled);
        io.execute(() -> {
            if (!directory.exists() && !directory.mkdirs()) return;
            File temporary = new File(directory, file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 64, out);
                out.flush();
                if (!temporary.renameTo(file)) throw new IOException("Rename failed");
                Log.i(TAG, "Thumbnail JPEG persisted, bytes=" + file.length());
            } catch (IOException error) {
                Log.w(TAG, "Could not save tab thumbnail", error);
                temporary.delete();
            }
        });
    }

    /** Persist Chromium back/forward PageState separately from tab metadata. */
    void saveNavigationState(String id, byte[] state) {
        File preview = path(id);
        if (preview == null || state == null || state.length > 256 * 1024) return;
        File file = new File(directory, preview.getName() + ".history");
        byte[] snapshot = state.clone();
        io.execute(() -> {
            if (!directory.exists() && !directory.mkdirs()) return;
            File temporary = new File(directory, file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(snapshot);
                if (!temporary.renameTo(file)) throw new IOException("History rename failed");
            } catch (IOException error) {
                Log.w(TAG, "Could not save tab history", error);
                temporary.delete();
            }
        });
    }

    byte[] loadNavigationState(String id) {
        File preview = path(id);
        if (preview == null) return null;
        File file = new File(directory, preview.getName() + ".history");
        if (!file.isFile() || file.length() > 256 * 1024) return null;
        try { return Files.readAllBytes(file.toPath()); }
        catch (IOException error) { return null; }
    }

    void remove(String id) {
        memory.remove(id);
        File file = path(id);
        if (file != null) io.execute(() -> {
            file.delete();
            new File(directory, file.getName() + ".history").delete();
        });
    }

    void close() {
        memory.evictAll();
        io.shutdown();
    }
}
