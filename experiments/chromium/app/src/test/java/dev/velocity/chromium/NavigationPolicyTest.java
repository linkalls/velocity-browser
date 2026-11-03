package dev.velocity.chromium;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationPolicyTest {
    @Test public void acceptsHttpAndHttpsOnly() {
        assertEquals("https://news.yahoo.co.jp/", NavigationPolicy.parse("https://news.yahoo.co.jp/"));
        assertEquals("https://example.com/", NavigationPolicy.parse("example.com/"));
        assertEquals("http://localhost:8080/", NavigationPolicy.parse("http://localhost:8080/"));
        assertNull(NavigationPolicy.parse("javascript:alert(1)"));
        assertNull(NavigationPolicy.parse("file:///sdcard/test"));
        assertNull(NavigationPolicy.parse("data:text/html,test"));
        assertNull(NavigationPolicy.parse("content://media/test"));
        assertNull(NavigationPolicy.parse("https://user:pass@example.com"));
        assertNull(NavigationPolicy.parse(""));
    }
    @Test public void searchesWordsInsteadOfLoadingArbitrarySchemes() {
        assertEquals("https://www.google.com/search?q=hello+world",
                     NavigationPolicy.parse("hello world"));
        assertNull(NavigationPolicy.parse("mailto:someone@example.com"));
    }
}
