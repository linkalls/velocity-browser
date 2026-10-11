package dev.velocity.browser.reading

import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

/** 匿名GETのコピーだけを扱い、生きているDOM・フォーム・ユーザー入力を読まない。 */
data class ReadingDocument(val title: String, val html: String) {
    val bytes get() = html.toByteArray(Charsets.UTF_8).size
    companion object {
        const val MAX_SOURCE_BYTES = 512 * 1024
        const val MAX_DOCUMENT_BYTES = 128 * 1024
        const val MAX_CACHE_BYTES = 2 * 1024 * 1024
        fun fromResponse(source: ByteArray, contentType: String): ReadingDocument {
            require(source.size <= MAX_SOURCE_BYTES)
            val mime = contentType.substringBefore(';').trim().lowercase()
            require(mime == "text/html" || mime == "text/plain")
            val charset = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                .find(contentType)?.groupValues?.get(1)
            val title: String
            val text: String
            if (mime == "text/html") {
                val doc = Jsoup.parse(ByteArrayInputStream(source), charset, "")
                doc.select("script,style,noscript,form,input,textarea,select,button,iframe,object,embed,audio,video,template,svg,math").remove()
                title = doc.title().take(200)
                text = doc.body().wholeText()
            } else {
                title = "公開テキスト"
                text = source.toString(charset?.let { java.nio.charset.Charset.forName(it) } ?: Charsets.UTF_8)
            }
            // 任意のサイトmarkup/属性/URLを復元HTMLへ残さない。
            fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            val html = "<!doctype html><html><head><meta charset=utf-8><meta http-equiv=Content-Security-Policy content=\"default-src 'none'; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'\"><title>${escape(title)}</title><style>body{font:18px sans-serif;padding:16px;line-height:1.7}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style></head><body><p>閲覧専用の本文コピー</p><pre>${escape(text)}</pre></body></html>"
            require(html.toByteArray().size <= MAX_DOCUMENT_BYTES) { "本文が保持上限を超えています" }
            return ReadingDocument(title, html)
        }
    }
}
