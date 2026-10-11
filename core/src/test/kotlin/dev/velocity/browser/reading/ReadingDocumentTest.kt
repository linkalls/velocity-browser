package dev.velocity.browser.reading

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ReadingDocumentTest {
    @Test fun hostileMarkupAndInteractiveStateAreNeverReplayed() {
        val source = """<title>News &amp; updates</title><p>Article &lt;script&gt; text</p><form><input value=secret><textarea>draft</textarea></form><script>alert(1)</script><video autoplay src=https://evil.test></video><iframe srcdoc='<input>'></iframe><svg onload=alert(1)></svg><a href=javascript:alert(1) onclick=alert(1)>label</a><meta http-equiv=refresh content=0><div contenteditable>plain</div>"""
        val reading = ReadingDocument.fromResponse(source.toByteArray(), "text/html; charset=utf-8")
        val rendered = Jsoup.parse(reading.html)
        assertEquals("News & updates", reading.title)
        assertTrue(rendered.body().text().contains("Article <script> text"))
        assertTrue(rendered.body().text().contains("label"))
        assertFalse(rendered.body().text().contains("draft"))
        assertEquals(0, rendered.select("input,textarea,form,script,video,iframe,svg,a,[contenteditable],[onclick]").size)
        assertEquals(1, rendered.select("meta[http-equiv=Content-Security-Policy]").size)
    }
    @Test fun plainTextIsEscapedAndHasNoNetworkCapabilities() {
        val document = ReadingDocument.fromResponse("<img src=https://evil.test><input>".toByteArray(), "text/plain")
        val rendered = Jsoup.parse(document.html)
        assertEquals(0, rendered.select("img,input").size)
        assertTrue(rendered.body().text().contains("<input>"))
    }
    @Test fun unsafeTypesAndOversizedCopiesAreRejectedRatherThanTruncated() {
        for ((bytes, type) in listOf("%PDF".toByteArray() to "application/pdf", ByteArray(ReadingDocument.MAX_SOURCE_BYTES + 1) to "text/plain", ByteArray(ReadingDocument.MAX_DOCUMENT_BYTES) { 65 } to "text/plain")) {
            try { ReadingDocument.fromResponse(bytes, type); fail("must reject") } catch (_: IllegalArgumentException) { }
        }
    }
}
