package dev.velocity.browser
import dev.velocity.browser.preload.*
import dev.velocity.browser.tabs.AdaptiveConfig
import dev.velocity.browser.util.Clock
import org.junit.Assert.*
import org.junit.Test
class PreconnectPolicyTest {
    private var now = 0L
    private val policy = PreconnectPolicy(AdaptiveConfig(), Clock { now })
    private val safe = PreconnectConditions(true, false, false, false, false, false, true)
    @Test fun sameOriginRootOnlyAndOneIssueWithinCooldown() {
        assertEquals("https://example.com", policy.candidate("https://example.com/", "https://example.com:443/", safe))
        now = 30001; assertNull(policy.candidate("https://example.com/", "https://example.com/", safe))
        policy.releaseIssueGuard(); assertNotNull(policy.candidate("https://example.com/", "https://example.com/", safe))
        policy.releaseIssueGuard(); assertNull(policy.candidate("https://example.com/", "https://example.com/", safe))
    }
    @Test fun allGuardsAndDangerousCandidatesDeny() {
        val guards = listOf(safe.copy(foreground=false), safe.copy(privateMode=true), safe.copy(metered=true), safe.copy(dataSaver=true), safe.copy(powerSave=true), safe.copy(lowMemory=true), safe.copy(userAllowed=false))
        guards.forEach { assertNull(policy.candidate("https://example.com/", "https://example.com/", it)) }
        for (url in listOf("http://example.com/", "https://other.com/", "https://example.com/login", "https://example.com/?token=private", "https://example.com/#account", "javascript:alert(1)")) assertNull(policy.candidate("https://example.com/", url, safe))
        policy.enabled = false; assertNull(policy.candidate("https://example.com/", "https://example.com/", safe))
    }
}
