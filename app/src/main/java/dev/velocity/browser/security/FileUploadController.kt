package dev.velocity.browser.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import dev.velocity.browser.R
import dev.velocity.browser.engine.GeckoSessionAdapter
import dev.velocity.browser.tabs.AdaptiveTabCoordinator
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Gecko's FilePrompt requires readable local paths; SAF content URIs are staged off the UI thread. */
class FileUploadController(private val context: Context, private val owner: AdaptiveTabCoordinator) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val root = File(context.cacheDir,"selected-files")
    private data class Request(val adapter: GeckoSessionAdapter, val epoch: Long, val prompt: GeckoSession.PromptDelegate.FilePrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>, val canceled: AtomicBoolean = AtomicBoolean(), var code: Int? = null, var finished: Boolean = false)
    private var pending: Request? = null
    private val retained = mutableMapOf<String,MutableList<Pair<File,Long>>>()
    private var retainedBytes=0L
    init { io.execute { root.deleteRecursively();root.mkdirs() } }
    private fun valid(r: Request) = owner.isCurrent(r.adapter.id,r.adapter.identity) && owner.scheduler.tab(r.adapter.id)?.epoch == r.epoch && owner.scheduler.selectedId == r.adapter.id
    fun request(a: GeckoSessionAdapter, p: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val tab = owner.scheduler.tab(a.id)
        if (tab == null || tab.privateMode || tab.readOnly || pending != null || p.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER || owner.scheduler.selectedId != a.id || !owner.scheduler.foreground) {
            owner.notice(if (tab?.privateMode == true) R.string.private_upload_unavailable else R.string.unsupported)
            return GeckoResult.fromValue(p.dismiss())
        }
        val r = Request(a,tab.epoch,p,GeckoResult());pending=r;a.risk(Protection.UPLOAD,true)
        p.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate { override fun onPromptDismiss(prompt: GeckoSession.PromptDelegate.BasePrompt) { finish(r,null) } })
        val types = TransferPolicy.mimeTypes(p.mimeTypes).ifEmpty { p.mimeTypes.orEmpty().mapNotNull { raw -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(raw.removePrefix(".").lowercase()) }.distinct() }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(types.singleOrNull() ?: "*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,p.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
        if (types.size>1) intent.putExtra(Intent.EXTRA_MIME_TYPES,types.toTypedArray())
        r.code = owner.externalActivities.launch(a.id,intent) { data ->
            if (data == null || !valid(r)) { finish(r,null);return@launch }
            val uris = buildList { data.data?.let(::add); data.clipData?.let { clip -> for (n in 0 until minOf(clip.itemCount,TransferPolicy.MAX_FILES+1)) add(clip.getItemAt(n).uri) } }.distinct()
            if (uris.isEmpty() || uris.size>TransferPolicy.MAX_FILES || uris.any { it.scheme!="content" } || (p.type == GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE && uris.size!=1)) { finish(r,null);return@launch }
            stage(r,uris)
        }
        return r.result
    }
    private fun stage(r: Request, uris: List<Uri>) {
        val budget=TransferPolicy.MAX_UPLOAD_BYTES-retainedBytes
        io.execute {
            val directory=File(root,UUID.randomUUID().toString());var count=0L
            val files=runCatching { uris.mapIndexed { n,uri ->
                if (r.canceled.get()) error("canceled")
                val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null }
                val file=File(File(directory,n.toString()).apply { mkdirs() },TransferPolicy.filename(name,"attachment.bin"))
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { out ->
                    val buffer=ByteArray(16384)
                    while(true) { if(r.canceled.get()) error("canceled");val read=input.read(buffer);if(read<0)break;count+=read;if(count>budget)error("too large");out.write(buffer,0,read) }
                } } ?: error("unreadable")
                file
            } }.getOrNull()
            main.post {
                if (files==null || r.finished || !valid(r)) { io.execute { directory.deleteRecursively() };if(!r.finished) { owner.notice(R.string.upload_failed);finish(r,null) } }
                else { retained.getOrPut(r.adapter.id) { mutableListOf() }.add(directory to count);retainedBytes+=count;finish(r,files.map(Uri::fromFile).toTypedArray()) }
            }
        }
    }
    private fun finish(r: Request, uris: Array<Uri>?) {
        if(r.finished)return;r.finished=true;r.canceled.set(true);if(pending===r)pending=null
        r.prompt.setDelegate(null)
        val answer=if(uris!=null && valid(r)) runCatching { r.prompt.confirm(context,uris) }.getOrNull() else null
        if(!r.prompt.isComplete || answer!=null) r.result.complete(answer ?: r.prompt.dismiss())
        else r.result.completeExceptionally(IllegalStateException("File prompt already completed"))
        owner.externalActivities.cancel(r.code)
        if (retained[r.adapter.id].isNullOrEmpty()) r.adapter.risk(Protection.UPLOAD,false)
    }
    fun cancelPendingFor(id: String) { pending?.takeIf { it.adapter.id==id }?.let { finish(it,null) } }
    fun documentEnded(id: String) {
        cancelPendingFor(id);retained.remove(id)?.let { directories -> retainedBytes-=directories.sumOf { it.second };io.execute { directories.forEach { it.first.deleteRecursively() } } }
        owner.scheduler.tab(id)?.identity?.let { owner.dispatch(dev.velocity.browser.tabs.Event.RiskChanged(id,it,Protection.UPLOAD,false)) }
    }
}
