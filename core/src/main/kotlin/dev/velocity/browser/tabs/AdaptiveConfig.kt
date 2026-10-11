package dev.velocity.browser.tabs

enum class RestoreFallback { URL_RELOAD }

data class AdaptiveConfig(
    val liveTarget: Int = 6,
    val discardAfterMs: Long = 120_000,
    val preconnectEnabled: Boolean = true,
    val preconnectMaxInflight: Int = 1,
    val preconnectCooldownMs: Long = 30_000,
    val backgroundScanIntervalMs: Long = 15_000,
    val aggressiveMemoryReclaim: Boolean = true,
    val restoreFallback: RestoreFallback = RestoreFallback.URL_RELOAD
) { init { require(liveTarget in 2..32); require(discardAfterMs >= 0); require(preconnectMaxInflight == 1); require(backgroundScanIntervalMs > 0) } }
