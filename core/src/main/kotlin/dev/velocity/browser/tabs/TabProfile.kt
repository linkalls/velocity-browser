package dev.velocity.browser.tabs

/** Targets guide safe reclamation, never admission or forced closure. */
enum class TabProfile(val target: Int, val idleMs: Long) {
    MEMORY(3, 30_000), BALANCED(6, 120_000), FAST(12, 600_000);
    fun config() = AdaptiveConfig(liveTarget = target, discardAfterMs = idleMs)
}
