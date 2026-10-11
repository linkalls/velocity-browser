package dev.velocity.browser.security
import java.net.URI
import java.net.URLEncoder

enum class SearchEngine(val prefix: String) { DUCKDUCKGO("https://duckduckgo.com/?q="), GOOGLE("https://www.google.com/search?q=") }
object UrlPolicy {
    /** Geckoの署名済み拡張metadata由来baseに限定。外部Intentからは呼ばない。 */
    fun extensionPage(raw: String, base: String): Boolean = runCatching {
        val url = java.net.URI(raw); val root = java.net.URI(base)
        root.scheme == "moz-extension" && !root.host.isNullOrBlank() && root.rawPath == "/" &&
            url.scheme == root.scheme && url.host == root.host && url.port == -1 && url.rawUserInfo == null &&
            url.rawPath.startsWith("/") && !url.rawPath.contains("..")
    }.getOrDefault(false)

    fun httpUrl(raw: String): String? = runCatching {
        if (raw.any { it.isISOControl() || it.isWhitespace() } || raw.contains('\\')) return null
        val u = URI(raw)
        if (u.scheme?.lowercase() !in setOf("https", "http") || u.host.isNullOrEmpty() || u.rawUserInfo != null || u.port !in -1..65535 || u.port == 0) return null
        u.toASCIIString()
    }.getOrNull()
    fun origin(raw: String): String? = httpUrl(raw)?.let { value ->
        val u = URI(value); if (u.scheme.lowercase() != "https") null else
            "https://${u.host.lowercase()}" + if (u.port != -1 && u.port != 443) ":${u.port}" else ""
    }
    fun input(raw: String, engine: SearchEngine): String? {
        val s = raw.trim(); if (s.isEmpty() || s.any { it.isISOControl() }) return null
        httpUrl(s)?.let { return it }
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(s)) return null
        if (!s.contains(' ') && (s.contains('.') || s.startsWith("localhost"))) return httpUrl("https://$s")
        return engine.prefix + URLEncoder.encode(s, "UTF-8")
    }
}
