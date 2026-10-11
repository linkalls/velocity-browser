package dev.velocity.browser.tabs
import android.content.Context
import dev.velocity.browser.security.UrlPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

data class StoredTabs(val tabs: List<TabRecord>, val selectedId: String?)
/** 永続化は単一I/Oキュー。完全SessionState、private情報を保存しない。 */
class TabRepository(context: Context, preferencesName: String = "normal_tabs") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    fun load(callback: (StoredTabs) -> Unit) = io.execute {
        val result = runCatching {
            val root = JSONObject(preferences.getString("metadata", "{}") ?: "{}")
            val array = root.optJSONArray("tabs") ?: JSONArray()
            val ids = mutableSetOf<String>()
            val tabs = (0 until minOf(array.length(), 1000)).mapNotNull { n ->
                val item = array.optJSONObject(n) ?: return@mapNotNull null
                val id = item.optString("id"); val raw = item.optString("url")
                val url = if (raw == "about:blank") raw else UrlPolicy.httpUrl(raw) ?: return@mapNotNull null
                if (id.isBlank() || id.length > 80 || !ids.add(id)) null else TabRecord(id, url, readOnly = item.optBoolean("readOnly", false), title = item.optString("title").take(1024))
            }
            StoredTabs(tabs, root.optString("selected").takeIf { it in ids })
        }.getOrElse { StoredTabs(emptyList(), null) }
        callback(result)
    }
    fun save(tabs: List<TabRecord>, selectedId: String?) {
        val normal = tabs.filterNot { it.privateMode || it.extensionBase != null }
        val items = JSONArray()
        normal.forEach { items.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("readOnly", it.readOnly)) }
        val json = JSONObject().put("schema", 1).put("tabs", items)
            .put("selected", selectedId?.takeIf { id -> normal.any { it.id == id } } ?: JSONObject.NULL).toString()
        io.execute { preferences.edit().putString("metadata", json).commit() }
    }
}
