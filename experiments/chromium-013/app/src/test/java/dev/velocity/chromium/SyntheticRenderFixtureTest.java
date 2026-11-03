package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;

public final class SyntheticRenderFixtureTest {
    @Test public void titleSignalRequiresJavascriptAndDom() {
        String html = SyntheticRenderFixture.html();
        assertTrue(html.contains("DOMContentLoaded"));
        assertTrue(html.contains("getElementById('smoke')"));
        assertTrue(html.contains("document.title='" + SyntheticRenderFixture.READY_TITLE + "'"));
        assertTrue(html.contains("<h1 id='smoke'>CEF RENDER SMOKE OK</h1>"));
        assertFalse(html.contains("<title>" + SyntheticRenderFixture.READY_TITLE + "</title>"));
        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));
        assertFalse(html.contains("cefriumQuery"));
        assertTrue(html.contains("PerformanceObserver"));
        assertTrue(html.contains("first-contentful-paint"));
        assertTrue(html.contains(SyntheticRenderFixture.FCP_TITLE_PREFIX));
        assertTrue(html.contains("buffered:true"));
    }
}
