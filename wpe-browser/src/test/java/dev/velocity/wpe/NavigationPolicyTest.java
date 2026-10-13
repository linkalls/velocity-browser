package dev.velocity.wpe;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationPolicyTest {
    @Test public void acceptsOnlyHttpOrHttps() {
        assertEquals("https://news.yahoo.co.jp/", NavigationPolicy.parse(" https://news.yahoo.co.jp/ "));
        assertEquals("https://example.com/", NavigationPolicy.parse("example.com/"));
        assertEquals("http://localhost:8080/", NavigationPolicy.parse("http://localhost:8080/"));
        assertNull(NavigationPolicy.parse("javascript:alert(1)"));
        assertNull(NavigationPolicy.parse("file:///etc/passwd"));
        assertNull(NavigationPolicy.parse("data:text/html,test"));
        assertNull(NavigationPolicy.parse("content://media/a"));
        assertNull(NavigationPolicy.parse("https://user:pass@example.com/"));
        assertNull(NavigationPolicy.parse(""));
    }
    @Test public void sendsPlainTextToSearchInsteadOfUnsafeUri() {
        assertEquals("https://www.google.com/search?q=hello+world",
            NavigationPolicy.parse("hello world"));
        assertNull(NavigationPolicy.parse("mailto:user@example.com"));
    }
}
