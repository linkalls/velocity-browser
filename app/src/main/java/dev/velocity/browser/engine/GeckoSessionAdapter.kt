package dev.velocity.browser.engine

import dev.velocity.browser.tabs.*
import dev.velocity.browser.security.Protection
import dev.velocity.browser.security.UrlPolicy
import org.mozilla.geckoview.*

/** Delegateのイベントはcoordinatorへ集約。GeckoSessionはUIへ公開しない。 */
class GeckoSessionAdapter(val id: String, val identity: Long, private val privateMode: Boolean,
    private val owner: AdaptiveTabCoordinator, private val readOnly: Boolean = false) {
    val session = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(privateMode).useTrackingProtection(true).allowJavascript(!readOnly).build())
    private var expectedDataUri: String? = null
    var pristineBlank = true; private set
    var canGoBack = false; private set
    var canGoForward = false; private set
    var loading = false; private set
    private fun traceScope(){
        if(dev.velocity.browser.diagnostics.ScrollDiagnostics.enabled&&owner.isCurrent(id,identity)&&owner.scheduler.selectedId==id)
            dev.velocity.browser.diagnostics.ScrollDiagnostics.context(id,identity,owner.scheduler.tab(id)?.epoch?:0,privateMode)
    }
    private fun emit(e: Event) { if (owner.isCurrent(id, identity)) owner.dispatch(e) }
    fun risk(p: Protection, on: Boolean) = emit(Event.RiskChanged(id, identity, p, on))
    private val scrollTracker=dev.velocity.browser.ui.ScrollIntentTracker()
    fun resetScrollTracking(){scrollTracker.reset()}
    init {
        session.compositorScrollDelegate=object:GeckoSession.CompositorScrollDelegate {
            override fun onScrollChanged(s:GeckoSession,update:GeckoSession.ScrollPositionUpdate) {
                val active=owner.isCurrent(id,identity)&&owner.scheduler.selectedId==id&&owner.scheduler.foreground
                val epoch=owner.scheduler.tab(id)?.epoch?:Long.MIN_VALUE
                val user=update.source==GeckoSession.ScrollPositionUpdate.SOURCE_USER_INTERACTION
                val hint=scrollTracker.update(update.scrollY,update.zoom,user,active,epoch,24*(owner.host?.activity?.resources?.displayMetrics?.density?:1f))
                if(!active)return
                traceScope()
                if (dev.velocity.browser.BuildConfig.DEBUG) dev.velocity.browser.diagnostics.SiteScrollBenchmark.onScroll(
                    id, identity, epoch, user, update.scrollY, update.zoom
                )
                if(user)owner.host?.toolbarUserScroll()
                dev.velocity.browser.diagnostics.ScrollDiagnostics.record(dev.velocity.browser.diagnostics.ScrollDiagnostics.Event.SAMPLE,update.source,hint.reason.ordinal,y=update.scrollY,zoom=update.zoom,delta=hint.deltaPx,intent=hint.intentPx)
                hint.show?.let {owner.host?.toolbarVisibility(it,dev.velocity.browser.ui.ToolbarMotion.Source.COMPOSITOR)}

            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                if (readOnly) return GeckoResult.fromValue(if (request.uri == expectedDataUri) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                val allowed = UrlPolicy.httpUrl(request.uri) != null || owner.extensionPageAllowed(id, request.uri) || (pristineBlank && request.uri == "about:blank")
                if (!allowed) owner.notice(dev.velocity.browser.R.string.invalid_url)
                else if (request.uri != "about:blank") {
                    pristineBlank = false
                    risk(Protection.UNKNOWN, true)
                }
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
            override fun onLocationChange(s: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                resetScrollTracking()
                if (!readOnly && url != null && (url == "about:blank" || UrlPolicy.httpUrl(url) != null || owner.extensionPageAllowed(id, url))) emit(Event.LocationChanged(id, identity, url))
            }
            override fun onCanGoBack(s: GeckoSession, value: Boolean) { canGoBack = value; owner.render() }
            override fun onCanGoForward(s: GeckoSession, value: Boolean) { canGoForward = value; owner.render() }
            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                // opener付き新窓は未対応。管理された独立タブにURLを渡し、隠しsessionは作らない。
                if (owner.isCurrent(id, identity) && UrlPolicy.httpUrl(uri) != null) owner.createTab(uri, privateMode)
                else owner.notice(dev.velocity.browser.R.string.unsupported)
                return null
            }
            override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                owner.notice(dev.velocity.browser.R.string.load_error)
                return null // 証明書例外・安全性迂回UIを提供しない。
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                resetScrollTracking();traceScope();if(owner.isCurrent(id,identity)&&owner.scheduler.selectedId==id)owner.host?.toolbarContextReset()
                if (readOnly) { loading = true; owner.render(); return }
                if (url != "about:blank") pristineBlank = false
                loading = true; emit(Event.NavigationStarted(id, identity, url)); owner.render()
            }
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                loading = false; owner.render()
                if (readOnly) { if (success) owner.readingLoaded(id, identity); return }
                val epoch = owner.scheduler.tab(id)?.epoch ?: return
                session.containsFormData().accept({ hasForm ->
                    if (owner.isCurrent(id, identity) && owner.scheduler.tab(id)?.epoch == epoch) risk(Protection.FORM, hasForm == true)
                }, { if (owner.isCurrent(id, identity)) risk(Protection.UNKNOWN, true) })
            }
            override fun onSessionStateChange(s: GeckoSession, state: GeckoSession.SessionState) {
                // このAPIにはnavigation epochもflush要求IDもない。Webの状態を最新と偽認定しない。
                if (!readOnly && pristineBlank) owner.acceptBlankSnapshot(id, identity, state)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onShowDynamicToolbar(s: GeckoSession) { traceScope();if (owner.isCurrent(id,identity) && owner.scheduler.selectedId==id) owner.host?.toolbarVisibility(true) }
            override fun onHideDynamicToolbar(s: GeckoSession) { traceScope();if (owner.isCurrent(id,identity) && owner.scheduler.selectedId==id) owner.host?.toolbarVisibility(false) }
            override fun onTitleChange(s: GeckoSession, title: String?) { emit(Event.TitleChanged(id, identity, title.orEmpty())) }
            override fun onFirstComposite(s:GeckoSession) {
                traceScope()
                if(owner.isCurrent(id,identity)&&owner.scheduler.selectedId==id&&owner.isDisplayed(s))owner.host?.toolbarViewportReady()
            }
            override fun onFirstContentfulPaint(s: GeckoSession) { if (owner.isCurrent(id, identity)) owner.firstContent(id) }
            override fun onCrash(s: GeckoSession) { emit(Event.Crashed(id, identity)); owner.notice(dev.velocity.browser.R.string.crash) }
            override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
                owner.downloads.offer(this@GeckoSessionAdapter,response)
            }
        }
        session.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onPlay(s: GeckoSession, media: MediaSession) = risk(Protection.MEDIA, true)
            override fun onPause(s: GeckoSession, media: MediaSession) = risk(Protection.MEDIA, false)
            override fun onStop(s: GeckoSession, media: MediaSession) = risk(Protection.MEDIA, false)
        }
        session.mediaDelegate = object : GeckoSession.MediaDelegate {
            override fun onRecordingStatusChanged(s: GeckoSession, devices: Array<GeckoSession.MediaDelegate.RecordingDevice>) {
                val active = devices.filter { it.status == GeckoSession.MediaDelegate.RecordingDevice.Status.RECORDING }
                risk(Protection.CAMERA, active.any { it.type == GeckoSession.MediaDelegate.RecordingDevice.Type.CAMERA })
                risk(Protection.MICROPHONE, active.any { it.type == GeckoSession.MediaDelegate.RecordingDevice.Type.MICROPHONE })
            }
        }
        session.permissionDelegate = owner.permissions.delegate(this)
        session.promptDelegate = owner.prompts.delegate(this)
    }
    fun loadReading(doc: dev.velocity.browser.reading.ReadingDocument) {
        check(readOnly)
        pristineBlank = false
        val html = doc.html.toByteArray(Charsets.UTF_8)
        expectedDataUri = "data:text/html;base64," + android.util.Base64.encodeToString(html, android.util.Base64.NO_WRAP)
        val url = owner.scheduler.tab(id)?.url ?: return
        emit(Event.NavigationStarted(id, identity, url))
        session.load(GeckoSession.Loader().data(html, "text/html"))
    }
    fun load(url: String) { if (url != "about:blank") pristineBlank = false; session.loadUri(url) }
    fun close() {
        session.compositorScrollDelegate=null;session.navigationDelegate = null; session.progressDelegate = null; session.contentDelegate = null
        session.mediaSessionDelegate = null; session.mediaDelegate = null; session.permissionDelegate = null; session.promptDelegate = null
        if (session.isOpen) session.close()
    }
}
