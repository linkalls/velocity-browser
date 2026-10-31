package dev.velocity.chromium;

/** Read-only page timings for two public pages in disposable benchmark builds. */
public final class PageTimingProbe {
    private static final String PREFIX = "VelocityExternalTiming2026:";
    private PageTimingProbe() {}

    public static boolean allows(String url) {
        return "https://www.wikipedia.org/".equals(url)
            || "https://developer.mozilla.org/en-US/docs/Web/JavaScript".equals(url);
    }

    public static String script() {
        return "(function(){if(!['https://www.wikipedia.org','https://developer.mozilla.org']"
            + ".includes(location.origin)||!document.body||document.body.innerText.length<100)return;"
            + "var attempts=0;function report(){var f=performance.getEntriesByType('paint')"
            + ".find(function(e){return e.name==='first-contentful-paint'});"
            + "var n=performance.getEntriesByType('navigation')[0];"
            + "if(!f||!n||!n.domContentLoadedEventEnd){if(++attempts<120)requestAnimationFrame(report);return;}"
            + "var old=document.title;document.title='" + PREFIX + "'+Math.round(f.startTime)+':'"
            + "+Math.round(n.domContentLoadedEventEnd)+':'+Math.round(performance.now());"
            + "setTimeout(function(){document.title=old;},200);}"
            + "requestAnimationFrame(function(){requestAnimationFrame(report);});})();";
    }

    public static int[] parse(String title) {
        if (title == null || !title.startsWith(PREFIX)) return null;
        String[] parts = title.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 3) return null;
        int[] timings = new int[3];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[0-9]{1,6}")) return null;
            timings[i] = Integer.parseInt(parts[i]);
            if (timings[i] <= 0 || timings[i] > 60000) return null;
        }
        if (timings[2] < timings[0] || timings[2] < timings[1]) return null;
        return timings;
    }
}
