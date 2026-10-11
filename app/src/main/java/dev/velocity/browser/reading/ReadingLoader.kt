package dev.velocity.browser.reading

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.velocity.browser.security.UrlPolicy
import org.mozilla.geckoview.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Gecko経由の匿名GET。認証情報・Cookieなし、redirectなし、入力済みDOMへのアクセスなし。 */
class ReadingLoader {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    var busy = false; private set
    @android.annotation.SuppressLint("WrongConstant") // Gecko FetchFlagsのIntDefにflag=trueが欠けるが公開fetch JavaDocはone or moreを明記。
    fun fetch(runtime: GeckoRuntime, url: String, done: (ReadingDocument?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (busy || UrlPolicy.httpUrl(url) == null) { done(null); return }
        busy = true
        var finished = false
        fun finish(doc: ReadingDocument?) {
            if (finished) return
            finished = true; done(doc)
        }
        fun end(doc: ReadingDocument?) { busy = false; finish(doc) }
        // 論理timeout後もnative fetchが終わるまでbusyを保持し、未完了GETを増殖させない。
        main.postDelayed({ finish(null) }, 30000)
        val request = WebRequest.Builder(url).cacheMode(WebRequest.CACHE_MODE_NO_STORE).build()
        GeckoWebExecutor(runtime).fetch(request, GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS or GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS or GeckoWebExecutor.FETCH_FLAGS_PRIVATE).accept({ response ->
            if (response == null) { end(null); return@accept }
            if (finished || response.statusCode != 200 || response.redirected) { runCatching { response.body?.close() }; end(null); return@accept }
            response.setReadTimeoutMillis(5000)
            io.execute {
                val doc = runCatching {
                    val deadline = SystemClock.elapsedRealtime() + 20000
                    val bytes = ByteArrayOutputStream()
                    response.body!!.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            check(SystemClock.elapsedRealtime() < deadline)
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= ReadingDocument.MAX_SOURCE_BYTES)
                            bytes.write(buffer, 0, count)
                        }
                    }
                    val contentType = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: ""
                    ReadingDocument.fromResponse(bytes.toByteArray(), contentType)
                }.getOrNull()
                runCatching { response.body?.close() }
                main.post { end(doc) }
            }
        }, { end(null) })
    }
}
