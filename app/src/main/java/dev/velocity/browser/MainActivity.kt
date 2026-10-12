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
    private var settingsScreen:String?=null
    private var settingsView:SettingsView?=null
    private var menuSheet:BrowserMenuSheet?=null
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
    private val modernBack by lazy { android.window.OnBackInvokedCallback { if(chrome.address.hasFocus())chrome.cancelAddressEdit() else coordinator.back() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(appearance.style())
        super.onCreate(savedInstanceState)
        chrome = BrowserChromeView(this,(application as VelocityApp).homeInputState)
        setContentView(chrome)
        chrome.onNavigate = coordinator::navigate
        chrome.back.setOnClickListener { coordinator.back() }
        chrome.forward.setOnClickListener { coordinator.forward() }
        chrome.reload.setOnClickListener { if(chrome.address.hasFocus())chrome.submitAddress() else coordinator.reload() }
        chrome.onEditingChanged={coordinator.render()}
        chrome.onTabs = {
            tabSheet?.dismiss()
            tabSheet = TabSwitcherView(this, appearance, { id ->
                coordinator.select(id); coordinator.scheduler.selectedId == id && coordinator.scheduler.pendingId == null
            }, { privateMode ->
                val before = coordinator.scheduler.selectedId
                coordinator.createTab(privateMode = privateMode)
                coordinator.scheduler.selectedId != before && coordinator.scheduler.pendingId == null
            }, { coordinator.close(it) }, coordinator::cancelPending, ::readingDialog, coordinator::undoClose, coordinator::canUndo, coordinator::pin, ::organizeTabs).also {
                it.update(coordinator.scheduler.tabs,coordinator.scheduler.selectedId,coordinator.scheduler.pendingId); it.show()
            }
        }
        chrome.onCreateTab={privateMode->coordinator.createTab(privateMode=privateMode)}
        chrome.onHome={coordinator.createTab(getSharedPreferences("settings",MODE_PRIVATE).getString("home_url","about:blank")!!)}
        chrome.onAddShortcut=::shortcutSettings
        chrome.onMenu=::browserMenu
        chrome.onSwitchTab={direction->
            val tabs=coordinator.scheduler.tabs;val current=tabs.indexOfFirst {it.id==coordinator.scheduler.selectedId}
            tabs.getOrNull(current+direction)?.let {coordinator.select(it.id)}
        }
        chrome.onReading = ::readingDialog
        chrome.onSettings = { settings() }
        chrome.onBlock = { blockSettings() }
        appearance.observe(appearanceChanged); applyAppearance()
        coordinator.bind(this, chrome.geckoView)
        if (savedInstanceState == null) openIntent(intent)
        else chrome.post {when(savedInstanceState.getString("settings_screen")){"menu"->browserMenu();"settings"->settings();"block"->blockSettings();"site"->siteRules()};if(savedInstanceState.getBoolean("tabs_open"))chrome.onTabs() }
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("settings_screen",settingsScreen);outState.putBoolean("tabs_open",tabSheet?.isShowing==true)
        super.onSaveInstanceState(outState)
    }
    private fun showSettings(kind:String,title:String,rows:List<SettingsView.Row>) {
        appearance.dismissFor(this);settingsScreen=kind
        settingsView=SettingsView(this,appearance,title,rows,searchable=kind!="menu",compact=kind=="menu") {if(settingsScreen==kind){settingsScreen=null;settingsView=null}}.also {it.show()}
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
    override fun onWindowFocusChanged(hasFocus:Boolean) { super.onWindowFocusChanged(hasFocus);if(::chrome.isInitialized)chrome.setModalBlocked(!hasFocus) }
    override fun toolbarUserScroll(){chrome.onUserScroll()}
    override fun toolbarContextReset(){chrome.resetScrollContext()}
    override fun toolbarVisibility(show:Boolean,source:ToolbarMotion.Source) {chrome.onEngineToolbar(show,source)}
    override fun render(tabs: List<TabRecord>, selectedId: String?, canBack: Boolean, canForward: Boolean, loading: Boolean) {
        settingsView?.refresh()
        tabSheet?.takeIf { it.isShowing }?.update(tabs,selectedId,coordinator.scheduler.pendingId)
        val waiting = coordinator.scheduler.pendingId
        if (capacity != null && capacity?.targetId != waiting) { capacity?.dismiss(); capacity = null }
        if (waiting != null && !coordinator.delegatedRequestInProgress) {
            if (capacity == null && tabSheet?.isShowing != true) capacityBlocked(waiting,coordinator.isOptionsPending(waiting))
            else capacity?.update(tabs)
        }
        val t = tabs.find { it.id == selectedId }
        chrome.showStart(t == null || t.url == "about:blank")
        val secure=tabs.any {it.privateMode};val alreadySecure=window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0
        if(secure!=alreadySecure){if(secure)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}
        val title = (if (t?.readOnly == true) getString(R.string.reading_label) else "") + (if (t?.privateMode == true) getString(R.string.private_label) else "") + (t?.title ?: getString(R.string.app_name))
        if (chrome.title.text.toString() != title) chrome.title.text = title
        val url = t?.url?.takeUnless { it == "about:blank" }.orEmpty()
        chrome.setAddressUrl(url)
        chrome.updateState(tabs.size, t?.privateMode == true, blocker.mode.name, blocker.enabled(t?.privateMode == true), blocker.busy, if (t?.privateMode == true) blocker.privateDecisions else blocker.normalDecisions, blocker.status, selectedId,t?.identity,t?.epoch?:0)
        if(chrome.back.isEnabled!=canBack)chrome.back.isEnabled=canBack; if(chrome.forward.isEnabled!=canForward)chrome.forward.isEnabled=canForward
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (canBack && !backRegistered) { onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, modernBack); backRegistered = true }
            else if (!canBack && backRegistered) { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(modernBack); backRegistered = false }
        }
        chrome.setPageLoading(loading)
        val reloadLabel = getString(if(chrome.address.hasFocus()) R.string.go else if (loading) R.string.stop else R.string.reload)
        if (chrome.reload.text.toString() != reloadLabel) chrome.reload.text = reloadLabel
        if (chrome.reload.contentDescription != reloadLabel) chrome.reload.contentDescription = reloadLabel
    }
    override fun notice(message: Int) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun capacityBlocked(id: String, adguard: Boolean) {
        if (!adguard && tabSheet?.isShowing == true) return
        if (capacity?.targetId == id) return
        capacity?.dismiss()
        capacity = CapacityDialog(this,id,adguard,appearance,{ coordinator.select(it) },{ target ->
            if (coordinator.host === this) coordinator.cancelPending(target)
        }, { capacity = null; chrome.onTabs() }).also { it.update(coordinator.scheduler.tabs); it.show() }
    }
    override fun confirmClose(id: String) {
        val tab = coordinator.scheduler.tab(id) ?: return
        val identity = tab.identity
        val homeDraft=(application as VelocityApp).homeInputState.get(id)
        val pending = capacity?.targetId
        fun cancelRequest() { if (coordinator.host === this && pending != null) coordinator.cancelPending(pending) }
        val builder = dialog().setTitle("『${tab.displayTitle()}』を閉じる")
            .setMessage("${tab.displayLocation()}\n" + getString(R.string.close_warning))
            .setPositiveButton("このタブを閉じる") { _, _ ->
                if (coordinator.host === this) {
                    val current=coordinator.scheduler.tab(id)
                    if(current!=null&&current.identity==identity&&current.epoch==tab.epoch&&current.revision==tab.revision&&(application as VelocityApp).homeInputState.get(id)==homeDraft)coordinator.close(id,true)
                    else Toast.makeText(this,"ページの状態が変わりました。内容を確認してから閉じてください。",Toast.LENGTH_LONG).show()
                }
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
        if(chrome.address.hasFocus())chrome.cancelAddressEdit() else if (chrome.back.isEnabled) coordinator.back() else super.onBackPressed()
    }
    private fun browserMenu() {
        chrome.cancelAddressEdit()
        val current=coordinator.scheduler.selectedId?.let(coordinator.scheduler::tab)
        appearance.dismissFor(this);settingsScreen="menu"
        fun action(label:String,icon:String="",enabled:Boolean=true,run:()->Unit)=BrowserMenuSheet.Action(label,icon,enabled,run)
        menuSheet=BrowserMenuSheet(this,appearance,listOf(
            action("進む","forward",chrome.forward.isEnabled){coordinator.forward()},
            action(chrome.reload.text.toString(),"reload"){coordinator.reload()},
            action("共有","share",current?.url?.let(UrlPolicy::httpUrl)!=null){
                current?.url?.let {url->startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,url)},"ページを共有"))}
            },
            action("新しいタブ","add"){coordinator.createTab()}
        ),listOf(
            "タブ" to listOf(action("新しいプライベートタブ"){coordinator.createTab(privateMode=true)},action("タブを表示"){chrome.onTabs()}),
            "ページ" to listOf(action("ホーム"){chrome.onHome()},action("ダウンロード"){coordinator.downloads.show()},action("リーダー表示"){readingDialog()},action("広告ブロック設定"){blockSettings()}),
            "ブラウザ" to listOf(action("設定"){settings()})
        )) {if(settingsScreen=="menu")settingsScreen=null;menuSheet=null}.also {it.show()}
    }
    private fun shortcutSettings() {
        val prefs=getSharedPreferences("settings",MODE_PRIVATE)
        val list=runCatching {org.json.JSONArray(prefs.getString("home_shortcuts","[]"))}.getOrElse {org.json.JSONArray()}
        val items=(0 until list.length()).map {list.getJSONObject(it).optString("title")}.toMutableList();items.add("＋ ショートカットを追加")
        dialog().setTitle("ホームのショートカット").setItems(items.toTypedArray()) {_,index->
            if(index<list.length())dialog().setTitle(items[index]).setItems(arrayOf("前へ移動","後へ移動","削除")) {_,operation->
                if(operation==2)dialog().setTitle("ショートカットを削除").setMessage(items[index]).setPositiveButton("削除") {_,_->list.remove(index);prefs.edit().putString("home_shortcuts",list.toString()).apply()}.setNegativeButton(R.string.cancel,null).showThemed()
                else {
                    val target=if(operation==0)index-1 else index+1
                    if(target in 0 until list.length()){val item=list.get(index);list.put(index,list.get(target));list.put(target,item);prefs.edit().putString("home_shortcuts",list.toString()).apply()}
                }
            }.showThemed()
            else if(list.length()<12){
                val panel=android.widget.LinearLayout(this).apply {orientation=android.widget.LinearLayout.VERTICAL;setPadding(24,12,24,12)}
                val name=android.widget.EditText(this).apply {hint="表示名";isSaveEnabled=false}
                val url=android.widget.EditText(this).apply {hint="https://example.com";isSaveEnabled=false;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI}
                panel.addView(name);panel.addView(url)
                val d=dialog().setTitle("ショートカットを追加").setView(panel).setPositiveButton("追加",null).setNegativeButton(R.string.cancel,null).showThemed()
                d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val target=UrlPolicy.httpUrl(url.text.toString().trim())
                    if(target==null){url.error="HTTPまたはHTTPSのURLを入力";return@setOnClickListener}
                    list.put(org.json.JSONObject().put("title",name.text.toString().take(80).ifBlank {java.net.URI(target).host}).put("url",target))
                    prefs.edit().putString("home_shortcuts",list.toString()).apply();d.dismiss()
                }
            }
        }.showThemed()
    }
    private fun settings() {
        val labels = arrayOf(getString(R.string.search_engine), getString(R.string.preconnect) + " [${coordinator.preconnectEnabled()}]",
            getString(R.string.allow_origin), getString(R.string.pin), getString(R.string.adaptive) + " [${coordinator.scheduler.adaptiveEnabled}]", getString(R.string.export_metrics), getString(R.string.reading_new), getString(R.string.reading_original), "ライセンス", getString(R.string.appearance), getString(R.string.downloads), getString(R.string.passkey_diagnostics), getString(R.string.data_protection))
        val categories=mapOf(0 to "一般",1 to "タブと性能",2 to "タブと性能",3 to "タブと性能",4 to "タブと性能",5 to "詳細・診断",6 to "詳細・診断",7 to "詳細・診断",8 to "詳細・診断",9 to "外観と操作",10 to "ダウンロードと権限",11 to "詳細・診断",12 to "プライバシーとブロック")
        val rows=labels.mapIndexed {which,label->SettingsView.Row(categories[which]?:"詳細情報",label,action={
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
        }) }.toMutableList()
        rows[0].liveText = { getString(R.string.search_engine) + " · " + coordinator.searchEngine.name }
        rows[1].liveText = { getString(R.string.preconnect) }; rows[1].navigation=false;rows[1].checked = { coordinator.preconnectEnabled() }
        rows[4].liveText = { getString(R.string.adaptive) }; rows[3].navigation=false;rows[5].navigation=false;rows[4].navigation=false;rows[4].checked = { coordinator.scheduler.adaptiveEnabled }
        rows[9].liveText = { getString(R.string.appearance) + " · " + arrayOf("システム", "ライト", "ダーク")[appearance.mode.ordinal] }
        rows.add(0,SettingsView.Row("タブと性能","ライブ目標 ${coordinator.scheduler.config.liveTarget}枚 · ${coordinator.scheduler.config.discardAfterMs/1000}秒","保護中は目標を超えて保持します。RAMの推奨: ${coordinator.recommendedProfile().name}",::memorySettings).apply { liveText = { "ライブ目標 ${coordinator.scheduler.config.liveTarget}枚 · ${coordinator.scheduler.config.discardAfterMs/1000}秒" } })
        rows.add(SettingsView.Row("一般","アドレスバー位置",if(chrome.bottomAddress)"下" else "上") {chrome.setAddressBottom(!chrome.bottomAddress)}.apply {liveText={"アドレスバー位置\n"+if(chrome.bottomAddress)"下" else "上"};navigation=false})
        rows.add(SettingsView.Row("外観と操作","アドレスバーのスワイプ","左右にスワイプしてタブを切り替える") {chrome.swipeEnabled=!chrome.swipeEnabled}.apply {liveText={"アドレスバーのスワイプ"};checked={chrome.swipeEnabled}})
        rows.add(SettingsView.Row("プライバシーとブロック","広告ブロック",blocker.mode.name,::blockSettings))
        rows.add(SettingsView.Row("一般","ホームのショートカット","最大12件・並べ替え・追加・削除",::shortcutSettings))
        rows.add(SettingsView.Row("一般","ホームページ","ホーム操作で開くURL（空欄でVelocityホーム）") {
            val prefs=getSharedPreferences("settings",MODE_PRIVATE)
            val input=android.widget.EditText(this).apply {isSaveEnabled=false;hint="https://example.com";setText(prefs.getString("home_url",""))}
            val d=dialog().setTitle("ホームページ").setView(input).setPositiveButton("保存",null).setNegativeButton(R.string.cancel,null).showThemed()
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw=input.text.toString().trim();val url=if(raw.isEmpty())"about:blank" else UrlPolicy.httpUrl(raw)
                if(url==null){input.error="HTTPまたはHTTPSのURLを入力"}else{prefs.edit().putString("home_url",url).apply();d.dismiss()}
            }
        })
        rows.add(SettingsView.Row("外観と操作","スクロールでツールバーを隠す","入力中・メニュー表示中・TalkBackでは表示を維持") {val p=getSharedPreferences("settings",MODE_PRIVATE);p.edit().putBoolean("toolbar_scroll",!p.getBoolean("toolbar_scroll",true)).apply()}.apply {checked={getSharedPreferences("settings",MODE_PRIVATE).getBoolean("toolbar_scroll",true)}})
        rows.add(SettingsView.Row("外観と操作","タブ表示","コンパクト一覧 / 2列グリッド") {val p=getSharedPreferences("settings",MODE_PRIVATE);p.edit().putBoolean("tabs_list",!p.getBoolean("tabs_list",true)).apply()}.apply {liveText={"タブ表示 · "+if(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("tabs_list",true))"一覧" else "グリッド"};navigation=false})
        rows.add(SettingsView.Row("外観と操作","文字サイズ","Androidの設定に従います") {startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS))})
        rows.add(SettingsView.Row("ダウンロードと権限","サイト権限","Android設定でアプリの権限を確認・取り消し") {startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))})
        rows.add(SettingsView.Row("詳細・診断","Velocity ${packageManager.getPackageInfo(packageName,0).versionName}","GeckoView 157 · Android Views") {dialog().setTitle("既知の制限").setMessage(R.string.restore_limits).setPositiveButton(R.string.close_confirm,null).showThemed()})
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)rows.add(SettingsView.Row("詳細・診断","配色プレビュー","全トークンのライト・ダーク確認") {PalettePreview.show(this,appearance)})
        if(BuildConfig.DEBUG){
            rows.add(SettingsView.Row("詳細・診断","スクロール診断を記録","通常タブのみ・匿名数値768件・常時保存や送信なし") {
                dev.velocity.browser.diagnostics.ScrollDiagnostics.enable(!dev.velocity.browser.diagnostics.ScrollDiagnostics.enabled);coordinator.render()
            }.apply {checked={dev.velocity.browser.diagnostics.ScrollDiagnostics.enabled}})
            rows.add(SettingsView.Row("詳細・診断","スクロール診断をローカル保存","操作・座標・ツールバー状態のみをCSVで保存") {
                val id=coordinator.scheduler.selectedId
                val csv=dev.velocity.browser.diagnostics.ScrollDiagnostics.snapshot()
                if(id!=null&&csv.isNotEmpty()){
                    val appContext=applicationContext
                    coordinator.externalActivities.launch(id,Intent(Intent.ACTION_CREATE_DOCUMENT).apply {type="text/csv";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,"velocity-scroll-trace.csv")}) {data->
                        data?.data?.let {uri->Thread {
                            val ok=runCatching {appContext.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {it.write(csv)}?:error("No stream")}.isSuccess
                            runOnUiThread {Toast.makeText(appContext,if(ok)"診断を保存しました" else "診断を保存できませんでした",Toast.LENGTH_LONG).show()}
                        }.start()}
                    }
                }
            }.apply {available={dev.velocity.browser.diagnostics.ScrollDiagnostics.size>0}})
        }
        val categoryOrder = listOf("一般", "外観と操作", "プライバシーとブロック", "タブと性能", "ダウンロードと権限", "詳細・診断")
        showSettings("settings","設定",rows.sortedBy {categoryOrder.indexOf(it.category)})
    }
    private fun memorySettings() {
        val options=arrayOf("省メモリ · 3枚 / 30秒","バランス · 6枚 / 120秒","高速重視 · 12枚 / 600秒","カスタム")
        dialog().setTitle("タブとメモリ").setItems(options) {_,n->
            if(n<3)coordinator.setTabConfig(TabProfile.entries[n].config()) else {
                val panel=android.widget.LinearLayout(this).apply {orientation=android.widget.LinearLayout.VERTICAL}
                val target=android.widget.EditText(this).apply {hint="目標枚数 2〜32";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(coordinator.scheduler.config.liveTarget.toString())}
                val idle=android.widget.EditText(this).apply {hint="非表示秒数 30〜1800";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText((coordinator.scheduler.config.discardAfterMs/1000).toString())}
                panel.addView(target);panel.addView(idle)
                dialog().setTitle("カスタム").setMessage("目標は上限ではありません。保護中のタブは閉じません。").setView(panel).setPositiveButton("適用") {_,_->
                    val count=target.text.toString().toIntOrNull();val seconds=idle.text.toString().toLongOrNull()
                    if(count!=null && count in 2..32 && seconds!=null && seconds in 30..1800)coordinator.setTabConfig(AdaptiveConfig(liveTarget=count,discardAfterMs=seconds*1000))
                    else Toast.makeText(this,"設定範囲を確認してください",Toast.LENGTH_LONG).show()
                }.setNegativeButton(R.string.cancel,null).showThemed()
            }
        }.showThemed()
    }
    private fun readingDialog() {
        val input = android.widget.EditText(appearance.dialogContext(this)).apply { isSaveEnabled = false; hint = "https://example.com/" }
        dialog().setTitle(R.string.reading_new).setMessage(R.string.reading_consent).setView(input)
            .setPositiveButton(R.string.go) { _, _ -> UrlPolicy.input(input.text.toString(), coordinator.searchEngine)?.let(coordinator::createReadingTab) }
            .setNegativeButton(R.string.cancel, null).showThemed()
    }
    private fun organizeTabs() {
        dialog().setTitle("タブを整理").setItems(arrayOf("全て閉じる","他のタブを閉じる","重複URLのタブを整理")) {_,choice->
            val tabs=coordinator.scheduler.tabs
            val seen=mutableSetOf<Pair<Boolean,String>>()
            val closing=tabs.filter {when(choice){0->true;1->it.id!=coordinator.scheduler.selectedId;else->!seen.add(it.privateMode to it.url)}}
            if(closing.isEmpty())return@setItems
            appearance.show(dialog().setTitle("${closing.size}枚のタブを閉じる")
                .setMessage("未保存のフォームや操作中のデータも失われます。固定されたタブも対象です。取り消しで完全なページ状態は復元できません。")
                .setPositiveButton("閉じる") {_,_->closing.forEach {t->
                    val current=coordinator.scheduler.tab(t.id)
                    if(current!=null&&current.identity==t.identity&&current.epoch==t.epoch&&current.revision==t.revision)coordinator.close(t.id,true)
                }}.setNegativeButton(R.string.cancel,null),true)
        }.showThemed()
    }
    private fun siteRules() {
        val t=coordinator.scheduler.selectedId?.let(coordinator.scheduler::tab)?:return
        val origin=dev.velocity.browser.block.LiteRules.origin(t.url)?:return
        val rows=blocker.siteRules(origin,t.privateMode).map {rule->SettingsView.Row("このサイトの規則",rule,"タップでこの規則を削除") {blocker.removeUserRule(rule,t.privateMode)}}
        showSettings("site","サイト別ルール",rows+SettingsView.Row("取り消し","直前の要素選択を元に戻す","ページを再読み込みせず解除します") {blocker.undoPickerRule(t.privateMode)})
    }
    private fun pickElement() {
        val t=coordinator.scheduler.selectedId?.let(coordinator.scheduler::tab)?:return
        val origin=dev.velocity.browser.block.LiteRules.origin(t.url)?:return
        val identity=t.identity?:return
        if(blocker.startPicker(origin,t.privateMode) {val current=coordinator.scheduler.tab(t.id);coordinator.scheduler.selectedId==t.id&&current?.identity==identity&&current.epoch==t.epoch}) {
            appearance.dismissFor(this)
        } else Toast.makeText(this,"このページではLiteを有効にしてから選択してください",Toast.LENGTH_LONG).show()
    }
    private fun blockSettings() {
        val tab = coordinator.scheduler.tabs.find { it.id == coordinator.scheduler.selectedId }
        val privateMode = tab?.privateMode == true
        val origin = tab?.url?.let { dev.velocity.browser.block.LiteRules.origin(it) }
        val items = arrayOf(getString(R.string.block_toggle) + " [${blocker.enabled(privateMode)}]",
            getString(R.string.block_exception) + " [${origin?.let { blocker.isException(it, privateMode) } ?: false}]",
            getString(R.string.block_update), getString(R.string.block_user_rules), getString(R.string.block_diagnostics),
            getString(R.string.block_modes), getString(R.string.adguard_install), getString(R.string.adguard_remove), getString(R.string.adguard_options))
        val rows=items.mapIndexed {index,label->SettingsView.Row(if(index>=5)"エンジン · 実験機能" else "このサイトとフィルター",label,action=action@ {
            if (blocker.busy && index != 4) { notice(R.string.block_busy); return@action }
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
                8 -> if (!privateMode) {appearance.dismissFor(this);blocker.openAdguardOptions()}
            }
        }) }
        rows[0].liveText={getString(R.string.block_toggle)};rows[0].checked={blocker.enabled(privateMode)};rows[0].available={!blocker.busy}
        rows[7].navigation=false
        val cosmeticRows=listOf(
            SettingsView.Row("要素非表示","このサイトの非表示ルールを管理","削除・直前の選択ルールを取り消し",::siteRules),
            SettingsView.Row("要素非表示","ページ内の要素を選択して非表示","Lite有効時のみ。適用前にプレビューできます",::pickElement),
            SettingsView.Row("要素非表示","広告要素を非表示 · ${blocker.cosmeticEnabled}","Liteのみ。フォーム・本文領域を保護します") {blocker.setCosmetic(!blocker.cosmeticEnabled)},
            SettingsView.Row("要素非表示","広告の空白を折りたたむ · ${blocker.collapseEnabled}","広告専用の空枠のみ。高負荷時は監視を停止") {blocker.setCollapse(!blocker.collapseEnabled)},
            SettingsView.Row("要素非表示","このサイトの折りたたみ","広告専用の空白をこのサイトで折りたたむ") {if(origin!=null)blocker.toggleCollapseException(origin,privateMode)}
        )
        cosmeticRows[2].liveText={"広告要素を非表示"};cosmeticRows[2].checked={blocker.cosmeticEnabled}
        cosmeticRows[3].liveText={"広告の空白を折りたたむ"};cosmeticRows[3].checked={blocker.collapseEnabled}
        cosmeticRows[4].liveText={"このサイトの折りたたみ"};cosmeticRows[4].checked={origin!=null&&!blocker.collapseExcluded(origin,privateMode)}
        fun counts()=if(blocker.mode==dev.velocity.browser.block.BlockController.Mode.ADGUARD)"AdGuardの非表示・折りたたみ: 未取得" else "全${if(privateMode)"非公開" else "通常"}タブの観測数 · 非表示 ${if(privateMode)blocker.privateHiddenElements else blocker.hiddenElements} / 折りたたみ ${if(privateMode)blocker.privateCollapsedElements else blocker.collapsedElements}（各ページ100候補まで）"
        showSettings("block","広告とトラッカーのブロック",listOf(SettingsView.Row("状態",origin?:"ページ未選択",action={}).apply {navigation=false;available={false};liveText={ (origin?:"ページ未選択")+"\n${blocker.mode.name} · 遮断判定 ${if(privateMode)blocker.privateDecisions else blocker.normalDecisions}（広告の個数ではありません）\n${counts()}"}})+rows.take(2)+cosmeticRows+rows.drop(2))
    }

}
