package dev.velocity.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import dev.velocity.browser.security.*
import dev.velocity.browser.tabs.*
import dev.velocity.browser.ui.*

class MainActivity : Activity(), BrowserHost {
    override val activity: Activity get() = this
    private val blocker get() = (application as VelocityApp).blocker
    private val coordinator get() = (application as VelocityApp).coordinator
    private val appearance get() = (application as VelocityApp).appearance
    private var tabSheet: TabSwitcherView? = null
    private var capacity: CapacityDialog? = null
    private val appearanceChanged: () -> Unit = { applyAppearance() }
    private fun dialog() = appearance.builder(this)
    private fun AlertDialog.Builder.showThemed() = appearance.show(this)
    private fun applyAppearance() {
        setTheme(appearance.style()); appearance.applyWindow(this)
        if (::chrome.isInitialized) chrome.applyPalette(appearance.palette())
        tabSheet?.applyPalette(); capacity?.update(coordinator.scheduler.tabs); coordinator.downloads.refresh()
    }
    private lateinit var chrome: BrowserChromeView
    private var permissionResult: ((Boolean) -> Unit)? = null
    private var foreground = false
    private var backRegistered = false
    private val modernBack by lazy { android.window.OnBackInvokedCallback { coordinator.back() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(appearance.style())
        super.onCreate(savedInstanceState)
        chrome = BrowserChromeView(this)
        setContentView(chrome)
        chrome.onNavigate = coordinator::navigate
        chrome.back.setOnClickListener { coordinator.back() }
        chrome.forward.setOnClickListener { coordinator.forward() }
        chrome.reload.setOnClickListener { coordinator.reload() }
        chrome.onTabs = {
            tabSheet?.dismiss()
            tabSheet = TabSwitcherView(this, appearance, { id ->
                coordinator.select(id); coordinator.scheduler.selectedId == id && coordinator.scheduler.pendingId == null
            }, { privateMode ->
                val before = coordinator.scheduler.selectedId
                coordinator.createTab(privateMode = privateMode)
                coordinator.scheduler.selectedId != before && coordinator.scheduler.pendingId == null
            }, { coordinator.close(it) }, coordinator::cancelPending, ::readingDialog).also {
                it.update(coordinator.scheduler.tabs,coordinator.scheduler.selectedId,coordinator.scheduler.pendingId); it.show()
            }
        }
        chrome.onReading = ::readingDialog
        chrome.onSettings = { settings() }
        chrome.onBlock = { blockSettings() }
        appearance.observe(appearanceChanged); applyAppearance()
        coordinator.bind(this, chrome.geckoView)
        if (savedInstanceState == null) openIntent(intent)
    }
    @Deprecated("Legacy Activity result bridge for GeckoRuntime and the system SAF picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        if (!coordinator.externalActivities.result(requestCode,resultCode,data)) { @Suppress("DEPRECATION") super.onActivityResult(requestCode,resultCode,data) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openIntent(intent) }
    private fun openIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val url = intent.dataString?.let { UrlPolicy.httpUrl(it) }
        if (url == null) notice(R.string.invalid_url) else coordinator.createTab(url)
    }
    override fun onStart() { super.onStart(); foreground = true; coordinator.foreground(true) }
    override fun onStop() {
        blocker.activityStopped(this)
        foreground = false; permissionResult?.invoke(false); permissionResult = null
        if (coordinator.host === this) {
            coordinator.permissions.cancelAll(); coordinator.prompts.cancelAll(); coordinator.foreground(false)
        }
        super.onStop()
    }
    override fun onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && backRegistered) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(modernBack)
        appearance.remove(appearanceChanged); tabSheet?.dismiss(); capacity?.dismiss(); appearance.dismissFor(this)
        if (isFinishing && coordinator.host === this) coordinator.externalActivities.cancelAll()
        coordinator.unbind(this); super.onDestroy()
    }
    override fun render(tabs: List<TabRecord>, selectedId: String?, canBack: Boolean, canForward: Boolean, loading: Boolean) {
        tabSheet?.takeIf { it.isShowing }?.update(tabs,selectedId,coordinator.scheduler.pendingId)
        val waiting = coordinator.scheduler.pendingId
        if (capacity != null && capacity?.targetId != waiting) { capacity?.dismiss(); capacity = null }
        if (waiting != null && !coordinator.delegatedRequestInProgress) {
            if (capacity == null && tabSheet?.isShowing != true) capacityBlocked(waiting,coordinator.isOptionsPending(waiting))
            else capacity?.update(tabs)
        }
        val t = tabs.find { it.id == selectedId }
        chrome.showStart(t == null || t.url == "about:blank")
        if (tabs.any { it.privateMode }) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        chrome.title.text = (if (t?.readOnly == true) getString(R.string.reading_label) else "") + (if (t?.privateMode == true) getString(R.string.private_label) else "") + (t?.title ?: getString(R.string.app_name))
        if (!chrome.address.hasFocus()) chrome.address.setText(t?.url?.takeUnless { it == "about:blank" }.orEmpty())
        chrome.updateState(tabs.size, t?.privateMode == true, blocker.mode.name, blocker.enabled(t?.privateMode == true), blocker.busy, if (t?.privateMode == true) blocker.privateDecisions else blocker.normalDecisions, blocker.status)
        chrome.back.isEnabled = canBack; chrome.forward.isEnabled = canForward
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (canBack && !backRegistered) { onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, modernBack); backRegistered = true }
            else if (!canBack && backRegistered) { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(modernBack); backRegistered = false }
        }
        chrome.reload.setText(if (loading) R.string.stop else R.string.reload)
    }
    override fun notice(message: Int) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun capacityBlocked(id: String, adguard: Boolean) {
        if (!adguard && tabSheet?.isShowing == true) return
        if (capacity?.targetId == id) return
        capacity?.dismiss()
        capacity = CapacityDialog(this,id,adguard,appearance,{ coordinator.close(it) },{ target ->
            if (coordinator.host === this) coordinator.cancelPending(target)
        }, { capacity = null; chrome.onTabs() }).also { it.update(coordinator.scheduler.tabs); it.show() }
    }
    override fun confirmClose(id: String) {
        val tab = coordinator.scheduler.tab(id) ?: return
        val identity = tab.identity
        val pending = capacity?.targetId
        fun cancelRequest() { if (coordinator.host === this && pending != null) coordinator.cancelPending(pending) }
        val builder = dialog().setTitle("『${tab.displayTitle()}』を閉じる")
            .setMessage("${tab.displayLocation()}\n" + getString(R.string.close_warning))
            .setPositiveButton("このタブを閉じる") { _, _ ->
                if (coordinator.host === this && coordinator.scheduler.tab(id)?.identity == identity) coordinator.close(id, true)
            }.setNegativeButton(R.string.cancel) { _, _ -> cancelRequest() }
            .setOnCancelListener { cancelRequest() }
        appearance.show(builder,true)
    }
    override fun requestAndroidPermissions(permissions: Array<String>, done: (Boolean) -> Unit) {
        if (!foreground || permissionResult != null) { done(false); return }
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { done(true); return }
        permissionResult = done
        requestPermissions(permissions, 410)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 410) {
            val callback = permissionResult; permissionResult = null
            callback?.invoke(foreground && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
        }
    }
    @android.annotation.SuppressLint("GestureBackNavigation") // API26〜32のfallback。33以降は上記dispatcher。
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (chrome.back.isEnabled) coordinator.back() else super.onBackPressed()
    }
    private fun settings() {
        val labels = arrayOf(getString(R.string.search_engine), getString(R.string.preconnect) + " [${coordinator.preconnectEnabled()}]",
            getString(R.string.allow_origin), getString(R.string.pin), getString(R.string.adaptive) + " [${coordinator.scheduler.adaptiveEnabled}]", getString(R.string.export_metrics), getString(R.string.reading_new), getString(R.string.reading_original), "ライセンス", getString(R.string.appearance), getString(R.string.downloads), getString(R.string.passkey_diagnostics), getString(R.string.data_protection))
        dialog().setTitle(R.string.settings).setItems(labels) { _, which ->
            when (which) {
                0 -> dialog().setTitle(R.string.search_engine).setSingleChoiceItems(SearchEngine.entries.map { it.name }.toTypedArray(), coordinator.searchEngine.ordinal) { dialog, n -> coordinator.searchEngine = SearchEngine.entries[n]; dialog.dismiss() }.showThemed()
                1 -> coordinator.setPreconnect(!coordinator.preconnectEnabled())
                2 -> dialog().setMessage(R.string.allow_origin).setPositiveButton(R.string.yes) { _, _ -> coordinator.allowCurrentOrigin() }.setNegativeButton(R.string.cancel, null).showThemed()
                3 -> coordinator.scheduler.selectedId?.let { coordinator.pin(it) }
                4 -> coordinator.setAdaptive(!coordinator.scheduler.adaptiveEnabled)
                5 -> coordinator.exportMetrics()
                6 -> readingDialog()
                7 -> coordinator.scheduler.selectedId?.let { coordinator.scheduler.tab(it) }?.takeIf { it.readOnly }?.let { coordinator.createTab(it.url) }
                10 -> coordinator.downloads.show()
                11 -> dialog().setTitle(R.string.passkey_diagnostics).setMessage(getString(R.string.passkey_diagnostic_text,android.os.Build.VERSION.SDK_INT,
                    packageManager.hasSystemFeature("android.software.credentials"),coordinator.runtimeActivities.requested,coordinator.runtimeActivities.returned,coordinator.runtimeActivities.canceled) + "\nOrigin権限: " + (checkSelfPermission("android.permission.CREDENTIAL_MANAGER_SET_ORIGIN")==PackageManager.PERMISSION_GRANTED) +
                    "\nGMS: " + runCatching { @Suppress("DEPRECATION") packageManager.getPackageInfo("com.google.android.gms",0).versionName }.getOrDefault("なし") ).setPositiveButton(R.string.close_confirm,null).showThemed()
                12 -> dialog().setTitle(R.string.data_protection).setMessage(R.string.restore_limits).setPositiveButton(R.string.close_confirm,null).showThemed()
                9 -> dialog().setTitle(R.string.appearance).setSingleChoiceItems(arrayOf("システムに合わせる", "ライト", "ダーク"),appearance.mode.ordinal) { d,n -> appearance.select(ThemeController.Mode.entries[n]); d.dismiss() }.showThemed()
                8 -> dialog().setTitle("ライセンス").setMessage(assets.open("licenses/PETER_LOWES_LIST.txt").bufferedReader().use { it.readText() } + "\n" + assets.open("licenses/JSOUP.txt").bufferedReader().use { it.readText() }).setPositiveButton(R.string.close_confirm, null).showThemed()
            }
        }.showThemed()
    }
    private fun readingDialog() {
        val input = android.widget.EditText(appearance.dialogContext(this)).apply { isSaveEnabled = false; hint = "https://example.com/" }
        dialog().setTitle(R.string.reading_new).setMessage(R.string.reading_consent).setView(input)
            .setPositiveButton(R.string.go) { _, _ -> UrlPolicy.input(input.text.toString(), coordinator.searchEngine)?.let(coordinator::createReadingTab) }
            .setNegativeButton(R.string.cancel, null).showThemed()
    }
    private fun blockSettings() {
        val tab = coordinator.scheduler.tabs.find { it.id == coordinator.scheduler.selectedId }
        val privateMode = tab?.privateMode == true
        val origin = tab?.url?.let { dev.velocity.browser.block.LiteRules.origin(it) }
        val items = arrayOf(getString(R.string.block_toggle) + " [${blocker.enabled(privateMode)}]",
            getString(R.string.block_exception) + " [${origin?.let { blocker.isException(it, privateMode) } ?: false}]",
            getString(R.string.block_update), getString(R.string.block_user_rules), getString(R.string.block_diagnostics),
            getString(R.string.block_modes), getString(R.string.adguard_install), getString(R.string.adguard_remove), getString(R.string.adguard_options))
        dialog().setTitle(R.string.block_settings).setItems(items) { _, index ->
            if (blocker.busy && index != 4) { notice(R.string.block_busy); return@setItems }
            when (index) {
                0 -> { blocker.setLite(!blocker.enabled(privateMode), privateMode); notice(R.string.block_reload_hint) }
                1 -> if (origin != null) dialog().setMessage(getString(R.string.block_exception_scope, origin))
                    .setPositiveButton(R.string.yes) { _, _ -> runCatching { blocker.toggleException(origin, privateMode) }.onFailure { notice(R.string.block_failed) } }.setNegativeButton(R.string.cancel, null).showThemed()
                2 -> if (!privateMode) dialog().setMessage(R.string.block_update_consent)
                    .setPositiveButton(R.string.yes) { _, _ -> blocker.updateFilters { notice(if (it) R.string.block_updated else R.string.block_failed) } }.setNegativeButton(R.string.cancel, null).showThemed()
                3 -> {
                    val input = android.widget.EditText(appearance.dialogContext(this)).apply { setText(blocker.userRules(privateMode)); isSaveEnabled = false; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; filters = arrayOf(android.text.InputFilter.LengthFilter(65536)) }
                    dialog().setTitle(R.string.block_user_rules).setMessage(R.string.block_rules_help).setView(input)
                        .setPositiveButton(R.string.go) { _, _ -> runCatching { blocker.setUserRules(input.text.toString(), privateMode) }.onSuccess { result -> android.widget.Toast.makeText(this, getString(R.string.block_rules_result, result.accepted, result.skipped), android.widget.Toast.LENGTH_LONG).show() }.onFailure { notice(R.string.block_failed) } }.setNegativeButton(R.string.cancel, null).showThemed()
                }
                4 -> dialog().setTitle(R.string.block_diagnostics).setMessage(getString(R.string.block_diagnostic_text, blocker.status + " / 遮断判定: " + (if (privateMode) blocker.privateDecisions else blocker.normalDecisions), blocker.filterVersion, blocker.accepted, blocker.skipped,
                    if (blocker.updatedAt == 0L) getString(R.string.block_never_updated) else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(blocker.updatedAt))) + "\n" + dev.velocity.browser.block.BlockController.SOURCE).setPositiveButton(R.string.close_confirm, null).showThemed()
                5 -> if (!privateMode) dialog().setTitle(R.string.block_modes).setMessage(R.string.block_mode_scope)
                    .setItems(resources.getStringArray(R.array.block_modes)) { _, selected ->
                        val mode = dev.velocity.browser.block.BlockController.Mode.entries[selected]
                        if (mode == dev.velocity.browser.block.BlockController.Mode.ADGUARD && !blocker.hasAdguard()) notice(R.string.unsupported) else { blocker.setMode(mode); notice(R.string.block_reload_hint) }
                    }.showThemed()
                6 -> if (!privateMode) dialog().setTitle(R.string.adguard_install).setMessage(R.string.adguard_experiment)
                    .setPositiveButton(R.string.yes) { _, _ -> blocker.installAdguard(this) }.setNegativeButton(R.string.cancel, null).showThemed()
                7 -> if (!privateMode) blocker.removeAdguard()
                8 -> if (!privateMode) blocker.openAdguardOptions()
            }
        }.showThemed()
    }

}
