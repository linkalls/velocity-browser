package dev.velocity.browser.security

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import dev.velocity.browser.tabs.AdaptiveTabCoordinator

/** Application-owned operation survives rotation; unique request codes reject stale results. */
class ExternalActivityBroker(private val owner: AdaptiveTabCoordinator) {
    private data class Request(val code: Int, val tab: String, val done: (Intent?) -> Unit)
    private var nextCode = 4200
    private var pending: Request? = null
    fun launch(tab: String, intent: Intent, done: (Intent?) -> Unit): Int? = start(tab,done) { activity,code ->
        @Suppress("DEPRECATION") activity.startActivityForResult(intent,code)
    }
    fun launch(tab: String, intent: PendingIntent, done: (Intent?) -> Unit): Int? = start(tab,done) { activity,code ->
        @Suppress("DEPRECATION") activity.startIntentSenderForResult(intent.intentSender,code,null,0,0,0)
    }
    private fun start(tab: String, done: (Intent?) -> Unit, launch: (Activity,Int) -> Unit): Int? {
        val host = owner.host
        if (pending != null || host == null || !owner.scheduler.foreground || nextCode >= 65000) { done(null); return null }
        val code = nextCode++; pending = Request(code,tab,done)
        return try { launch(host.activity,code); code } catch (_:Exception) { pending=null;done(null);null }
    }
    fun result(code: Int, resultCode: Int, data: Intent?): Boolean {
        val request = pending?.takeIf { it.code == code } ?: return false
        pending = null; request.done(data?.takeIf { resultCode == Activity.RESULT_OK }); return true
    }
    fun cancel(code: Int?) { val request=pending?.takeIf { it.code == code } ?: return;pending=null;request.done(null) }
    fun cancelFor(tab: String) { pending?.takeIf { it.tab == tab }?.let { cancel(it.code) } }
    fun cancelAll() { pending?.let { cancel(it.code) } }
}
