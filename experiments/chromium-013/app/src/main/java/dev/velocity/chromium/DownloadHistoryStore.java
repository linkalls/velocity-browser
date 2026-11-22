package dev.velocity.chromium;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Serializes updates across overlapping Activity lifetimes. */
final class DownloadHistoryStore {
    private static final Object LOCK = new Object();
    private static final String KEY = "finished";
    private final SharedPreferences prefs;

    DownloadHistoryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(
            "velocity_downloads_v1", Context.MODE_PRIVATE);
    }

    List<DownloadHistory.Entry> read() {
        synchronized (LOCK) { return readLocked(); }
    }

    void record(DownloadHistory.Entry entry) {
        synchronized (LOCK) { writeLocked(DownloadHistory.insert(readLocked(), entry)); }
    }

    void forget(String uri) {
        synchronized (LOCK) { writeLocked(DownloadHistory.remove(readLocked(), uri)); }
    }

    private List<DownloadHistory.Entry> readLocked() {
        List<DownloadHistory.Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int index = 0; index < Math.min(array.length(), 30); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                try {
                    entries.add(new DownloadHistory.Entry(item.getString("uri"),
                        item.getString("name"), item.getString("mime"), item.getLong("bytes")));
                } catch (IllegalArgumentException | JSONException invalid) {
                    // A malformed saved row is never exposed as an open/share target.
                }
            }
        } catch (ClassCastException | JSONException invalid) {
            Log.w("VelocityDownloads", "Unreadable download history");
        }
        return entries;
    }

    private void writeLocked(List<DownloadHistory.Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (DownloadHistory.Entry entry : entries) {
                array.put(new JSONObject().put("uri", entry.contentUri()).put("name", entry.name())
                    .put("mime", entry.mime()).put("bytes", entry.bytes()));
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (JSONException impossible) {
            Log.w("VelocityDownloads", "Could not save download history");
        }
    }
}
