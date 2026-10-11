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
        val epoch=owner.scheduler.tab(a.id)?.epoch
        a.risk(Protection.PROMPT, true)
        var completed = false
        var dialog: AlertDialog? = null
        lateinit var cancel: () -> Unit
        fun finish(positive: Boolean, value: String = "") {
            if (completed) return
            completed = true; pending[a]?.remove(cancel)
            prompt.setDelegate(null)
            if (!prompt.isComplete) result.complete(if (positive && owner.isCurrent(a.id, a.identity) && owner.scheduler.tab(a.id)?.epoch==epoch && owner.scheduler.selectedId==a.id && owner.scheduler.foreground) confirm(value) else prompt.dismiss())
            else result.completeExceptionally(IllegalStateException("Prompt already completed"))
            dialog?.dismiss()
            if (pending[a].isNullOrEmpty()) a.risk(Protection.PROMPT, false)
        }
        cancel = { finish(false) }; pending.getOrPut(a) { mutableListOf() }.add(cancel)
        val input = if (text != null) EditText((host.activity.application as dev.velocity.browser.VelocityApp).appearance.dialogContext(host.activity)).also { it.setText(text) } else null
        val builder = (host.activity.application as dev.velocity.browser.VelocityApp).appearance.builder(host.activity).setTitle(prompt.title ?: host.activity.getString(R.string.error_title))
            .setMessage(message).setPositiveButton(android.R.string.ok) { _, _ -> finish(true, input?.text?.toString().orEmpty()) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish(false) }.setOnCancelListener { finish(false) }
        if (input != null) builder.setView(input)
        prompt.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: GeckoSession.PromptDelegate.BasePrompt) { finish(false) }
        })
        dialog = builder.create(); dialog.show(); (host.activity.application as dev.velocity.browser.VelocityApp).appearance.track(dialog)
        if(owner.scheduler.tab(a.id)?.privateMode==true)dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        return result
    }
    private fun unsupported(a: GeckoSessionAdapter, p: GeckoSession.PromptDelegate.BasePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        owner.notice(R.string.unsupported); return GeckoResult.fromValue(p.dismiss())
    }
    private fun authenticate(a: GeckoSessionAdapter, p: GeckoSession.PromptDelegate.AuthPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val host=owner.host;val tab=owner.scheduler.tab(a.id)
        val origin=TransferPolicy.origin(p.authOptions.uri)
        if(host==null || tab==null || origin==null || owner.scheduler.selectedId!=a.id || !owner.scheduler.foreground || p.authOptions.flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.PROXY != 0) return unsupported(a,p)
        val epoch=tab.epoch;val result=GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val appearance=(host.activity.application as dev.velocity.browser.VelocityApp).appearance
        val c=appearance.dialogContext(host.activity)
        val fields=android.widget.LinearLayout(c).apply { orientation=android.widget.LinearLayout.VERTICAL }
        val username=EditText(c).apply { hint=c.getString(R.string.auth_username);isSaveEnabled=false;importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;setText(p.authOptions.username.orEmpty()) }
        val password=EditText(c).apply { hint=c.getString(R.string.auth_password);isSaveEnabled=false;importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val onlyPassword=p.authOptions.flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        if(!onlyPassword)fields.addView(username);fields.addView(password)
        var finished=false;var dialog:AlertDialog?=null;lateinit var cancel:()->Unit
        fun finish(ok:Boolean) {
            if(finished)return;finished=true;pending[a]?.remove(cancel);p.setDelegate(null)
            val valid=ok && owner.isCurrent(a.id,a.identity) && owner.scheduler.tab(a.id)?.epoch==epoch && owner.scheduler.selectedId==a.id && owner.scheduler.foreground
            if(!p.isComplete) result.complete(if(valid) { if(onlyPassword)p.confirm(password.text.toString()) else p.confirm(username.text.toString(),password.text.toString()) } else p.dismiss())
            else result.completeExceptionally(IllegalStateException("Authentication prompt already completed"))
            username.text.clear();password.text.clear();dialog?.dismiss();if(pending[a].isNullOrEmpty())a.risk(Protection.PROMPT,false)
        }
        cancel={finish(false)};pending.getOrPut(a){mutableListOf()}.add(cancel);a.risk(Protection.PROMPT,true)
        p.setDelegate(object:GeckoSession.PromptDelegate.PromptInstanceDelegate { override fun onPromptDismiss(prompt:GeckoSession.PromptDelegate.BasePrompt){finish(false)} })
        val message=origin + "\nページ: " + (TransferPolicy.origin(tab.url) ?: "不明") + if(!origin.startsWith("https://") || p.authOptions.level!=GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Level.SECURE) "\n暗号化されていない認証です。" else ""
        dialog=appearance.builder(host.activity).setTitle(R.string.http_auth).setMessage(message).setView(fields)
            .setPositiveButton(R.string.auth_login){_,_->finish(true)}.setNegativeButton(R.string.cancel){_,_->finish(false)}.setOnCancelListener{finish(false)}.create()
        dialog.show();appearance.track(dialog);dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        return result
    }
    fun delegate(a: GeckoSessionAdapter) = object : GeckoSession.PromptDelegate {
        override fun onAlertPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.AlertPrompt) = show(a, p, p.message) { p.dismiss() }
        override fun onButtonPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.ButtonPrompt) = show(a, p, p.message) { p.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE) }
        override fun onTextPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.TextPrompt) = show(a, p, p.message, p.defaultValue.orEmpty()) { p.confirm(it) }
        override fun onBeforeUnloadPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.BeforeUnloadPrompt) = show(a, p, owner.host?.activity?.getString(R.string.close_warning)) { p.confirm(AllowOrDeny.ALLOW) }
        override fun onRepostConfirmPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.RepostConfirmPrompt) = show(a, p, owner.host?.activity?.getString(R.string.close_warning)) { p.confirm(AllowOrDeny.ALLOW) }
        override fun onFilePrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.FilePrompt) = owner.uploads.request(a, p)
        override fun onFolderUploadPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.FolderUploadPrompt) = unsupported(a, p)
        override fun onAuthPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.AuthPrompt) = authenticate(a, p)
        override fun onWebAuthnRelatedOriginPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.WebAuthnRelatedOriginPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val tab=owner.scheduler.tab(a.id)
            val origin=TransferPolicy.origin(p.origin)
            val rp=p.rpId?.let { runCatching { java.net.IDN.toASCII(it,java.net.IDN.USE_STD3_ASCII_RULES) }.getOrNull() }
            if(origin==null || !origin.startsWith("https://") || origin!=TransferPolicy.origin(tab?.url) || rp.isNullOrBlank() || rp.length>255) return GeckoResult.fromValue(p.confirm(AllowOrDeny.DENY))
            return show(a,p,"$origin\nRP ID: $rp\nこの関連サイトのPasskey要求を許可しますか？") { p.confirm(AllowOrDeny.ALLOW) }
        }
        override fun onPopupPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.PopupPrompt) = unsupported(a, p)
    }
}
