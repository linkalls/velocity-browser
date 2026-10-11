package dev.velocity.browser.tabs

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.*
import dev.velocity.browser.R
import dev.velocity.browser.engine.*
import dev.velocity.browser.preload.*
import dev.velocity.browser.security.*
import dev.velocity.browser.util.*
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.util.UUID

interface BrowserHost {
    val activity: Activity
    fun render(tabs: List<TabRecord>, selectedId: String?, canBack: Boolean, canForward: Boolean, loading: Boolean)
    fun notice(message: Int)
    fun confirmClose(id: String)
    fun requestAndroidPermissions(permissions: Array<String>, done: (Boolean) -> Unit)
}

class AdaptiveTabCoordinator(private val context: Context) {
    private val clock = Clock { SystemClock.elapsedRealtime() }
    val scheduler = AdaptiveTabScheduler(AdaptiveConfig(), clock)
    val metrics = Metrics(clock)
    private val preconnect = PreconnectPolicy(scheduler.config, clock)
    private val handler = Handler(Looper.getMainLooper())
    private val repository = TabRepository(context)
    private val adapters = mutableMapOf<String, GeckoSessionAdapter>()
    private val snapshots = mutableMapOf<String, GeckoSession.SessionState>()
    private val reading = dev.velocity.browser.reading.ReadingLoader()
    private val documents = mutableMapOf<String, dev.velocity.browser.reading.ReadingDocument>()
    private var deferredExtensionLoadId: String? = null
    private var untrustedExtensionSeen = false
    private val queue = ArrayDeque<Event>()
    private var draining = false
    private var view: GeckoView? = null
    var host: BrowserHost? = null; private set
    val permissions = PermissionController(this)
    val prompts = PromptController(this)
    private var started = false
    private var lowMemory = false
    private var runtimeRecorded = false
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val allowedOrigins = settings.getStringSet("preconnect_origins", emptySet())!!.toMutableSet()
    var searchEngine: SearchEngine
        get() = SearchEngine.entries.getOrElse(settings.getInt("search_engine", 0)) { SearchEngine.DUCKDUCKGO }
        set(value) { settings.edit().putInt("search_engine", value.ordinal).apply() }
    private val scan = object : Runnable {
        override fun run() {
            if (!scheduler.foreground) return
            dispatch(Event.Tick(clock.elapsedRealtimeMs()))
            handler.postDelayed(this, scheduler.config.backgroundScanIntervalMs)
        }
    }
    init {
        preconnect.enabled = settings.getBoolean("preconnect", true)
        scheduler.adaptiveEnabled = settings.getBoolean("adaptive", true)
    }
    fun bind(host: BrowserHost, view: GeckoView) {
        this.host = host; this.view = view
        view.postOnAnimation { handler.post { if (this.host === host) metrics.record("UI_READY") } }
        if (!started) {
            started = true
            repository.load { stored -> handler.post {
                stored.tabs.forEach { t -> if (scheduler.tab(t.id) == null) dispatch(Event.CreateTab(t.id, t.url, readOnly = t.readOnly)) }
                if (scheduler.tabs.isEmpty()) createTab("about:blank")
                else if (scheduler.selectedId == null) dispatch(Event.SelectTab(stored.selectedId ?: scheduler.tabs.first().id))
                render()
            } }
        }
        scheduler.selectedId?.let { id -> adapters[id]?.let { attach(it) } }
        render()
    }
    fun unbind(host: BrowserHost) {
        if (this.host !== host) return
        permissions.cancelAll(); prompts.cancelAll()
        view?.releaseSession(); view = null; this.host = null
    }
    fun isCurrent(id: String, identity: Long) = scheduler.tab(id)?.identity == identity
    fun dispatch(event: Event) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val hadPrivate = scheduler.tabs.any { it.privateMode }
        queue.addLast(event)
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) {
                val commands = scheduler.dispatch(queue.removeFirst())
                commands.forEach { execute(it) }
                check(adapters.size <= scheduler.config.maxLiveTabs)
            }
        } finally { draining = false }
        // COLDタブの手動closeにはCloseSession commandが出ない。残るstate参照も解放。
        documents.keys.removeAll { scheduler.tab(it) == null }
        if (snapshots.isNotEmpty()) snapshots.keys.removeAll { scheduler.tab(it) == null }
        if (hadPrivate && scheduler.tabs.none { it.privateMode }) (context.applicationContext as dev.velocity.browser.VelocityApp).blocker.resetPrivate()
        metrics.record("LIVE_COUNT", scheduler.liveCount.toLong()); render()
    }
    private fun execute(c: Command) {
        when (c) {
            is Command.CreateSession -> {
                try {
                    val adapter = GeckoSessionAdapter(c.id, c.identity, c.privateMode, this, c.readOnly)
                    adapters[c.id] = adapter
                    android.os.Trace.beginSection("VelocityRuntime")
                    try { adapter.session.open(GeckoRuntimeHolder.get(context)) } finally { android.os.Trace.endSection() }
                    if (!runtimeRecorded) { runtimeRecorded = true; metrics.record("RUNTIME_CREATED") }
                    val state = c.snapshot?.let { snapshots[it] }
                    if (c.id == deferredExtensionLoadId) { /* GeckoがTabDelegate返却後にロードする */ }
                    else if (c.readOnly) {
                        val document = documents[c.id]
                        if (document != null) adapter.loadReading(document)
                        else reading.fetch(GeckoRuntimeHolder.get(context), c.url) { loaded ->
                            if (isCurrent(c.id, c.identity)) {
                                if (loaded != null && storeDocument(c.id, loaded)) adapter.loadReading(loaded)
                                else notice(R.string.reading_failed)
                            }
                        }
                    } else if (state == null) adapter.load(c.url) else try { adapter.session.restoreState(state) } catch (_: Exception) { dispatch(Event.RestoreFailed(c.id, c.identity)) }
                    dispatch(Event.Ready(c.id, c.identity))
                } catch (_: Exception) { dispatch(Event.Crashed(c.id, c.identity)); notice(R.string.crash) }
            }
            is Command.AttachSessionToView -> adapters[c.id]?.let { attach(it) }
            is Command.DetachSessionFromView -> view?.let { v -> if (v.session == adapters[c.id]?.session) v.releaseSession() }
            is Command.SetSessionActive -> adapters[c.id]?.session?.takeIf { it.isOpen }?.let { it.setFocused(c.active && host != null); it.setActive(c.active); GeckoRuntimeHolder.get(context).webExtensionController.setTabActive(it, c.active) }
            is Command.RequestSessionStateFlush -> adapters[c.id]?.takeIf { it.identity == c.identity }?.let { adapter ->
                handler.postDelayed({ dispatch(Event.SaveTimedOut(c.id, c.identity, c.generation)) }, 5000)
                adapter.session.flushSessionState() // void、非同期。直後の保存完了判定は禁止。
                if (!untrustedExtensionSeen && (context.applicationContext as dev.velocity.browser.VelocityApp).blocker.readingAllowed() && adapter.pristineBlank && scheduler.tab(c.id)?.url == "about:blank") {
                    dispatch(Event.RiskChanged(c.id, c.identity, Protection.UNKNOWN, false))
                    handler.post {
                        val t = scheduler.tab(c.id)
                        if (!untrustedExtensionSeen && (context.applicationContext as dev.velocity.browser.VelocityApp).blocker.readingAllowed() && t != null && isCurrent(c.id, c.identity) && adapter.pristineBlank && t.url == "about:blank")
                            dispatch(Event.SafeCheckpoint(t.id, c.identity, t.epoch, t.revision, t.saveGeneration))
                    }
                }
            }
            is Command.CloseSession -> adapters[c.id]?.takeIf { it.identity == c.identity }?.let {
                permissions.cancelFor(it); prompts.cancelFor(it)
                it.close(); adapters.remove(c.id)
                // 自動休眠は安全な空ページstateを保持。手動close/失敗は参照を解放。
                if (scheduler.tab(c.id)?.state != TabState.COLD) snapshots.remove(c.id)
            }
            Command.PersistTabMetadata -> repository.save(scheduler.tabs, scheduler.selectedId)
            is Command.ShowEvictionBlockedNotice -> { metrics.record("BLOCKED"); notice(R.string.blocked) }
            is Command.ConfirmClose -> host?.confirmClose(c.id)
            is Command.FallbackReload -> adapters[c.id]?.load(c.url)
        }
    }
    private fun attach(adapter: GeckoSessionAdapter) {
        view?.let { if (adapter.session.isOpen && it.session != adapter.session) { it.releaseSession(); it.setSession(adapter.session) } }
    }
    fun acceptBlankSnapshot(id: String, identity: Long, state: GeckoSession.SessionState) {
        if (untrustedExtensionSeen || !isCurrent(id, identity) || !(context.applicationContext as dev.velocity.browser.VelocityApp).blocker.readingAllowed()) return
        val t = scheduler.tab(id) ?: return
        snapshots[id] = state
        dispatch(Event.SessionStateChanged(id, identity, t.epoch, id))
    }
    fun extensionPageAllowed(id: String, url: String): Boolean {
        val base = scheduler.tab(id)?.extensionBase ?: return false
        return (context.applicationContext as dev.velocity.browser.VelocityApp).blocker.extensionPageAllowed(url, base)
    }
    fun createExtensionTab(url: String, base: String, delegatedLoad: Boolean): GeckoSession? {
        if (!(context.applicationContext as dev.velocity.browser.VelocityApp).blocker.extensionPageAllowed(url, base)) return null
        if (!delegatedLoad) scheduler.tabs.firstOrNull { it.extensionBase == base && it.url == url }?.let { existing ->
            select(existing.id); return adapters[existing.id]?.session
        }
        val id = UUID.randomUUID().toString()
        if (delegatedLoad) deferredExtensionLoadId = id
        try {
            dispatch(Event.CreateTab(id, url, extensionBase = base)); select(id)
        } finally { deferredExtensionLoadId = null }
        return adapters[id]?.session
    }
    fun untrustedExtensionInstalled() {
        untrustedExtensionSeen = true
        scheduler.tabs.filter { it.live }.forEach { t ->
            dispatch(Event.RiskChanged(t.id, t.identity!!, Protection.PINNED, true))
        }
    }
    fun readingLoaded(id: String, identity: Long) {
        val t = scheduler.tab(id) ?: return
        if (isCurrent(id, identity) && documents.containsKey(id) && !untrustedExtensionSeen)
            dispatch(Event.ReplayReady(id, identity, t.epoch, id))
    }
    private fun storeDocument(id: String, doc: dev.velocity.browser.reading.ReadingDocument): Boolean {
        if (untrustedExtensionSeen || (context.applicationContext as dev.velocity.browser.VelocityApp).blocker.readingAllowed().not()) return false
        if (documents.values.sumOf { it.bytes } + doc.bytes > dev.velocity.browser.reading.ReadingDocument.MAX_CACHE_BYTES) return false
        documents[id] = doc; return true
    }
    fun createReadingTab(url: String) {
        if (UrlPolicy.httpUrl(url) == null || untrustedExtensionSeen || scheduler.selectedId?.let { scheduler.tab(it)?.privateMode } == true) { notice(R.string.reading_failed); return }
        reading.fetch(GeckoRuntimeHolder.get(context), url) { doc ->
            val id = UUID.randomUUID().toString()
            if (doc == null || !storeDocument(id, doc)) { notice(R.string.reading_failed); return@fetch }
            dispatch(Event.CreateTab(id, url, readOnly = true)); select(id)
        }
    }
    fun createTab(url: String = "about:blank", privateMode: Boolean = false) {
        if (url != "about:blank" && UrlPolicy.httpUrl(url) == null) { notice(R.string.invalid_url); return }
        val id = UUID.randomUUID().toString()
        dispatch(Event.CreateTab(id, url, privateMode)); select(id)
    }
    fun select(id: String) { metrics.record("SELECT"); dispatch(Event.SelectTab(id)) }
    fun close(id: String, confirmed: Boolean = false) = dispatch(Event.CloseTab(id, confirmed))
    fun navigate(input: String) {
        val url = UrlPolicy.input(input, searchEngine) ?: return notice(R.string.invalid_url)
        val id = scheduler.selectedId ?: return createTab(url)
        if (scheduler.tab(id)?.readOnly == true) { createReadingTab(url); return }
        val adapter = adapters[id] ?: return
        val current = scheduler.tab(id) ?: return
        maybePreconnect(current, url)
        // loadUriの非同期callback前に空タブの保存証拠を失効させる。
        dispatch(Event.NavigationStarted(id, adapter.identity, url))
        adapter.load(url)
    }
    fun back() { selectedAdapter()?.session?.goBack() }
    fun forward() { selectedAdapter()?.session?.goForward() }
    fun reload() {
        selectedAdapter()?.let { adapter ->
            val tab = scheduler.tab(adapter.id) ?: return
            if (tab.readOnly) {
                val doc = documents[tab.id]
                if (doc != null) adapter.loadReading(doc)
                else reading.fetch(GeckoRuntimeHolder.get(context), tab.url) { loaded ->
                    if (isCurrent(tab.id, adapter.identity)) {
                        if (loaded != null && storeDocument(tab.id, loaded)) adapter.loadReading(loaded)
                        else notice(R.string.reading_failed)
                    }
                }
            } else if (adapter.loading) adapter.session.stop() else adapter.session.reload()
        }
    }
    private fun selectedAdapter() = scheduler.selectedId?.let { adapters[it] }
    fun pin(id: String) {
        val t = scheduler.tab(id) ?: return
        t.identity?.let { dispatch(Event.RiskChanged(id, it, Protection.PINNED, Protection.PINNED !in t.protections)) }
    }
    fun foreground(active: Boolean) {
        handler.removeCallbacks(scan)
        dispatch(Event.AppForegroundChanged(active))
        if (active) { lowMemory = false; handler.postDelayed(scan, scheduler.config.backgroundScanIntervalMs) }
    }
    fun memoryPressure() { lowMemory = true; dispatch(Event.MemoryPressure()) }
    fun setPreconnect(enabled: Boolean) { preconnect.enabled = enabled; settings.edit().putBoolean("preconnect", enabled).apply() }
    fun preconnectEnabled() = preconnect.enabled
    fun setAdaptive(enabled: Boolean) { scheduler.adaptiveEnabled = enabled; settings.edit().putBoolean("adaptive", enabled).apply() }
    fun allowCurrentOrigin() {
        val t = scheduler.selectedId?.let { scheduler.tab(it) } ?: return
        if (t.privateMode) return
        UrlPolicy.origin(t.url)?.let { allowedOrigins += it; settings.edit().putStringSet("preconnect_origins", allowedOrigins.toSet()).apply() }
    }
    private fun maybePreconnect(t: TabRecord, next: String) {
        val network = context.getSystemService(ConnectivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val memory = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
        val conditions = PreconnectConditions(scheduler.foreground && network.activeNetwork != null, t.privateMode, network.isActiveNetworkMetered,
            network.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
            power.isPowerSaveMode, lowMemory || memory.lowMemory, UrlPolicy.origin(t.url) in allowedOrigins)
        val origin = preconnect.candidate(t.url, next, conditions) ?: return
        try { GeckoWebExecutor(GeckoRuntimeHolder.get(context)).speculativeConnect(origin); metrics.record("PRECONNECT_ISSUED") }
        finally { handler.postDelayed({ preconnect.releaseIssueGuard() }, scheduler.config.preconnectCooldownMs) }
    }
    fun firstContent(id: String) {
        if (id == scheduler.selectedId) {
            metrics.record("FIRST_CONTENT")
            val t = scheduler.tab(id)
            if (t != null && t.url != "about:blank") {
                host?.activity?.reportFullyDrawn()
                maybePreconnect(t, t.url)
            }
        }
    }
    fun render() { val a = selectedAdapter(); host?.render(scheduler.tabs, scheduler.selectedId, a?.canGoBack == true, a?.canGoForward == true, a?.loading == true) }
    fun notice(message: Int) { host?.notice(message) }
    fun exportMetrics() {
        val array = JSONArray()
        metrics.snapshot().forEach { array.put(JSONObject().put("name", it.name).put("elapsedMs", it.elapsedMs).put("value", it.value)) }
        context.openFileOutput("metrics.json", Context.MODE_PRIVATE).use { it.write(JSONObject().put("schema", 1).put("events", array).toString().toByteArray()) }
        notice(R.string.metrics_saved)
    }
}
