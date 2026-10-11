package dev.velocity.browser.download

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.*
import dev.velocity.browser.R
import dev.velocity.browser.engine.GeckoSessionAdapter
import dev.velocity.browser.security.*
import dev.velocity.browser.tabs.AdaptiveTabCoordinator
import dev.velocity.browser.ui.*
import org.mozilla.geckoview.WebResponse
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Consume Gecko's authenticated response exactly once; no refetch, cookies copied, or external auto-open. */
class DownloadController(private val context: Context,private val owner: AdaptiveTabCoordinator) {
    private val io=Executors.newFixedThreadPool(2)
    private val closer=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val jobs=mutableListOf<Job>()
    private var nextId=1
    private var dialog: Dialog?=null
    private var panel: LinearLayout?=null
    private var wanted=false
    private var replacing=false
    enum class State { OFFERED, CHOOSING, SAVING, COMPLETE, CANCELED, FAILED }
    class Job(val id:Int,val adapter:GeckoSessionAdapter,val response:WebResponse,val filename:String,val privateMode:Boolean) {
        var state=State.OFFERED;var bytes=0L;val length=if(response.headers["content-encoding"]==null)response.headers["content-length"]?.toLongOrNull()?.takeIf { it>=0 } else null
        var picker:Int?=null;var destination:Uri?=null
        val canceled=AtomicBoolean();@Volatile var output:OutputStream?=null
        val active get()=state in listOf(State.OFFERED,State.CHOOSING,State.SAVING)
    }
    fun snapshot()=jobs.toList()
    private val appearance get()=(context.applicationContext as dev.velocity.browser.VelocityApp).appearance
    fun offer(a:GeckoSessionAdapter,response:WebResponse) {
        val tab=owner.scheduler.tab(a.id)
        if(!owner.isCurrent(a.id,a.identity) || tab==null || response.body==null || response.statusCode !in 200..299 || jobs.count { it.active }>=2 || TransferPolicy.origin(response.uri)==null) {
            closer.execute { runCatching { response.body?.close() } };owner.notice(R.string.download_failed);return
        }
        jobs.removeAll { !it.active && jobs.size>=40 }
        val job=Job(nextId++,a,response,TransferPolicy.downloadName(response.headers["content-disposition"],response.uri),tab.privateMode)
        jobs+=job;a.risk(Protection.DOWNLOAD,true);show()
    }
    fun show() {
        val activity=owner.host?.activity ?: return
        replacing=true;dialog?.dismiss();replacing=false;wanted=true
        val d=Dialog(appearance.dialogContext(activity));dialog=d
        panel=LinearLayout(d.context).apply { orientation=LinearLayout.VERTICAL;setPadding(context.dp(16),context.dp(16),context.dp(16),context.dp(16)) }
        d.setContentView(ScrollView(d.context).apply { addView(panel) })
        d.setOnDismissListener { if(!replacing) { wanted=false;dialog=null;panel=null } }
        d.show();d.window?.setLayout(-1,-2);appearance.track(d);refresh()
    }
    fun bind() { if(wanted || jobs.any { it.state==State.OFFERED })show() }
    fun dismiss() { wanted=false;dialog?.dismiss() }
    fun unbind() { replacing=true;dialog?.dismiss();dialog=null;panel=null;replacing=false }
    fun refresh() {
        val layout=panel ?: return;val c=layout.context;val p=appearance.palette();layout.removeAllViews();layout.setBackgroundColor(p.surface)
        layout.addView(TextView(c).apply { text=c.getString(R.string.downloads);textSize=20f;setTextColor(p.text) })
        if(jobs.isEmpty())layout.addView(TextView(c).apply { text="ダウンロードはありません";setTextColor(p.muted) })
        jobs.forEach { job ->
            val title=TextView(c).apply { text=job.filename + "\n保存元: " + TransferPolicy.origin(job.response.uri);textSize=16f;setTextColor(p.text) };layout.addView(title)
            layout.addView(TextView(c).apply { setTextColor(p.muted);text=when(job.state) {
                State.OFFERED -> if(job.privateMode) "非公開タブから保存したファイルは端末に残ります。保存先を選ぶ場合だけ書き込みます。" else "保存先を選んでください"
                State.CHOOSING -> "保存先を選択中"
                State.SAVING -> if(job.length!=null) "${job.bytes} / ${job.length} bytes" else "${job.bytes} bytes 保存中"
                State.COMPLETE -> "保存完了 · ${job.bytes} bytes"
                State.CANCELED -> "キャンセル · 未完了ファイルが保存先に残る場合があります"
                State.FAILED -> "保存失敗 · 未完了ファイルが保存先に残る場合があります"
            } })
            if(job.state==State.OFFERED)layout.addView(Button(c).apply { text=c.getString(R.string.choose_destination);contentDescription="${job.filename}の保存先を選ぶ";setOnClickListener { choose(job.id) } })
            if(job.active)layout.addView(Button(c).apply { text=c.getString(R.string.cancel);contentDescription="${job.filename}の保存をキャンセル";setOnClickListener { cancel(job.id) } })
        }
        layout.addView(Button(c).apply { text="閉じる";setOnClickListener { dialog?.dismiss() } })
        dialog?.let { appearance.tintDialog(it);if(jobs.any { j -> j.privateMode })it.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun choose(id:Int) {
        val job=jobs.find { it.id==id && it.state==State.OFFERED } ?: return
        if(!owner.isCurrent(job.adapter.id,job.adapter.identity)) { cancel(id);return }
        job.state=State.CHOOSING;refresh()
        val type=job.response.headers["content-type"]?.substringBefore(';')?.let { TransferPolicy.mimeTypes(arrayOf(it)).singleOrNull() } ?: "application/octet-stream"
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).putExtra(Intent.EXTRA_TITLE,job.filename)
        job.picker=owner.externalActivities.launch(job.adapter.id,intent) { data ->
            val uri=data?.data
            if(job.canceled.get())return@launch
            if(uri?.scheme!="content" || !owner.isCurrent(job.adapter.id,job.adapter.identity)) { cancel(id);return@launch }
            job.destination=uri;job.state=State.SAVING;refresh();save(job,uri)
        }
    }
    private fun save(job:Job,uri:Uri) {
        io.execute {
            var bytes=0L;var lastUpdate=0L
            val success=runCatching {
                job.response.body!!.use { input ->
                    context.contentResolver.openOutputStream(uri,"wt")?.use { out ->
                        job.output=out;val buffer=ByteArray(32768)
                        while(true) {
                            if(job.canceled.get())error("canceled")
                            val n=input.read(buffer);if(n<0)break;bytes+=n
                            if(bytes>8L*1024*1024*1024)error("download size limit")
                            out.write(buffer,0,n)
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(now-lastUpdate>=250) { lastUpdate=now;val progress=bytes;main.post { job.bytes=progress;refresh() } }
                        }
                        out.flush();if(job.length!=null && bytes!=job.length)error("incomplete response")
                    } ?: error("destination unavailable")
                };true
            }.getOrDefault(false)
            job.output=null
            runCatching { job.response.body?.close() }
            if(!success || job.canceled.get()) runCatching { DocumentsContract.deleteDocument(context.contentResolver,uri) }
            main.post { job.bytes=bytes;job.state=if(job.canceled.get())State.CANCELED else if(success)State.COMPLETE else State.FAILED;protect(job.adapter);refresh() }
        }
    }
    fun cancel(id:Int) {
        val job=jobs.find { it.id==id && it.active } ?: return
        job.canceled.set(true);job.state=State.CANCELED;owner.externalActivities.cancel(job.picker)
        closer.execute { runCatching { job.response.body?.close() };runCatching { job.output?.close() } }
        protect(job.adapter);refresh()
    }
    fun cancelFor(a:GeckoSessionAdapter) { jobs.filter { it.active && it.adapter===a }.forEach { cancel(it.id) } }
    private fun protect(a:GeckoSessionAdapter) { a.risk(Protection.DOWNLOAD,jobs.any { it.active && it.adapter===a }) }
}
