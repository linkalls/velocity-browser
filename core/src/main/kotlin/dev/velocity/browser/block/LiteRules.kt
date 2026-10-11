package dev.velocity.browser.block

/** 実装した部分集合だけを受理する。遠隔JavaScript・正規表現・scriptletは受理しない。 */
object LiteRules {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_RULES = 20_000
    private val domain = Regex("(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
    private val network = Regex("(@@)?\\|\\|([^\\^]+)\\^")
    private val selectors = setOf(".ad-slot", ".ad-banner", ".advertisement", ".velocity-ad", "[data-velocity-ad]", "[id^=\"bnc_ad_\"]")
    data class Parsed(val text: String, val accepted: Int, val skipped: Int)
    fun parse(text: String): Parsed {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "リスト上限2MiB" }
        require(!text.contains('\u0000')) { "NULは禁止" }
        val accepted = linkedSetOf<String>(); var skipped = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line == "[Adblock Plus 1.1]") continue
            val net = network.matchEntire(line)
            val css = line.split("##", limit = 2)
            val valid = when {
                net != null -> domain.matches(net.groupValues[2])
                css.size == 2 -> domain.matches(css[0]) && css[1] in selectors
                else -> false
            }
            if (valid) { accepted += line; require(accepted.size <= MAX_RULES) { "規則上限20000" } }
            else skipped++
        }
        return Parsed(accepted.joinToString("\n"), accepted.size, skipped)
    }
    fun origin(raw: String): String? = dev.velocity.browser.security.UrlPolicy.httpUrl(raw)?.let {
        val u = java.net.URI(it); val scheme = u.scheme.lowercase()
        val port = if (u.port == -1 || (scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)) "" else ":${u.port}"
        "$scheme://${u.host.lowercase()}$port"
    }
    fun remoteVersion(text: String): String {
        Regex("(?m)^! Version: (\\d{12})\\s*$").find(text)?.let { return it.groupValues[1] }
        val stamp = Regex("(?m)^!Last modified: (.+)$").find(text)?.groupValues?.get(1)?.trim()
            ?: error("更新リストに配信側version/更新日時がありません")
        val date = java.time.ZonedDateTime.parse(stamp, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        return date.withZoneSameInstant(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm", java.util.Locale.ROOT))
    }
}
