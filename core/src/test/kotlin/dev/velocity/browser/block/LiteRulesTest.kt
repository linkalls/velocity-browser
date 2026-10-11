package dev.velocity.browser.block
import org.junit.Assert.*
import org.junit.Test
class LiteRulesTest {
    @Test fun acceptsOnlyImplementedSubset() {
        val r = LiteRules.parse("! comment\n||ads.example^\n@@||safe.example^\nsite.example##.ad-slot\n||ads.example^\n||track.example^\$third-party\nsite.example#%#alert(1)\nsite.example##body\nsite.example##.ad-slot{color:red}")
        assertEquals(3, r.accepted); assertEquals(4, r.skipped)
    }
    @Test fun rejectsOversizeAndNull() {
        assertThrows(IllegalArgumentException::class.java) { LiteRules.parse("x".repeat(LiteRules.MAX_BYTES+1)) }
        assertThrows(IllegalArgumentException::class.java) { LiteRules.parse("\u0000") }
    }
    @Test fun originsNormalizeDefaultPortsButDoNotMergeSites() {
        assertEquals("http://example.com", LiteRules.origin("HTTP://EXAMPLE.COM:80/path?x=1"))
        assertEquals("https://sub.example.com:8443", LiteRules.origin("https://Sub.Example.COM:8443/path"))
        assertNull(LiteRules.origin("javascript:alert(1)"))
    }
    @Test fun versionMustBeExplicit() {
        assertEquals("202610101515", LiteRules.remoteVersion("!Last modified: Sat, 10 Oct 2026 15:15:08 GMT\n||ads.example^"))
        assertEquals("202610110606", LiteRules.remoteVersion("! Version: 202610110606\n||ads.example^"))
        assertThrows(IllegalStateException::class.java) { LiteRules.remoteVersion("<html>error</html>") }
    }
}
