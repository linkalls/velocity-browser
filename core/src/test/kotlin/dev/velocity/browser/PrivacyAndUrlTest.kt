package dev.velocity.browser
import dev.velocity.browser.security.*
import dev.velocity.browser.util.*
import org.junit.Assert.*
import org.junit.Test
class PrivacyAndUrlTest {
    @Test fun rejectsUntrustedSchemesCredentialsAndSpoofing() {
        for (url in listOf("javascript:alert(1)", "intent://x", "file:///x", "content://x", "https://trusted.example@evil.example", "https://example.com:0", "https://example.com\n", "https://example.com\\@evil.com", "https://example.com:99999")) assertNull(url, UrlPolicy.httpUrl(url))
        assertEquals("https://example.com/a", UrlPolicy.input("https://example.com/a", SearchEngine.GOOGLE))
        assertEquals("https://example.com", UrlPolicy.input("example.com", SearchEngine.GOOGLE))
        assertTrue(UrlPolicy.input("検索 語", SearchEngine.DUCKDUCKGO)!!.startsWith("https://duckduckgo.com/?q="))
        assertNull(UrlPolicy.input("javascript:alert(1)", SearchEngine.GOOGLE))
    }
    @Test fun telemetryNeverAcceptsSiteOrUserContent() {
        val metrics = Metrics(Clock { 10 })
        metrics.record("SELECT", 1); metrics.record("FIRST_CONTENT", 2)
        try { metrics.record("https://secret.example/token"); fail("URL accepted") } catch (_: IllegalArgumentException) { }
        assertFalse(metrics.snapshot().toString().contains("secret"))
        repeat(600) { metrics.record("LIVE_COUNT", 2) }; assertEquals(512, metrics.snapshot().size)
    }
    @Test fun softTargetExcessMetricIsAcceptedWithoutUserData() {
        val metrics=Metrics(Clock {10});metrics.record("TARGET_EXCESS",14)
        assertEquals(14L,metrics.snapshot().single().value)
    }
}
