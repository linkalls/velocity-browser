package dev.velocity.browser.util
/** URL、タブID、タイトル、フォーム等を受け取らない内部計測。ネット送信なし。 */
data class Metric(val name: String, val elapsedMs: Long, val value: Long)
class Metrics(private val clock: Clock) {
    private val events = ArrayDeque<Metric>()
    fun record(name: String, value: Long = 0) {
        require(name in setOf("UI_READY", "RUNTIME_CREATED", "FIRST_CONTENT", "SELECT", "BLOCKED", "LIVE_COUNT", "TARGET_EXCESS", "PRECONNECT_ISSUED"))
        if (events.size == 512) events.removeFirst()
        events.addLast(Metric(name, clock.elapsedRealtimeMs(), value))
    }
    fun snapshot(): List<Metric> = events.toList()
}
