package dev.velocity.browser
import dev.velocity.browser.security.TransferPolicy
import org.junit.Assert.*
import org.junit.Test
class TransferPolicyTest {
    @Test fun filenameDoesNotEscapeDirectoryOrKeepControls() {
        assertEquals("report.pdf",TransferPolicy.filename("../../report.pdf"))
        assertEquals("a.txt",TransferPolicy.filename("C:\\fake\\a.txt"))
        assertEquals("evil.txt",TransferPolicy.filename("\u202eevil\n.txt"))
        assertEquals("download.bin",TransferPolicy.filename(".."))
        assertTrue(TransferPolicy.filename("x".repeat(200)).length<=120)
    }
    @Test fun contentDispositionUnicodeAndLiteralPlusArePreserved() {
        assertEquals("日本+.txt",TransferPolicy.downloadName("attachment; filename*=UTF-8''%E6%97%A5%E6%9C%AC+.txt","https://example.test/a"))
        assertEquals("a.pdf",TransferPolicy.downloadName("attachment; filename=\"../a.pdf\"","https://example.test/b"))
        assertEquals("b",TransferPolicy.downloadName(null,"https://example.test/b?token=secret"))
        assertEquals("a.txt",TransferPolicy.downloadName("attachment; filename*=UTF-8''%ZZ; filename=\"a.txt\"","https://example.test/b"))
    }
    @Test fun pickerMimeDeclarationsRejectInvalidTypesAndDuplicates() {
        assertEquals(listOf("image/*","application/pdf"),TransferPolicy.mimeTypes(arrayOf("image/*","image/*","application/pdf",".pdf","text/plain\n", "bad")))
    }
    @Test fun webauthnBridgeAcceptsHttpsAndExactLoopbackSecureContextsOnly() {
        assertTrue(TransferPolicy.secureWebAuthnContext("https://example.test/"))
        assertTrue(TransferPolicy.secureWebAuthnContext("http://localhost:8080/"))
        assertTrue(TransferPolicy.secureWebAuthnContext("http://127.0.0.1:1234/"))
        assertFalse(TransferPolicy.secureWebAuthnContext("http://localhost.evil.test/"))
        assertFalse(TransferPolicy.secureWebAuthnContext("http://example.test/"))
    }
    @Test fun authenticationOriginIsCanonicalAndDropsPathsNotCredentials() {
        assertEquals("https://example.test",TransferPolicy.origin("HTTPS://EXAMPLE.test:443/private?secret=x"))
        assertEquals("http://localhost:8080",TransferPolicy.origin("http://localhost:8080/path"))
        assertNull(TransferPolicy.origin("https://user:pass@example.test/"))
        assertNull(TransferPolicy.origin("https://example.test:99999/"))
        assertNull(TransferPolicy.origin("javascript:alert(1)"))
    }
}
