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
    private lateinit var chrome: BrowserChromeView
    private var permissionResult: ((Boolean) -> Unit)? = null
    private var foreground = false
    private var backRegistered = false
    private val modernBack by lazy { android.window.OnBackInvokedCallback { coordinator.back() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chrome = BrowserChromeView(this)
        setContentView(chrome)
        chrome.onNavigate = coordinator::navigate
        chrome.back.setOnClickListener { coordinator.back() }
        chrome.forward.setOnClickListener { coordinator.forward() }
        chrome.reload.setOnClickListener { coordinator.reload() }
        chrome.onTabs = {
            TabSwitcherView.show(this, coordinator.scheduler.tabs, coordinator.scheduler.selectedId,
                coordinator::select, { coordinator.createTab(privateMode = it) }, { coordinator.close(it) }, ::readingDialog)
        }
        chrome.onReading = ::readingDialog
        chrome.onSettings = { settings() }
        chrome.onBlock = { blockSettings() }
        coordinator.bind(this, chrome.geckoView)
        if (savedInstanceState == null) openIntent(intent)
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
        coordinator.unbind(this); super.onDestroy()
    }
    override fun render(tabs: List<TabRecord>, selectedId: String?, canBack: Boolean, canForward: Boolean, loading: Boolean) {
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
    override fun confirmClose(id: String) {
        AlertDialog.Builder(this).setMessage(R.string.close_warning)
            .setPositiveButton(R.string.close_confirm) { _, _ -> coordinator.close(id, true) }
            .setNegativeButton(R.string.cancel, null).show()
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
            getString(R.string.allow_origin), getString(R.string.pin), getString(R.string.adaptive) + " [${coordinator.scheduler.adaptiveEnabled}]", getString(R.string.export_metrics), getString(R.string.reading_new), getString(R.string.reading_original), "ライセンス")
        AlertDialog.Builder(this).setTitle(R.string.settings).setItems(labels) { _, which ->
            when (which) {
                0 -> AlertDialog.Builder(this).setTitle(R.string.search_engine).setSingleChoiceItems(SearchEngine.entries.map { it.name }.toTypedArray(), coordinator.searchEngine.ordinal) { dialog, n -> coordinator.searchEngine = SearchEngine.entries[n]; dialog.dismiss() }.show()
                1 -> coordinator.setPreconnect(!coordinator.preconnectEnabled())
                2 -> AlertDialog.Builder(this).setMessage(R.string.allow_origin).setPositiveButton(R.string.yes) { _, _ -> coordinator.allowCurrentOrigin() }.setNegativeButton(R.string.cancel, null).show()
                3 -> coordinator.scheduler.selectedId?.let { coordinator.pin(it) }
                4 -> coordinator.setAdaptive(!coordinator.scheduler.adaptiveEnabled)
                5 -> coordinator.exportMetrics()
                6 -> readingDialog()
                7 -> coordinator.scheduler.selectedId?.let { coordinator.scheduler.tab(it) }?.takeIf { it.readOnly }?.let { coordinator.createTab(it.url) }
                8 -> AlertDialog.Builder(this).setTitle("ライセンス").setMessage(assets.open("licenses/PETER_LOWES_LIST.txt").bufferedReader().use { it.readText() } + "\n" + assets.open("licenses/JSOUP.txt").bufferedReader().use { it.readText() }).setPositiveButton(R.string.close_confirm, null).show()
            }
        }.show()
    }
    private fun readingDialog() {
        val input = android.widget.EditText(this).apply { isSaveEnabled = false; hint = "https://example.com/" }
        AlertDialog.Builder(this).setTitle(R.string.reading_new).setMessage(R.string.reading_consent).setView(input)
            .setPositiveButton(R.string.go) { _, _ -> UrlPolicy.input(input.text.toString(), coordinator.searchEngine)?.let(coordinator::createReadingTab) }
            .setNegativeButton(R.string.cancel, null).show()
    }
    private fun blockSettings() {
        val tab = coordinator.scheduler.tabs.find { it.id == coordinator.scheduler.selectedId }
        val privateMode = tab?.privateMode == true
        val origin = tab?.url?.let { dev.velocity.browser.block.LiteRules.origin(it) }
        val items = arrayOf(getString(R.string.block_toggle) + " [${blocker.enabled(privateMode)}]",
            getString(R.string.block_exception) + " [${origin?.let { blocker.isException(it, privateMode) } ?: false}]",
            getString(R.string.block_update), getString(R.string.block_user_rules), getString(R.string.block_diagnostics),
            getString(R.string.block_modes), getString(R.string.adguard_install), getString(R.string.adguard_remove), getString(R.string.adguard_options))
        AlertDialog.Builder(this).setTitle(R.string.block_settings).setItems(items) { _, index ->
            if (blocker.busy && index != 4) { notice(R.string.block_busy); return@setItems }
            when (index) {
                0 -> { blocker.setLite(!blocker.enabled(privateMode), privateMode); notice(R.string.block_reload_hint) }
                1 -> if (origin != null) AlertDialog.Builder(this).setMessage(getString(R.string.block_exception_scope, origin))
                    .setPositiveButton(R.string.yes) { _, _ -> runCatching { blocker.toggleException(origin, privateMode) }.onFailure { notice(R.string.block_failed) } }.setNegativeButton(R.string.cancel, null).show()
                2 -> if (!privateMode) AlertDialog.Builder(this).setMessage(R.string.block_update_consent)
                    .setPositiveButton(R.string.yes) { _, _ -> blocker.updateFilters { notice(if (it) R.string.block_updated else R.string.block_failed) } }.setNegativeButton(R.string.cancel, null).show()
                3 -> {
                    val input = android.widget.EditText(this).apply { setText(blocker.userRules(privateMode)); isSaveEnabled = false; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; filters = arrayOf(android.text.InputFilter.LengthFilter(65536)) }
                    AlertDialog.Builder(this).setTitle(R.string.block_user_rules).setMessage(R.string.block_rules_help).setView(input)
                        .setPositiveButton(R.string.go) { _, _ -> runCatching { blocker.setUserRules(input.text.toString(), privateMode) }.onSuccess { result -> android.widget.Toast.makeText(this, getString(R.string.block_rules_result, result.accepted, result.skipped), android.widget.Toast.LENGTH_LONG).show() }.onFailure { notice(R.string.block_failed) } }.setNegativeButton(R.string.cancel, null).show()
                }
                4 -> AlertDialog.Builder(this).setTitle(R.string.block_diagnostics).setMessage(getString(R.string.block_diagnostic_text, blocker.status + " / 遮断判定: " + (if (privateMode) blocker.privateDecisions else blocker.normalDecisions), blocker.filterVersion, blocker.accepted, blocker.skipped,
                    if (blocker.updatedAt == 0L) getString(R.string.block_never_updated) else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(blocker.updatedAt))) + "\n" + dev.velocity.browser.block.BlockController.SOURCE).setPositiveButton(R.string.close_confirm, null).show()
                5 -> if (!privateMode) AlertDialog.Builder(this).setTitle(R.string.block_modes).setMessage(R.string.block_mode_scope)
                    .setItems(resources.getStringArray(R.array.block_modes)) { _, selected ->
                        val mode = dev.velocity.browser.block.BlockController.Mode.entries[selected]
                        if (mode == dev.velocity.browser.block.BlockController.Mode.ADGUARD && !blocker.hasAdguard()) notice(R.string.unsupported) else { blocker.setMode(mode); notice(R.string.block_reload_hint) }
                    }.show()
                6 -> if (!privateMode) AlertDialog.Builder(this).setTitle(R.string.adguard_install).setMessage(R.string.adguard_experiment)
                    .setPositiveButton(R.string.yes) { _, _ -> blocker.installAdguard(this) }.setNegativeButton(R.string.cancel, null).show()
                7 -> if (!privateMode) blocker.removeAdguard()
                8 -> if (!privateMode && blocker.hasAdguard()) blocker.openAdguardOptions()
            }
        }.show()
    }

}
