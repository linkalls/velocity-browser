package dev.velocity.browser.preload
import dev.velocity.browser.security.UrlPolicy
import dev.velocity.browser.tabs.AdaptiveConfig
import dev.velocity.browser.util.Clock
import java.net.URI

data class PreconnectConditions(val foreground: Boolean, val privateMode: Boolean, val metered: Boolean,
    val dataSaver: Boolean, val powerSave: Boolean, val lowMemory: Boolean, val userAllowed: Boolean)
class PreconnectPolicy(private val config: AdaptiveConfig, private val clock: Clock) {
    var enabled = config.preconnectEnabled
    private val last = mutableMapOf<String, Long>()
    private var inflight = false
    fun candidate(current: String, next: String, conditions: PreconnectConditions): String? {
        val c = conditions
        if (!enabled || inflight || !c.foreground || c.privateMode || c.metered || c.dataSaver || c.powerSave || c.lowMemory || !c.userAllowed) return null
        val origin = UrlPolicy.origin(current) ?: return null
        if (origin != UrlPolicy.origin(next)) return null
        // v0.1: パスがあるサイトは安全性を証明できない。明示許可のルート→ルートに限定。
        if (listOf(current, next).any { raw -> val u = URI(raw); u.rawPath !in listOf("", "/") || u.rawQuery != null || u.rawFragment != null }) return null
        val now = clock.elapsedRealtimeMs()
        if (last[origin]?.let { now - it < config.preconnectCooldownMs } == true) return null
        if (last.size >= 128 && origin !in last) last.remove(last.minBy { it.value }.key)
        last[origin] = now; inflight = true
        return origin
    }
    /** APIはvoidで接続完了通知なし。次の許可はクールダウン+単一発行ガード後。 */
    fun releaseIssueGuard() { inflight = false }
}
