package dev.velocity.browser.tabs
import android.content.Context
import dev.velocity.browser.security.UrlPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

 data class StoredTabs(val tabs:List<TabRecord>,val selectedId:String?)
/** Immutable latest snapshot, debounced serialization and commits on one IO owner. */
class TabRepository(context:Context,preferencesName:String="normal_tabs") {
    private val preferences=context.getSharedPreferences(preferencesName,Context.MODE_PRIVATE)
    private val io=Executors.newSingleThreadScheduledExecutor()
    private val pending=AtomicReference<StoredTabs?>()
    private val scheduled=java.util.concurrent.atomic.AtomicBoolean(false)
    fun load(callback:(StoredTabs)->Unit)=io.execute {
        drain()
        val result=runCatching {
            val root=JSONObject(preferences.getString("metadata","{}")?:"{}")
            val array=root.optJSONArray("tabs")?:JSONArray();val ids=mutableSetOf<String>()
            // No arbitrary 1000-tab truncation; metadata still requires valid IDs and URLs.
            val tabs=(0 until array.length()).mapNotNull {n->
                val item=array.optJSONObject(n)?:return@mapNotNull null
                val id=item.optString("id");val raw=item.optString("url")
                val url=if(raw=="about:blank")raw else UrlPolicy.httpUrl(raw)?:return@mapNotNull null
                if(id.isBlank() || id.length>80 || !ids.add(id))null else TabRecord(id,url,readOnly=item.optBoolean("readOnly",false),title=item.optString("title").take(1024),pinned=item.optBoolean("pinned",false))
            };StoredTabs(tabs,root.optString("selected").takeIf {it in ids})
        }.getOrElse {StoredTabs(emptyList(),null)};callback(result)
    }
    fun save(tabs:List<TabRecord>,selectedId:String?) {
        val normal=tabs.filterNot {it.privateMode || it.extensionBase!=null}.map {TabRecord(it.id,it.url,readOnly=it.readOnly,title=it.title,pinned=it.pinned)}
        pending.set(StoredTabs(normal,selectedId?.takeIf {id->normal.any {it.id==id}}))
        if(scheduled.compareAndSet(false,true))io.schedule({scheduled.set(false);drain()},150,TimeUnit.MILLISECONDS)
    }
    fun flush() {io.execute {drain()} }
    private fun drain() {
        val data=pending.getAndSet(null)?:return
        val array=JSONArray();data.tabs.forEach {array.put(JSONObject().put("id",it.id).put("url",it.url).put("title",it.title).put("readOnly",it.readOnly).put("pinned",it.pinned))}
        val json=JSONObject().put("schema",2).put("tabs",array).put("selected",data.selectedId?:JSONObject.NULL).toString()
        preferences.edit().putString("metadata",json).commit()
    }
}
