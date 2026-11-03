# Cefrium 0.16.0 measurements — 2026-10-28

Measured commit: `b2a432f7a3217f2fbe6e53299e61f061ffa9f886`.

## Startup and memory

[Completed comparison run 40630648094](https://github.com/linkalls/velocity-browser/actions/runs/40630648094)
contains the JSON, screenshots, APK SHA-256, official competitor provenance and
ART crossover results in its `Velocity-Chrome-Brave-Vivaldi-Android-Benchmark` artifact.

Android 16 x86_64 emulator, release/R8 Velocity with experimental fast attach;
this is not the default signed arm64 Preview configuration. One unmeasured warmup
per browser, four rotated interleaved rounds, all competitors force-stopped before
each trial, package PSS sampled after five seconds. Force-stop restarts the app
process; it does not clear the kernel page cache.

| Browser | Version | Activity median (ms) | Min–max (ms) | Package PSS median (MiB) |
|---|---|---:|---:|---:|
| Velocity | 0.16.0-experiment | 2597.5 | 1078–4220 | 124.6 |
| Chrome | 133.0.6943.137 | 1232.5 | 638–1698 | 89.8 |
| Brave | 1.98.60 | 852.0 | 723–1438 | 151.2 |
| Vivaldi | 8.2.4147.173 | 2123.5 | 1070–2267 | 177.7 |

Screenshot inspection: Velocity displays its offline home; Chrome displays sign-in
onboarding; Brave displays a default-browser chooser over onboarding; Vivaldi
displays its welcome screen. These are different workloads. The table measures
Android Activity launch to these screens, not equivalent ready-to-browse states,
real-page loading, FCP, Speedometer or browser engine superiority. PSS is package
attribution from dumpsys, not guaranteed whole-browser-process-tree memory.

Velocity trials were **4220, 2984, 2211, 1078 ms**. All four created a native
Surface, retained a live process, logged attach strategy and returned PSS.
App-instrumented process-attachment-to-Surface times were **2688, 1672, 987, 420 ms**,
median **1329.5 ms**. This interval excludes earlier Android launch work and is not
rendered web content. The monotonic warmup and large spread prevent a claim of
reproducible 1400 ms cold readiness.

The prior 0.14 run [40481808270](https://github.com/linkalls/velocity-browser/actions/runs/40481808270)
reported 3913 ms Activity median and 113.8 MiB PSS. The current observation is lower
startup time and higher PSS; different CI executions and cache states mean these
numbers do not isolate the effect of the SDK upgrade. A same-device controlled
0.14/0.16 crossover is still needed for an upgrade-performance claim.

The separate ART verify/speed/speed/verify crossover gave per-block Activity
medians of 740/916/1094/794 ms, with combined block medians verify 767 ms versus
speed 1005 ms. It does not support enabling forced ART speed compilation.

## Renderer and synthetic-page checks

[Run 40630652989](https://github.com/linkalls/velocity-browser/actions/runs/40630652989)
passed the arm64/x86_64 APK builds, Java tests, packaged renderer-manifest audit,
and mandatory DOM+JavaScript smoke tests on Android 16 and 17. The screenshot
on Android 16 visibly renders `CEF RENDER SMOKE OK`.

| Emulator | DOM+JS callback (ms) | Synthetic FCP, browser clock (ms) |
|---|---:|---:|
| Android 16 | 321 | 628 |
| Android 17 | 50 | 440 |

These are single samples on a tiny local HTML fixture; they are not total app
startup, real-site FCP or a cross-version performance comparison.

The optional Android 16 post/attach/attach/post experiment completed all trials.
Android 17 had two unavailable trials (empty logs; the first launch command
reported `Broken pipe`). The Android 17 A/B comparison is therefore incomplete,
despite the mandatory smoke test passing. Do not use it to enable fast attach
by default. Physical-device stability, default Preview timing, equivalent-site
FCP/Speedometer, scrolling/power and network-privacy validation remain pending.
