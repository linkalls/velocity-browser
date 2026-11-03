package dev.velocity.chromium;

import static org.junit.Assert.*;
import org.junit.Test;

public final class CosmeticRulesTest {
    @Test public void onlyStaticSafeSelectorsAreInjected() {
        String js = CosmeticRules.injectScript();
        assertTrue(js.contains("adsbygoogle"));
        assertTrue(js.contains("data-ad-slot"));
        assertTrue(js.contains("createElement('style')"));
        assertFalse(js.contains("window.cefriumQuery"));
        assertFalse(js.contains("fetch("));
        assertFalse(js.contains("innerHTML"));
        assertFalse(js.contains("form,"));
    }

    @Test public void removalTargetsExactlyTheOwnStyle() {
        assertTrue(CosmeticRules.removeScript().contains("velocity-lite-cosmetic-013"));
        assertTrue(CosmeticRules.removeScript().contains("el.remove()"));
    }
}
