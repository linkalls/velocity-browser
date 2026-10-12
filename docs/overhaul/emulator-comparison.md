# Overhaul / latest main — measured emulator comparison

[Actions 38224987096](https://github.com/linkalls/velocity-browser/actions/runs/38224987096),
[raw sample records / trace hashes](emulator-results.json). Baseline `16be2395`,
initial overhaul `e5abcd8`. Final menu/address/search polish is outside this
measurement run; toolbar geometry/state machine, engine and cosmetic code are
unchanged. Do not present these as measurements of the final binary.

API36 x86_64, R8 benchmark with identical Kotlin/AndroidX instrumentation keep
rules, 320×640, density 160dpi, portrait, 60.000004Hz, font 1.0, default Lite and
bottom auto-hide. Five alternating old/new rounds for static and dynamic localhost
fixtures: 20/20 complete, 0 skipped. Same driver, 15-second trace/CPU windows,
10 alternating 600ms pan gestures. Both APK/commit hashes and display dumps
are stored in the separate measurement artifact. Trace hashes verified locally.

| Build / scene | Runs | Total PSS median MiB | CPU median % of one core | Instrumented Activity TotalTime median ms | Host frames | Host p50 / p95 / p99 ms | Host >50ms frames |
|---|---:|---:|---:|---:|---:|---|---:|
| baseline / static | 5 | 395.02 | 34.08 | 1304 | 424 | 7.49 / 15.51 / 241.59 | 16 |
| baseline / dynamic | 5 | 401.69 | 33.85 | 1316 | 427 | 7.38 / 18.81 / 106.75 | 12 |
| current / static | 5 | 396.99 | 33.81 | 1296 | 436 | 6.93 / 17.91 / 156.99 | 17 |
| current / dynamic | 5 | 400.79 | 35.04 | 1267 | 431 | 7.19 / 18.62 / 147.02 | 19 |

The host percentiles are medians of the five per-run percentiles, not pooled
Gecko frame timings. Host frame/long-frame counts are sums; only the MainActivity
TX layer is used, excluding Bounds/SystemUI layers. Every primary host layer
has data in all five runs. The raw classifier marks every host row as jank
(100%); inspection of current-static-1 found 79/80 with Prediction Error and
one SurfaceFlinger CPU Deadline Missed/App Deadline Missed. These emulator
classifications are not a physical dropped-frame rate or web-content FPS.

Dynamic CPU and host p99 increased; static p99 decreased but p95 increased.
This does not establish an overall performance win or a resolution of the
reported visible page jumps. PSS is similar. No statistical significance or
physical responsiveness claim is made from five emulator runs.

CPU uses a stable process set and excludes short-lived processes; PSS sums
matching Gecko children at a point in time. Activity TotalTime includes the
instrumentation launch and warm runtime/uncertain OS cache; it is not FCP or a
controlled cold-start distribution. Driver overhead is present on both sides.
The host FrameTimeline cannot fully measure Gecko SurfaceView content. Real
Gecko profiling, high-speed video/optical page-position comparison, main-thread
UI/engine scheduling attribution, Pixel 7 Pro, 120Hz, physical gestures/selection
and final-APK CPU/PSS/scroll performance remain NOT_MEASURED.
