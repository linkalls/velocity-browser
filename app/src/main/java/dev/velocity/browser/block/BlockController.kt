package dev.velocity.browser.block

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import dev.velocity.browser.R
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Runtime共有。拡張にセッション生成を委譲しないので2ライブ上限を変えない。 */
class BlockController(private val context: Context) {
    enum class Mode { ALL_OFF, ETP_ONLY, LITE, ADGUARD }
    companion object {
        const val BUNDLED_VERSION = "202610101515"
        const val ID = "velocity-block@velocity.browser"
        const val ADGUARD_ID = "adguardadblocker@adguard.com"
        const val XPI = "https://addons.mozilla.org/firefox/downloads/file/4986231/adguard_adblocker-5.5.2.3.xpi"
        const val SOURCE = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext"
    }
    var openExtensionPage: ((String, String, Boolean) -> GeckoSession?)? = null
    var onChanged: (() -> Unit)? = null
    var normalDecisions = 0; private set
    var privateDecisions = 0; private set
    var onUntrustedExtension: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("block_settings", Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(context.filesDir, "lite-rules.json"))
    private var extensionInventoryKnown = false
    fun readingAllowed() = extensionInventoryKnown && adguard == null
    private var runtime: GeckoRuntime? = null
    private var lite: WebExtension? = null
    private var adguard: WebExtension? = null
    private var port: WebExtension.Port? = null
    private var generation = 0
    private var dataReady = false
    var busy = false; private set
    var status = "NOT_INITIALIZED"; private set(value) { field = value; onChanged?.invoke() }
    var filterVersion = BUNDLED_VERSION; private set
    var updatedAt = 0L; private set
    var accepted = 0; private set
    var skipped = 0; private set
    var mode = runCatching { Mode.valueOf(prefs.getString("mode", Mode.ETP_ONLY.name)!!) }.getOrDefault(Mode.ETP_ONLY); private set
    private var base = ""
    private var user = prefs.getString("user", "").orEmpty()
    private var privateUser = ""
    private var privateEnabled = false
    private val exceptions = prefs.getStringSet("exceptions", emptySet())!!.toMutableSet()
    private val privateExceptions = mutableSetOf<String>()
    private var installActivity: Activity? = null
    private var consent: GeckoResult<WebExtension.PermissionPromptResponse>? = null
    private var consentDialog: AlertDialog? = null
    init {
        io.execute {
            val original = context.assets.open("velocity-block/rules.txt").bufferedReader().use { it.readText() }
            var rules = original + "\n" + context.assets.open("velocity-block/pgl.txt").bufferedReader().use { it.readText() }; var version = BUNDLED_VERSION; var stamp = 0L; var ignored = 0
            runCatching {
                val obj = cache.openRead().use { JSONObject(it.bufferedReader().readText()) }
                val parsed = LiteRules.parse(obj.getString("rules"))
                require(parsed.skipped == 0 && parsed.accepted > 0)
                require(obj.getString("source") == SOURCE && obj.getString("version").matches(Regex("\\d{12}")))
                require(obj.getString("version") >= BUNDLED_VERSION)
                rules = original + "\n" + parsed.text; version = obj.getString("version"); stamp = obj.getLong("updatedAt"); ignored = obj.getInt("skipped")
            }
            val parsed = LiteRules.parse(rules)
            main.post { base = parsed.text; accepted = parsed.accepted; skipped = ignored; filterVersion = version; updatedAt = stamp; dataReady = true; push() }
        }
    }
    fun attach(runtime: GeckoRuntime) {
        check(this.runtime == null)
        this.runtime = runtime
        val controller = runtime.webExtensionController
        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>): GeckoResult<WebExtension.PermissionPromptResponse> {
                val activity = installActivity
                if (extension.id != ADGUARD_ID || activity == null || activity.isFinishing || activity.isDestroyed)
                    return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
                val result = GeckoResult<WebExtension.PermissionPromptResponse>(); consent = result
                consentDialog = AlertDialog.Builder(activity).setTitle(R.string.adguard_permissions)
                    .setMessage(activity.getString(R.string.adguard_permission_explanation) + "\n" + (permissions + origins).joinToString("\n"))
                    .setPositiveButton(R.string.yes) { _, _ -> finishConsent(true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> finishConsent(false) }
                    .setOnCancelListener { finishConsent(false) }.show()
                return result
            }
        }) // 追加権限/データ収集/私的モード/自動更新の既定は拒否。
        controller.ensureBuiltIn("resource://android/assets/velocity-block/", ID).accept({ extension ->
            if (extension == null) { status = "INSTALL_FAILED"; return@accept }
            lite = extension; guardTabs(extension)
            extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onConnect(p: WebExtension.Port) {
                    if (p.name != "velocity_block_lite" || p.sender.webExtension.id != ID || p.sender.environmentType != WebExtension.MessageSender.ENV_TYPE_EXTENSION || p.sender.session != null) { p.disconnect(); return }
                    port?.disconnect(); port = p
                    p.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, p: WebExtension.Port) {
                            if (port !== p || message !is JSONObject || message.length() > 4) return
                            if (message.optString("type") == "stats" && message.optInt("generation", -1) == generation) {
                                val n = message.optInt("blocked", -1); val pCount = message.optInt("privateBlocked", -1)
                                if (n in 0..1_000_000 && pCount in 0..1_000_000) { normalDecisions = n; privateDecisions = pCount; onChanged?.invoke() }
                            } else if (message.optString("type") == "ready" && message.optInt("generation", -1) == generation && message.optInt("accepted", -1) in 0..LiteRules.MAX_RULES && message.optInt("skipped", -1) in 0..200_000) status = "READY_CONFIG_ACK (blocking UNVERIFIED)"
                            else if (message.optString("type") == "error" && message.optString("code") == "CONFIG_INVALID") status = "CONFIG_INVALID"
                        }
                        override fun onDisconnect(p: WebExtension.Port) { if (port === p) { port = null; status = "DISCONNECTED" } }
                    }); push()
                }
            }, "velocity_block_lite")
            controller.setAllowedInPrivateBrowsing(extension, true).accept({ updated ->
                lite = updated ?: extension
                prefs.edit().putString("builtInVersion", "0.2.0").apply()
                controller.list().accept({ list ->
                    extensionInventoryKnown = list != null
                    if (list?.any { it.id != ID } == true) onUntrustedExtension?.invoke()
                    adguard = list?.find { it.id == ADGUARD_ID }; adguard?.let { onUntrustedExtension?.invoke(); guardTabs(it) }
                    if (mode == Mode.ADGUARD && adguard == null) mode = Mode.ETP_ONLY
                    applyMode()
                }, { status = "LIST_FAILED"; mode = Mode.ETP_ONLY; applyMode() })
            }, { status = "PRIVATE_ENABLE_FAILED"; mode = Mode.ETP_ONLY; applyMode() })
        }, { status = "INSTALL_FAILED (page rendering continues)" })
        updateEtp()
    }
    private fun updateEtp() {
        runtime?.settings?.contentBlocking?.apply {
            setEnhancedTrackingProtectionLevel(if (mode == Mode.ALL_OFF) ContentBlocking.EtpLevel.NONE else ContentBlocking.EtpLevel.DEFAULT)
            setAntiTracking(if (mode == Mode.ALL_OFF) ContentBlocking.AntiTracking.NONE else ContentBlocking.AntiTracking.DEFAULT)
            // Safe Browsingの既定ONは変更しない。
        }
    }
    fun enabled(privateMode: Boolean) = if (privateMode) privateEnabled && mode != Mode.ADGUARD else mode == Mode.LITE
    fun setLite(enabled: Boolean, privateMode: Boolean) {
        if (busy) return
        if (privateMode) { if (mode == Mode.ADGUARD) return; privateEnabled = enabled; applyMode() }
        else setMode(if (enabled) Mode.LITE else Mode.ETP_ONLY)
    }
    fun setMode(next: Mode) {
        if (busy || (next == Mode.ADGUARD && adguard == null)) return
        mode = next
        if (next == Mode.ADGUARD) privateEnabled = false
        prefs.edit().putString("mode", next.name).apply(); applyMode()
    }
    private fun applyMode() {
        val r = runtime ?: return; val l = lite ?: return
        busy = true; status = "APPLYING"; updateEtp()
        // 二重フィルタリングを避け、まず両エンジンを停止してから一方だけ有効化。
        r.webExtensionController.disable(l, WebExtensionController.EnableSource.APP).accept({ disabled ->
            lite = disabled ?: l
            fun enableSelected() {
                val target = if (mode == Mode.ADGUARD) adguard else if (mode == Mode.LITE || privateEnabled) lite else null
                if (target == null) { busy = false; status = "DISABLED (ETP=${mode != Mode.ALL_OFF})"; return }
                r.webExtensionController.enable(target, WebExtensionController.EnableSource.APP).accept({ enabled ->
                    if (target.id == ID) lite = enabled ?: target else adguard = enabled ?: target
                    busy = false; status = if (target.id == ID) "WAITING_CONFIG_ACK" else "ADGUARD_ENABLED (compatibility UNVERIFIED)"; push()
                }, { failedMode() })
            }
            val a = adguard
            if (a == null) enableSelected() else r.webExtensionController.disable(a, WebExtensionController.EnableSource.APP).accept({ disabledAdguard -> adguard = disabledAdguard ?: a; enableSelected() }, { failedMode() })
        }, { failedMode() })
    }
    private fun failedMode() { busy = false; status = "MODE_FAILED (actual extension state UNKNOWN)"; mode = Mode.ETP_ONLY; updateEtp(); prefs.edit().putString("mode", mode.name).apply() }
    private fun push() {
        normalDecisions = 0; privateDecisions = 0
        if (!dataReady) return
        if (port != null) status = "WAITING_CONFIG_ACK"
        port?.postMessage(JSONObject().put("type", "config").put("generation", ++generation)
            .put("enabled", mode == Mode.LITE).put("privateEnabled", privateEnabled && mode != Mode.ADGUARD)
            .put("rules", base + "\n" + user).put("privateRules", base + "\n" + privateUser)
            .put("exceptions", JSONArray(exceptions.toList())).put("privateExceptions", JSONArray(privateExceptions.toList())))
    }
    fun isException(origin: String, privateMode: Boolean) = (if (privateMode) privateExceptions else exceptions).contains(origin)
    fun toggleException(origin: String, privateMode: Boolean) {
        require(LiteRules.origin(origin) == origin) { "canonical HTTP(S) origin required" }
        val list = if (privateMode) privateExceptions else exceptions
        if (!list.remove(origin)) { require(list.size < 1000); list += origin }
        if (!privateMode) prefs.edit().putStringSet("exceptions", exceptions.toSet()).apply()
        push()
    }
    fun userRules(privateMode: Boolean) = if (privateMode) privateUser else user
    fun setUserRules(text: String, privateMode: Boolean): LiteRules.Parsed {
        require(text.toByteArray().size <= 64 * 1024) { "ユーザー規則上限64KiB" }
        check(!busy) { "更新またはモード切替中" }
        val result = LiteRules.parse(text)
        require(LiteRules.parse(base + "\n" + result.text).accepted <= LiteRules.MAX_RULES)
        if (privateMode) privateUser = result.text else { user = result.text; prefs.edit().putString("user", user).apply() }
        push(); return result
    }
    fun resetPrivate() { privateUser = ""; privateExceptions.clear(); privateEnabled = false; push(); if (!busy) applyMode() }
    /** 明示操作でのみ開始。HTTP redirect禁止、2MiB上限、完全検査→AtomicFile置換。 */
    fun updateFilters(done: (Boolean) -> Unit) {
        if (busy) { done(false); return }; busy = true
        io.execute {
            val result = runCatching {
                val connection = URI(SOURCE).toURL().openConnection() as HttpsURLConnection
                connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 15000
                try {
                    require(connection.responseCode == 200)
                    require(connection.contentLengthLong <= LiteRules.MAX_BYTES)
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= LiteRules.MAX_BYTES); output.write(buffer, 0, n) }
                        output.toByteArray()
                    }; require(bytes.size <= LiteRules.MAX_BYTES)
                    val text = bytes.toString(Charsets.UTF_8); val version = LiteRules.remoteVersion(text)
                    require(!filterVersion.matches(Regex("\\d{12}")) || version >= filterVersion) { "version rollback" }
                    val parsed = LiteRules.parse(text); require(parsed.accepted > 0 && parsed.skipped < 200_000)
                    val original = context.assets.open("velocity-block/rules.txt").bufferedReader().use { it.readText() }
                    val combined = LiteRules.parse(original + "\n" + parsed.text)
                    LiteRules.parse(combined.text + "\n" + user)
                    LiteRules.parse(combined.text + "\n" + privateUser)
                    val stamp = System.currentTimeMillis()
                    val json = JSONObject().put("source", SOURCE).put("version", version).put("updatedAt", stamp).put("skipped", parsed.skipped).put("rules", parsed.text)
                        .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).toString()
                    val stream = cache.startWrite()
                    try { stream.write(json.toByteArray()); cache.finishWrite(stream) } catch (e: Exception) { cache.failWrite(stream); throw e }
                    Triple(combined, version, stamp) to parsed.skipped
                } finally { connection.disconnect() }
            }
            main.post { busy = false; result.onSuccess { (data, ignored) -> base = data.first.text; accepted = data.first.accepted; filterVersion = data.second; updatedAt = data.third; skipped = ignored; push() }; done(result.isSuccess) }
        }
    }
    fun installAdguard(activity: Activity) {
        if (busy || runtime == null || lite == null) return
        if (adguard != null) { setMode(Mode.ADGUARD); return }
        onUntrustedExtension?.invoke() // installがcontentへ触れる前に再表示証拠を永久失効。
        busy = true; installActivity = activity; status = "ADGUARD_INSTALLING"
        mode = Mode.ETP_ONLY; privateEnabled = false; updateEtp(); push()
        prefs.edit().putString("mode", mode.name).apply()
        // install()が拡張を有効化する前にLite停止を確定させる。
        runtime!!.webExtensionController.disable(lite!!, WebExtensionController.EnableSource.APP).accept({ disabledLite ->
            lite = disabledLite ?: lite
            if (installActivity !== activity || activity.isDestroyed || activity.isFinishing) { busy = false; status = "ADGUARD_CANCELLED"; return@accept }
            runtime!!.webExtensionController.install(XPI).accept({ extension ->
                installActivity = null; busy = false
                if (extension?.id != ADGUARD_ID) { status = "ADGUARD_ID_MISMATCH"; extension?.let { runtime!!.webExtensionController.uninstall(it) }; return@accept }
                adguard = extension; guardTabs(extension)
                runtime!!.webExtensionController.setAllowedInPrivateBrowsing(extension, false).accept({ updated -> adguard = updated ?: extension; setMode(Mode.ADGUARD) }, { status = "ADGUARD_PRIVATE_GUARD_FAILED" })
            }, { installActivity = null; busy = false; status = "ADGUARD_INSTALL_FAILED" })
        }, { installActivity = null; busy = false; status = "LITE_DISABLE_FAILED (AdGuard not installed)" })
    }
    private fun guardTabs(extension: WebExtension) {
        extension.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
                val url = createDetails.url ?: return null
                if (source.id != ADGUARD_ID || createDetails.cookieStoreId != null || !extensionPageAllowed(url, source.metaData.baseUrl)) return null
                val session = openExtensionPage?.invoke(url, source.metaData.baseUrl, true) ?: return null
                return GeckoResult.fromValue(session)
            }
            override fun onOpenOptionsPage(source: WebExtension) { if (source.id == ADGUARD_ID) openAdguardOptions() }
        })
        // SessionTabDelegate未設定: stable API既定でtabs.update/removeを拒否。
    }
    fun extensionPageAllowed(url: String, base: String) = adguard?.metaData?.baseUrl == base && dev.velocity.browser.security.UrlPolicy.extensionPage(url, base)
    fun openAdguardOptions() {
        val a = adguard ?: return
        val page = a.metaData.optionsPageUrl ?: return
        val url = runCatching { URI(a.metaData.baseUrl).resolve(page).toString() }.getOrNull() ?: return
        if (extensionPageAllowed(url, a.metaData.baseUrl)) openExtensionPage?.invoke(url, a.metaData.baseUrl, false)
    }
    fun hasAdguard() = adguard != null
    fun removeAdguard() {
        if (busy) return
        val a = adguard ?: return
        busy = true; mode = Mode.ETP_ONLY; privateEnabled = false; updateEtp()
        prefs.edit().putString("mode", mode.name).apply()
        runtime?.webExtensionController?.disable(a, WebExtensionController.EnableSource.APP)?.accept({ disabled ->
            runtime!!.webExtensionController.uninstall(disabled ?: a).accept({ adguard = null; busy = false; applyMode() }, { busy = false; status = "ADGUARD_REMOVE_FAILED" })
        }, { busy = false; status = "ADGUARD_DISABLE_FAILED" })
    }
    private fun finishConsent(allow: Boolean) { val r = consent; consent = null; r?.complete(WebExtension.PermissionPromptResponse(allow, false, false)); consentDialog = null }
    fun activityStopped(activity: Activity) { if (installActivity === activity) { val dialog = consentDialog; finishConsent(false); dialog?.dismiss(); installActivity = null } }
}
