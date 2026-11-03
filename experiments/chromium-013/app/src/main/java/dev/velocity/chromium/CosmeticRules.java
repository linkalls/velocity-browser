package dev.velocity.chromium;

/**
 * Strictly static, reviewed element-hiding rule set for top-level documents.
 * Never assemble JavaScript from remote lists, visited URLs or CSS selectors.
 * This is CSS element hiding, not a Gecko extension or page/native messaging.
 */
public final class CosmeticRules {
    private CosmeticRules() {}

    private static final String STYLE_ID = "velocity-lite-cosmetic-013";

    // Deliberately narrow: no generic header/footer/banner classes, no form,
    // input, media, dialog, accessibility controls or forced element removal.
    private static final String CSS =
        ".adsbygoogle," +
        ".ad-slot," +
        ".ad-banner," +
        ".advertisement," +
        "[data-velocity-ad]," +
        "[data-ad-slot]," +
        "[data-ad-unit]," +
        "[id^=\"bnc_ad_\"]" +
        "{display:none!important;}";

    public static String injectScript() {
        // Both id and CSS are hard-coded constants, not dynamically generated.
        return "(function(){" +
            "if(!document.documentElement)return;" +
            "if(document.getElementById('" + STYLE_ID + "'))return;" +
            "var style=document.createElement('style');" +
            "style.id='" + STYLE_ID + "';" +
            "style.textContent='" + CSS.replace("'", "\\'") + "';" +
            "document.documentElement.appendChild(style);" +
            "})();";
    }

    public static String removeScript() {
        return "(function(){var el=document.getElementById('" + STYLE_ID +
            "');if(el)el.remove();})();";
    }
}
