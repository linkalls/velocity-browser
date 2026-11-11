package dev.velocity.browser.diagnostics

import android.app.Activity
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import dev.velocity.browser.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil

/**
 * Opt-in developer-only scroll measurements for real websites.
 *
 * Two DISTINCT proxies are recorded:
 * 1. Gecko compositor scroll callback gaps (not displayed content FPS).
 * 2. Android Window/HWUI frame durations (not SurfaceView/Gecko content frames).
 *
 * No JS injection, touch synthesis, URLs, search terms, DOM, cookies or private
 * tab data are collected. Explicit SAF export is the only disk write.
 */
object SiteScrollBenchmark {
    data class Preset(val label: String, val url: String)

    val presets = listOf(
        Preset("Yahoo!ニュース", "https://news.yahoo.co.jp/"),
        Preset("Google検索", "https://www.google.com/search?q=android"),
        Preset("Wikipedia", "https://ja.wikipedia.org/wiki/Android"),
        Preset("Reddit", "https://www.reddit.com/"),
        Preset("YouTube", "https://www.youtube.com/"),
        Preset("Amazon.co.jp", "https://www.amazon.co.jp/")
    )

    private const val LIMIT_MS = 180_000L
    private const val MAX_EVENTS = 60_000
    private const val FRAME_ASSOCIATION_MS = 350L
    private const val MAX_USER_GAP_MS = 250.0

    private data class Sample(
        val elapsedMs: Long,
        val kind: String,
        val user: Int,
        val yCss: Float = Float.NaN,
        val zoom: Float = Float.NaN,
        val gapMs: Double = Double.NaN,
        val frameMs: Double = Double.NaN,
        val droppedReports: Int = 0
    )

    private class Run(
        val site: String,
        val tabId: String,
        val identity: Long,
        val epoch: Long,
        val blocker: String,
        val cosmeticCollapse: Boolean,
        val hz: Float
    ) {
        val startedWallMs = System.currentTimeMillis()
        val startedUptimeMs = SystemClock.uptimeMillis()
        val cpuStartMs = Process.getElapsedCpuTime()
        val pssStartKb = Debug.getPss()
        val samples = ArrayList<Sample>(4096)
        var lastUserMs = -1L
        var lastUserZoom = Float.NaN
        var truncatedEvents = 0
        var droppedWindowReports = 0L
        var endedUptimeMs = startedUptimeMs
        var cpuEndMs = cpuStartMs
        var pssEndKb = pssStartKb
        var endReason = "running"

        fun append(sample: Sample) {
            if (samples.size < MAX_EVENTS) samples.add(sample) else truncatedEvents++
        }
    }

    private val lock = Any()
    private var running: Run? = null
    private var completed: Run? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var measuredWindow: Window? = null
    private var worker: HandlerThread? = null

    val active: Boolean get() = synchronized(lock) { running != null }
    val canExport: Boolean get() = synchronized(lock) { completed != null }

    /** These labels are deliberately coarse and never include the actual URL. */
    fun siteLabel(url: String): String {
        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        return when {
            host == "news.yahoo.co.jp" -> "yahoo_news"
            host == "google.com" || host.endsWith(".google.com") ||
                host == "google.co.jp" || host.endsWith(".google.co.jp") -> "google_search"
            host == "ja.wikipedia.org" || host == "wikipedia.org" -> "wikipedia"
            host == "reddit.com" || host.endsWith(".reddit.com") -> "reddit"
            host == "youtube.com" || host.endsWith(".youtube.com") -> "youtube"
            host == "amazon.co.jp" || host.endsWith(".amazon.co.jp") -> "amazon_jp"
            else -> "other_site"
        }
    }

    /** Only a user-initiated action starts a run, on the selected normal tab. */
    fun start(
        activity: Activity,
        tabId: String,
        identity: Long,
        epoch: Long,
        url: String,
        blocker: String,
        cosmeticCollapse: Boolean
    ): Boolean {
        if (!BuildConfig.DEBUG || !url.startsWith("https://") && !url.startsWith("http://")) return false
        @Suppress("DEPRECATION")
        val hz = activity.windowManager.defaultDisplay.refreshRate.coerceAtLeast(1f)
        val run = Run(siteLabel(url), tabId, identity, epoch, blocker, cosmeticCollapse, hz)
        synchronized(lock) {
            if (running != null) return false
            // A new run intentionally replaces the previous unsaved result.
            completed = null
            running = run
        }
        val metricsWorker = HandlerThread("velocity-scroll-metrics").apply { start() }
        val metricsListener = Window.OnFrameMetricsAvailableListener { _, frame, dropCount ->
            val now = SystemClock.uptimeMillis()
            val duration = frame.getMetric(FrameMetrics.TOTAL_DURATION)
            val firstDraw = frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
            synchronized(lock) {
                val current = running
                if (current === run && !firstDraw) {
                    current.droppedWindowReports += dropCount.toLong()
                    // Window frames are included only near actual Gecko USER scroll callbacks.
                    if (duration >= 0 && current.lastUserMs >= 0 &&
                        now - current.lastUserMs in 0L..FRAME_ASSOCIATION_MS
                    ) {
                        current.append(Sample(now - current.startedUptimeMs, "android_window", -1,
                            frameMs = duration / 1_000_000.0, droppedReports = dropCount))
                    }
                }
            }
        }
        try {
            activity.window.addOnFrameMetricsAvailableListener(metricsListener, Handler(metricsWorker.looper))
        } catch (_: Exception) {
            metricsWorker.quitSafely()
            synchronized(lock) { if (running === run) running = null }
            return false
        }
        synchronized(lock) {
            measuredWindow = activity.window
            listener = metricsListener
            worker = metricsWorker
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (synchronized(lock) { running === run }) stop("time_limit")
        }, LIMIT_MS)
        return true
    }

    /** Called only for the selected, currently foreground Gecko session. */
    fun onScroll(
        tabId: String,
        identity: Long,
        epoch: Long,
        user: Boolean,
        yCss: Float,
        zoom: Float
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            val run = running ?: return
            if (tabId != run.tabId || identity != run.identity || epoch != run.epoch) return
            val gap = if (user && run.lastUserMs >= 0 &&
                zoom == run.lastUserZoom && now > run.lastUserMs
            ) (now - run.lastUserMs).toDouble().takeIf { it <= MAX_USER_GAP_MS }
                ?: Double.NaN else Double.NaN
            run.append(Sample(now - run.startedUptimeMs, "gecko_scroll", if (user) 1 else 0,
                yCss, zoom, gapMs = gap))
            // A quiet interval, source change or pinch zoom cannot be interpreted as dropped frames.
            run.lastUserMs = if (user) now else -1L
            run.lastUserZoom = if (user) zoom else Float.NaN
        }
    }

    /** Navigating, switching tabs, leaving the app or entering private mode ends the sample. */
    fun onScope(tabId: String?, identity: Long?, epoch: Long, privateMode: Boolean) {
        if (!BuildConfig.DEBUG) return
        val mismatch = synchronized(lock) {
            running?.let {
                privateMode || tabId != it.tabId || identity != it.identity || epoch != it.epoch
            } ?: false
        }
        if (mismatch) stop(if (privateMode) "private_tab" else "tab_or_navigation")
        if (privateMode) synchronized(lock) { completed = null }
    }

    /** Freezes a bounded run; no file is written until the user chooses Export. */
    fun stop(reason: String = "manual") {
        if (!BuildConfig.DEBUG) return
        val oldWindow: Window?
        val oldListener: Window.OnFrameMetricsAvailableListener?
        val oldWorker: HandlerThread?
        synchronized(lock) {
            val run = running ?: return
            run.endedUptimeMs = SystemClock.uptimeMillis()
            run.cpuEndMs = Process.getElapsedCpuTime()
            run.pssEndKb = Debug.getPss()
            run.endReason = reason
            completed = run
            running = null
            oldWindow = measuredWindow
            oldListener = listener
            oldWorker = worker
            measuredWindow = null
            listener = null
            worker = null
        }
        if (oldWindow != null && oldListener != null) {
            runCatching { oldWindow.removeOnFrameMetricsAvailableListener(oldListener) }
        }
        oldWorker?.quitSafely()
    }

    fun discard() {
        stop("discarded")
        synchronized(lock) { completed = null }
    }

    fun status(): String = synchronized(lock) {
        val r = running ?: completed ?: return@synchronized "停止中・データなし"
        val state = if (running === r) "記録中" else "終了（${r.endReason}）"
        "$state · ${r.site} · ${r.hz}Hz（報告値）· ${r.samples.size}件"
    }

    private fun percentile(values: List<Double>, fraction: Double): Any {
        if (values.isEmpty()) return JSONObject.NULL
        val sorted = values.sorted()
        return sorted[ceil((sorted.size - 1) * fraction).toInt()]
    }

    /**
     * Explicit user export only. ZIP holds machine-readable summary JSON and raw CSV.
     * Both omit URL, query, tab identity, form data, cookies, screenshots and page text.
     */
    fun exportZip(): ByteArray? {
        if (!BuildConfig.DEBUG) return null
        val r = synchronized(lock) { completed } ?: return null
        val scrollGaps = r.samples.filter { it.kind == "gecko_scroll" && it.gapMs.isFinite() }
            .map { it.gapMs }
        val windowFrames = r.samples.filter { it.kind == "android_window" && it.frameMs.isFinite() }
            .map { it.frameMs }
        val budgetMs = 1000.0 / r.hz
        val summary = JSONObject().apply {
            put("schema", "velocity-scroll-performance-lab-v1")
            put("build_type", "debug")
            put("device_model", android.os.Build.MODEL)
            put("site_label", r.site)
            put("site_url_exported", false)
            put("refresh_hz_reported", r.hz.toDouble())
            put("expected_frame_budget_ms", budgetMs)
            put("blocker_mode", r.blocker)
            put("cosmetic_collapse", r.cosmeticCollapse)
            put("started_epoch_ms", r.startedWallMs)
            put("duration_ms", r.endedUptimeMs - r.startedUptimeMs)
            put("stop_reason", r.endReason)
            put("event_count", r.samples.size)
            put("truncated_events", r.truncatedEvents)
            put("gecko_scroll_gaps_count", scrollGaps.size)
            put("gecko_gap_p50_ms", percentile(scrollGaps, 0.50))
            put("gecko_gap_p95_ms", percentile(scrollGaps, 0.95))
            put("gecko_gap_p99_ms", percentile(scrollGaps, 0.99))
            put("gecko_gaps_over_1_5_budget", scrollGaps.count { it > budgetMs * 1.5 })
            put("android_window_frame_count", windowFrames.size)
            put("android_window_p50_ms", percentile(windowFrames, 0.50))
            put("android_window_p95_ms", percentile(windowFrames, 0.95))
            put("android_window_p99_ms", percentile(windowFrames, 0.99))
            put("android_window_frames_over_budget", windowFrames.count { it > budgetMs })
            put("dropped_window_metric_reports", r.droppedWindowReports)
            put("app_process_cpu_ms", (r.cpuEndMs - r.cpuStartMs).coerceAtLeast(0L))
            put("app_process_pss_start_kb", r.pssStartKb)
            put("app_process_pss_end_kb", r.pssEndKb)
            put("content_fps_measured", false)
            put("optical_jank_measured", false)
            put("gecko_child_process_cpu_pss_measured", false)
        }
        val csv = StringWriter().apply {
            write("elapsed_ms,kind,is_user,y_css,zoom,scroll_callback_gap_ms,android_window_total_ms,window_reports_dropped\n")
            for (e in r.samples) {
                fun Float.safe() = if (isFinite()) toString() else ""
                fun Double.safe() = if (isFinite()) toString() else ""
                write("${e.elapsedMs},${e.kind},${e.user},${e.yCss.safe()},${e.zoom.safe()},${e.gapMs.safe()},${e.frameMs.safe()},${e.droppedReports}\n")
            }
        }.toString()
        val readme = """
            Velocity Scroll Performance Lab (Debug-only, opt-in)
            Site: ${r.site}; actual URL is deliberately NOT recorded.
            All timestamps in events.csv are milliseconds since local run start.

            gecko_scroll: compositor callback timings, NOT displayed content FPS.
            android_window: Android Window/HWUI frame durations near USER scroll samples;
              these do NOT cover all Gecko SurfaceView compositor frames.
            Long callback intervals can also represent natural gesture pauses, pinch,
              asynchronous delivery or sparse callbacks. Do not call them dropped frames.
            CPU and PSS values refer ONLY to the Android application process.
            Refresh Hz is a device-reported value at run start, not guaranteed optical Hz.
            Missing native frame records MUST NOT be interpreted as zero jank.
            For real content frame cadence use Perfetto FrameTimeline + Gecko profiler +
              synchronized physical/optical evidence on the same device.
            No automated scrolling is performed; scroll naturally on the chosen site.
            No cookies, URLs, queries, page contents or private-tab events are exported.
        """.trimIndent()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun add(name: String, data: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("summary.json", summary.toString(2))
            add("events.csv", csv)
            add("README.txt", readme)
        }
        return bytes.toByteArray()
    }
}
