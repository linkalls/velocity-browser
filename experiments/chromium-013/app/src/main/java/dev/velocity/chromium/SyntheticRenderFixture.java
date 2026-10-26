package dev.velocity.chromium;

/**
 * Deterministic, zero-network engine exercise used only for emulator smoke.
 *
 * This page changes its title with JavaScript after DOMContentLoaded, proving
 * that the renderer executed JS and parsed the body. The callback is not a
 * visual paint, FCP, or Speedometer result.
 */
public final class SyntheticRenderFixture {
    public static final String READY_TITLE = "VelocityCEFRenderSmoke2026";

    private SyntheticRenderFixture() {}

    public static String html() {
        return "<!doctype html><html><head>"
            + "<meta charset='utf-8'><title>CEF loading fixture</title>"
            + "<script>document.addEventListener('DOMContentLoaded',function(){"
            + "var node=document.getElementById('smoke');"
            + "if(node&&node.textContent==='CEF RENDER SMOKE OK'){"
            + "document.title='" + READY_TITLE + "';}"
            + "});</script></head>"
            + "<body><h1 id='smoke'>CEF RENDER SMOKE OK</h1></body></html>";
    }
}
