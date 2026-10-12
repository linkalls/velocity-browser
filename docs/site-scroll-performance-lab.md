# Site Scroll Performance Lab (Debug-only)

## Goal and exact scope

This is an opt-in manual benchmark for **real GeckoView pages**: Yahoo News, Google Search, Wikipedia, Reddit, YouTube, Amazon Japan, and any other ordinary non-private HTTP(S) tab (exported as `other_site`).

The benchmark does **not** inject page JavaScript, synthesize scrolls, rewrite page scroll offsets, or claim displayed Gecko content FPS. Touch/fling gestures are performed by the tester. For repeatable robotic touch input, drive the phone from a separate ADB/UIAutomator test and retain touch/video provenance; synthetic JavaScript `scrollBy()` is not a proxy for real APZ/fling responsiveness.

## How to use

1. Install the **Debug diagnostics APK** on a test device/profile. The release/benchmark APK has no Lab menu.
2. Open **Settings → 詳細・診断 → Webスクロール性能ラボ**.
3. Choose **計測サイトを開く** or browse to any non-private page manually. Let the page settle.
4. Choose **記録を開始**. The settings sheet closes before collecting metrics.
5. Perform normal slow pans, reversals, and flings (suggest 20 gestures in each alternating before/after set). Avoid touching password fields during capture.
6. Open the Lab again and choose **記録を終了** (or leave it to auto-stop after 180 seconds).
7. Choose **計測結果をZIPに保存** and select a location with Android's system document picker.
8. The ZIP contains `summary.json`, `events.csv` and `README.txt`. Nothing is uploaded automatically. A new run replaces the previous unsaved result; **export before starting again**.

## Data dictionary and caveats

* `gecko_scroll` in CSV records Gecko compositor scroll callbacks with a coarse USER/OTHER flag, CSS Y and zoom. `scroll_callback_gap_ms` is generated only for consecutive USER callbacks at the same zoom with no gap >250ms. It is a **callback-cadence proxy**, not measured displayed page FPS and not proof of dropped frames.
* `android_window` records Android Window/HWUI `FrameMetrics.TOTAL_DURATION` near recent USER scroll callbacks. FrameMetrics are **not** a full measurement of the Gecko SurfaceView compositor, and a missing frame record is NOT proof of smoothness. Dropped `OnFrameMetricsAvailableListener` callback reports are tracked separately.
* The frame budget in the JSON is `1000 / reported_refresh_hz`. Device-reported 60/120Hz may differ from actual dynamic refresh behavior. Counts over budget are diagnostic signals, not frame-drop totals.
* CPU elapsed time and PSS are for **the Android app process only**, not all Gecko content/GPU child processes. Do not compare them to total-device or total-app-tree resource metrics.
* No URL, query, HTML, form contents, cookies, page text, image/screenshot or tab identity is exported. Site labels are coarse. Private-mode transitions discard collected results. Recording is disabled in release/benchmark builds and does not persist between app processes.
* A run auto-stops on app background, tab selection/navigation change or after 180 seconds. The sample cap is 60,000 events; `truncated_events` reports overflow.

## Minimal physical comparison matrix

Use the **same Pixel 7 Pro**, OS version, browser build type, pages, display size and blocker mode:

| Case | 60Hz | 120Hz |
|---|---|---|
| Yahoo!ニュース, ETP_ONLY | 20 gestures × 3 alternating sets | 20 gestures × 3 alternating sets |
| Yahoo!ニュース, Lite/collapse OFF | 20 × 3 | 20 × 3 |
| Yahoo!ニュース, Lite/collapse ON | 20 × 3 | 20 × 3 |
| Wikipedia, ETP_ONLY (control) | 20 × 3 | 20 × 3 |

Check real refresh modes during each run with ADB `dumpsys display`. Alternate before/after app versions, not only 60 then 120. Preserve battery/temperature and capture user touch/video timings.

The in-app exported ZIP is **one part** of the evidence. To diagnose the original Yahoo visual jump and actual displayed Gecko content FPS, additionally capture **Perfetto FrameTimeline, Gecko Profiler markers and synchronized optical/touch evidence**. Classify content reversal vs viewport movement vs script/anchoring vs missing displayed frames. Never claim PR #7 fixed Yahoo unless the before symptom is reproduced and the after case passes.

## Acceptance / security

- Existing build, Bun and device smoke checks remain green, including fixed footer taps, form/IME, zoom, private mode, and navigation.
- UI start/stop and system picker export verified on a physical Pixel and a standard emulator.
- Debug lab is absent from non-debug distribution and no diagnostic record is sent over network.
- Run output must be clearly labelled **callback/HWUI proxies, not actual content FPS**.
- Run capture must stop on tab/epoch changes and exclude private pages.
