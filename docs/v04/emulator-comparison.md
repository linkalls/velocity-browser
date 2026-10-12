# API36 emulator comparison — actual records

[Workflow](https://github.com/linkalls/velocity-browser/actions/runs/38189014222), baseline `8de8e0b` / current application `f019c57`. Five runs per version and scene, alternating version order each round. Full raw records and trace hashes: [emulator-results.json](emulator-results.json).

| Version / scene | Samples | Total child-inclusive PSS median (MiB) | CPU median (% of one core) | Instrumented Activity TotalTime median (ms) | App-attributed FrameTimeline samples |
| --- | ---: | ---: | ---: | ---: | ---: |
| baseline / static | 5 | 396.20 (5/5) | 27.42 (5/5) | 1288.00 (5/5) | 0/5 |
| baseline / dynamic | 5 | 404.59 (5/5) | 30.39 (5/5) | 1268.00 (5/5) | 1/5 |
| current / static | 5 | 397.24 (5/5) | 33.91 (5/5) | 1328.00 (5/5) | 5/5 |
| current / dynamic | 5 | 402.33 (5/5) | 35.72 (5/5) | 1352.00 (5/5) | 5/5 |

These are disposable x86_64 emulator R8 benchmark builds, with identical ABI and shared Kotlin/AndroidX keep overrides. They are not the arm64 release APK or Pixel 7 Pro results. Both run the same UiAutomation driver, whose process overhead is included. CPU is stable-process-set /proc sampling over approximately 15 seconds; it can exceed 100% across cores and excludes short-lived processes. PSS is a point-in-time sum across matching child processes. Each version uses a fresh emulator application profile, not user data.

Activity TotalTime is an instrumented am start -W result. The test process is already initialized, OS page cache is uncontrolled, and this is neither a guaranteed cold-start distribution nor FCP. Do not interpret the difference as ordinary startup improvement.

FrameTimeline filtering selects only application layers/processes. Empty application results remain NOT_MEASURED; SurfaceFlinger/SystemUI rows are not substituted. Capture completion does not prove comparable Gecko frame time, scroll jank, 60/120fps or optimization success. Raw traces may contain unsupported emulator ftrace events. Inspect them on Perfetto before interpreting scheduling.

Pixel 7 Pro pre/post startup, child-process PSS, CPU and jank, full physical accessibility/gesture QA, real Passkey/provider operations and AdGuard XPI compatibility remain UNVERIFIED. ETP/Lite/AdGuard three-way comparison was not performed by this workflow.

Observed CPU medians increased in both scenes; this is not a performance win. PSS medians are close. The new auto-hide animation performs host-UI work, but this study does not isolate causality.

Current MainActivity host frames (median of each run’s percentile, ms):

| Scene | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| static | 7.15 | 21.75 | 390.41 |
| dynamic | 7.29 | 17.79 | 326.93 |

These are host-window FrameTimeline durations, not Gecko surface FPS. Baseline coverage (0/5 static, 1/5 dynamic) is insufficient for a jank comparison. In the inspected current-static-1 trace, all 82 host entries include Prediction Error (some also App Deadline Missed/Buffer Stuffing); the raw classification count is not treated as a measured real-device dropped-frame rate.
