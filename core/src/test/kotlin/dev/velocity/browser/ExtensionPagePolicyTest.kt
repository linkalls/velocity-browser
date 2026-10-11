package dev.velocity.browser
import dev.velocity.browser.security.UrlPolicy
import org.junit.Assert.*
import org.junit.Test
class ExtensionPagePolicyTest {
    @Test fun extensionPagesRequireExactTrustedOrigin() {
        val base = "moz-extension://trusted-uuid/"
        assertTrue(UrlPolicy.extensionPage("${base}pages/options.html", base))
        for (url in listOf("moz-extension://different/pages/options.html", "moz-extension://trusted-uuid.evil/pages/options.html", "moz-extension://user@trusted-uuid/options.html", "https://trusted-uuid/options.html", "moz-extension://trusted-uuid:123/options.html", "${base}../outside")) assertFalse(url, UrlPolicy.extensionPage(url, base))
    }
}
