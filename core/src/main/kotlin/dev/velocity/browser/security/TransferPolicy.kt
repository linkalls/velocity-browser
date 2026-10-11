package dev.velocity.browser.security

import java.net.URI
import java.net.URLDecoder

/** Untrusted server filenames and picker declarations never become directory paths. */
object TransferPolicy {
    const val MAX_FILES = 16
    const val MAX_UPLOAD_BYTES = 256L * 1024 * 1024
    fun filename(raw: String?, fallback: String = "download.bin"): String {
        val name = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
            .replace(Regex("[<>:\"|?*]"), "_").trim().trim('.').take(120)
        return name.takeUnless { it.isBlank() } ?: fallback
    }
    fun downloadName(disposition: String?, url: String): String {
        val encoded = Regex("(?:^|;)\\s*filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition.orEmpty())?.groupValues?.get(1)
        val decoded = encoded?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"),"UTF-8") }.getOrNull() }
        val plain = Regex("(?:^|;)\\s*filename=(?:\"([^\"]*)\"|([^;]*))",RegexOption.IGNORE_CASE).find(disposition.orEmpty())?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        return filename(decoded ?: plain ?: runCatching { URI(url).path?.substringAfterLast('/') }.getOrNull())
    }
    fun mimeTypes(types: Array<String>?): List<String> = types.orEmpty().filter { it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/(?:[A-Za-z0-9!#$&^_.+-]+|\\*)")) }.distinct().take(32)
    fun secureWebAuthnContext(uri:String):Boolean {
        val origin=origin(uri) ?: return false
        if(origin.startsWith("https://"))return true
        return runCatching { URI(origin).host.lowercase() in setOf("localhost","127.0.0.1","[::1]","::1") }.getOrDefault(false)
    }
    fun origin(uri: String?): String? = runCatching {
        val u = URI(UrlPolicy.httpUrl(uri ?: return null) ?: return null)
        val scheme=u.scheme.lowercase();val rawHost=u.host.lowercase()
        val host = if (rawHost.contains(':') && !rawHost.startsWith('[')) "[$rawHost]" else rawHost
        val port=if(u.port == -1 || (scheme=="https" && u.port==443) || (scheme=="http" && u.port==80)) "" else ":${u.port}"
        "$scheme://$host$port"
    }.getOrNull()
}
