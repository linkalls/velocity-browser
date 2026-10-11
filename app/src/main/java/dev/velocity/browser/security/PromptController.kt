package dev.velocity.browser.security

import android.app.AlertDialog
import android.widget.EditText
import dev.velocity.browser.R
import dev.velocity.browser.engine.GeckoSessionAdapter
import dev.velocity.browser.tabs.AdaptiveTabCoordinator
import org.mozilla.geckoview.*

class PromptController(private val owner: AdaptiveTabCoordinator) {
    private val pending = mutableMapOf<GeckoSessionAdapter, MutableList<() -> Unit>>()
    fun cancelFor(a: GeckoSessionAdapter) { pending.remove(a)?.toList()?.forEach { it() } }
    fun cancelAll() { pending.keys.toList().forEach { cancelFor(it) } }
    private fun show(a: GeckoSessionAdapter, prompt: GeckoSession.PromptDelegate.BasePrompt,
        message: String?, text: String? = null, confirm: (String) -> GeckoSession.PromptDelegate.PromptResponse): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val host = owner.host
        if (host == null || owner.scheduler.selectedId != a.id || !owner.scheduler.foreground) return GeckoResult.fromValue(prompt.dismiss())
        a.risk(Protection.PROMPT, true)
        var completed = false
        var dialog: AlertDialog? = null
        lateinit var cancel: () -> Unit
        fun finish(positive: Boolean, value: String = "") {
            if (completed) return
            completed = true; pending[a]?.remove(cancel)
            prompt.setDelegate(null)
            if (!prompt.isComplete) result.complete(if (positive && owner.isCurrent(a.id, a.identity)) confirm(value) else prompt.dismiss())
            dialog?.dismiss()
            if (pending[a].isNullOrEmpty()) a.risk(Protection.PROMPT, false)
        }
        cancel = { finish(false) }; pending.getOrPut(a) { mutableListOf() }.add(cancel)
        val input = if (text != null) EditText(host.activity).also { it.setText(text) } else null
        val builder = AlertDialog.Builder(host.activity).setTitle(prompt.title ?: host.activity.getString(R.string.error_title))
            .setMessage(message).setPositiveButton(android.R.string.ok) { _, _ -> finish(true, input?.text?.toString().orEmpty()) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish(false) }.setOnCancelListener { finish(false) }
        if (input != null) builder.setView(input)
        prompt.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: GeckoSession.PromptDelegate.BasePrompt) { finish(false) }
        })
        dialog = builder.create(); dialog.show(); return result
    }
    private fun unsupported(a: GeckoSessionAdapter, p: GeckoSession.PromptDelegate.BasePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        owner.notice(R.string.unsupported); return GeckoResult.fromValue(p.dismiss())
    }
    fun delegate(a: GeckoSessionAdapter) = object : GeckoSession.PromptDelegate {
        override fun onAlertPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.AlertPrompt) = show(a, p, p.message) { p.dismiss() }
        override fun onButtonPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.ButtonPrompt) = show(a, p, p.message) { p.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE) }
        override fun onTextPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.TextPrompt) = show(a, p, p.message, p.defaultValue.orEmpty()) { p.confirm(it) }
        override fun onBeforeUnloadPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.BeforeUnloadPrompt) = show(a, p, owner.host?.activity?.getString(R.string.close_warning)) { p.confirm(AllowOrDeny.ALLOW) }
        override fun onRepostConfirmPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.RepostConfirmPrompt) = show(a, p, owner.host?.activity?.getString(R.string.close_warning)) { p.confirm(AllowOrDeny.ALLOW) }
        override fun onFilePrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.FilePrompt) = unsupported(a, p)
        override fun onFolderUploadPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.FolderUploadPrompt) = unsupported(a, p)
        override fun onAuthPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.AuthPrompt) = unsupported(a, p)
        override fun onPopupPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.PopupPrompt) = unsupported(a, p)
    }
}
