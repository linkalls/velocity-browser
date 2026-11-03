# Automated Android browser benchmarks — actual measurements only

Workflow: `.github/workflows/chromium-browser-bench.yml`

The workflow is triggered by every push that modifies the Cefrium experiment or
its benchmarking harness. It also supports manual `workflow_dispatch`. One
GitHub-hosted x86_64 Android 36 emulator is configured with 4 GiB RAM, 4 CPU
cores, and a reproducible virtual GPU setting. Exactly the same device instance
runs Velocity, Google Chrome, Brave, and Vivaldi sequentially.

## Sources and integrity

| Browser | Source | Installation method |
|---|---|---|
| Velocity | Same commit as the workflow | Build Cefrium 0.13 release-mode APK for x86_64 and sign it with disposable CI key |
| Chrome | Google APIs + Play Store Android system image | Use preinstalled Chrome if available; otherwise `unavailable` |
| Brave | Official `brave/brave-browser` GitHub stable release | `BraveMonox64.apk`, verify official SHA-256 |
| Vivaldi | `vivaldi.com/android/` official Intel 64-bit link | Download its own APK, record SHA-256, no independently authenticated digest |

No APK mirrors. Failed downloads, ABI mismatches, installation failures,
first-run setup blockers and missing packages are recorded as unavailable,
never substituted or treated as score zero. Keep versions and image build
fingerprints in each JSON artifact to compare runs responsibly.

## Metrics currently implemented

- Four interleaved trials per browser: `adb shell am force-stop`, then `am start -W` for
  the resolved launcher. Record Android Activity `TotalTime` and report median.
- Wait exactly five seconds after start, then capture `dumpsys meminfo` for
  that package. Report total PSS in KiB and median MiB where available.
- Save raw trial arrays and environment metadata to `results.json`, a table
  to `summary.md`, and installation provenance (`downloads.json`). Publish
  in GitHub Actions summary and an inspectable Actions artifact.

**Important limitations:** Android Activity startup is **not** navigation
completion, first contentful paint, scroll smoothness, or a Speedometer 3.1
score. The current launch workload uses each browser's own start page, so
first-run prompts and startup behavior differ. PSS includes implementation-
specific allocations; browser services, child processes, and running-system
pressure vary, especially in emulators. Avoid cross-run ranking assertions.

## Follow-up required for full performance comparison

1. Launch the same pinned HTTPS page in each browser after onboarding and
   measure page-ready and first-paint timestamps with a validated, identical
   instrumentation interface for all four engines.
2. Run [Speedometer 3.1](https://browserbench.org/Speedometer3.1/) in a
   controlled 850×650+ viewport, collecting its *reported score* via a
   standard harness instead of confusing Activity `TotalTime` with JS speed.
3. Measure p50/p95/p99 scroll frame times on real hardware, compositor jank,
   memory after 1/5/12 tabs, CPU utilization, energy and battery.
4. Use a dedicated Pixel 7 Pro test device (not a GitHub-hosted virtual
   CPU/GPU) before claiming Brave/Vivaldi/Chrome performance superiority.

## Why missing measurements are explicit

The runner does not install Chrome from unverified APK sites. If the requested
emulator image doesn't include Chrome, the result is `unavailable` with an
explicit reason. This is a truthful partial comparison and a signal to provision
a verified Chrome installation or install an official, version-pinned image.

## Per-phase Velocity diagnostics

From the version with `CEFStartupTiming` logging, each Velocity trial also
records numeric-only milliseconds spent constructing the UI, validating the
renderer pool, parsing bundled blocklists and creating the initial Cefrium
Surface-backed browser. Each phase is stored in the JSON and reported as an
optional median in the summary. No URLs, login data or private browsing
content is logged. These are **not page-paint timestamps**.

## Release-mode fairness

Starting with the `release` benchmark upgrade, Velocity is measured as a
**non-debuggable, non-minified release APK** for x86_64. An ephemeral
benchmark-only signature is attached after Gradle assembly because AGP
intentionally leaves release unsigned without configured production secrets.
The disposable key is never used for arm64 signed previews. Old benchmark
figures from the **debug** build cannot be used as a true release-vs-release
comparison or directly compared as a performance regression.


## APK download cache

Each benchmark resolves the current official Brave stable release tag and
Vivaldi's official Intel64 APK URL. A SHA-256 of those two identifiers keys
the Actions cache, and cached APK bytes are hashed again before installation.
Brave is checked against its official GitHub release digest. Vivaldi's cache
uses its previously recorded download SHA-256 and requires the same official
versioned URL; this is integrity of a prior HTTPS download, **not** an
independently authenticated Vivaldi vendor digest. The benchmark still records
versions and download provenance, and it refuses third-party APK mirrors.


## R8 build experiment

Benchmarks with the velocityMinify=true Gradle property enable Android
R8 Java/Dex shrinking and shrinkResources on the Cefrium Preview release.
Default fixed-signing Preview releases do NOT set that flag; this
keeps existing upgrades stable until optimized builds pass compilation,
API 36/37 startup, navigation, private-context and device checks.
The benchmark JSON explicitly labels the R8 variant. Never claim that
old debug-mode baseline values measure the R8 optimization directly.

## Native readiness verification

The benchmark must not report a fast Velocity Activity if Chromium itself
never created its native Surface. Each Velocity sample now captures the
Cefrium startup log, the Surface-ready marker, the surviving process PID
after settling, and nonmissing PSS. The benchmark workflow fails when
any trial omits one of these required native-readiness observations.
The Surface marker confirms creation and initial navigation, not first paint.

## Native offline start page experiment

The experiment previously navigated to `https://news.yahoo.co.jp/` on cold
start, unlike the built-in start pages of comparison browsers. Velocity now
opens a native, network-free new-tab page (`about:blank` underneath) with
shortcuts that navigate only after a user action. The SDK itself may still
perform background network traffic (for example component metadata), so
this does **not** claim that the entire browser makes zero connections.

Cefrium 0.13.0's `CefriumInitProvider` calls `Cefrium.initialize()` before
any Activity. Upstream's v0.13.0 code comments explicitly require early
initialization for the Chromium UI looper and native browser. We are not
bypassing provider setup or weakening renderer sandboxing to game `am start -W`.
The ~4-second provider path remains a separate performance challenge. Never
interpret a fast local home overlay as faster web page first paint.

## Cefrium 0.14.0 SDK upgrade trial

0.14.0 is published in Maven Central and remains Chromium 156.0.8078.17.
It fixes the Android 17 native-only child renderer launch path and moves
persistent profiles out of the app cache directory, with a one-time migration.
The SDK also flushes pending profile storage when the last Activity stops.
These correctness changes motivate testing the newer release, but do not
establish any speed improvement by themselves. The workspace retains its
historical chromium-013 path until final migration and reorganization.
Measure this version with the same emulator, release/R8 settings, repetitions
and workload as 0.13 before making comparative performance claims.

## Exploratory Android Runtime AOT test

After the shared four-browser cold-start test, the same Velocity package is
compiled by Android Runtime in speed mode and measured in three extra trials,
saved under the distinct art-speed directory. This tests whether ahead-of-time
optimization of app DEX improves startup. It does not change the release APK,
nor the CEF native library, nor measure web-first-paint. Comparing scores from
before and after is NOT a randomized A/B test because warmed CPU caches,
device heat, storage and trial order may bias results. Never cite this as a
browser-wide performance win without device validation.


## Balanced execution-order experiment

Earlier runs measured Velocity first, then Chrome, Brave and Vivaldi. This
introduces startup order and emulator cache bias. The revised methodology
performs one unmeasured warmup launch for each installed browser, then rotates
the order across four rounds. When all four are available, each browser runs
once in every execution slot (first, second, third, fourth). All four packages
are force-stopped before each trial. JSON retains trial order, iteration and
order position. These measurements are better balanced, but still show
Android Activity startup rather than interactive webpage first paint.


## ART verify/speed/speed/verify crossover

The earlier Android Runtime speed-only trial ran at the *end* of the entire
comparison and reached 727.5 ms median on one emulator run. That result was
heavily confounded by time/order/cache warming: the last ordinary Velocity
startup in that same run had already fallen to 1034 ms.

The new exploratory experiment applies ART verify, speed, speed, then verify
compilation on the identical signed x86_64 Velocity APK, measuring each block
with three fresh force-stopped Activity launches and an unmeasured warmup.
This ABBA design probes whether ART optimization has an effect that persists
when reversed. It is **not** perfectly randomized, cannot separate all
temperature/filesystem-cache effects, and says nothing about competing
browsers' ART profiles or first-page paint. No standalone speed-mode
measurement should ever be presented as a general browser victory.

## Why distributions must be shown

On run 40326545991 the rotated Velocity startup samples were 5931, 2552,
1427 and 786 ms. The fastest trial alone did not mean the browser reached
competitor performance consistently; the 7.5x spread indicates substantial
warmup/cache effects. Subsequent reports expose min/median/max, max/min and
the unmeasured per-browser warmup launch separately. None of these metrics
represent first paint. A force-stop does not drop page cache or simulate
a fresh application install.

## Page-navigation persistence I/O experiment

The tabbed UI previously serialized all regular tabs and called
SharedPreferences.apply() on every URL callback and every title callback.
Cefrium can deliver multiple callbacks for redirects or dynamic page titles.
The new UI coalesces only these frequent callbacks over 350 ms and skips a
write altogether when the serialized normal-tab snapshot is unchanged.
Explicit tab creation/closing, address navigation and Activity.onStop remain
immediate save points. Private tab URLs and titles are never serialized.
This aims to reduce main-thread JSON serialization and redundant background
preference writes during page loads; no measured improvement in first paint or
frame jank is asserted before a controlled device benchmark.

## CI build reuse for Android 16 / Android 17

The Android emulator smoke workflow used to re-run a full Cefrium x86_64
Gradle build in **both** the API 36 and API 37 matrix jobs, even though both
needed the same APK bytes. The build job now creates exactly one x86_64
diagnostic APK (after keeping the separate arm64 build), verifies the native
libcef.so ABI, and uploads it as a temporary Actions artifact. Both emulator
jobs download that identical APK instead of duplicating the entire build.
The smoke tests still install and run on different OS images independently.
This is CI throughput optimization, **not evidence of better app runtime**.


## Synthetic DOM and JavaScript readiness in emulator

Android 16 and Android 17 smoke jobs now load a local data HTML fixture in
the real Cefrium renderer. The document starts with a generic loading
title. An inline script waits for DOMContentLoaded, verifies an expected H1,
then changes the title to a unique success marker. The Java title callback
records elapsed milliseconds from loadHtml until the DOM/JS signal.
CI requires the marker and numeric timing in logcat; native Surface
creation alone is insufficient. No network or page-to-native JS bridge.

This is a local fixture's DOM/JS completion timestamp, NOT first contentful
paint, Speedometer, overall web loading speed, or UI smoothness. Engine
startup timing remains a separate instrument.


## View attachment vs posted startup (experimental)

Cefrium startup currently runs from root.post(), after Android attaches the
first view hierarchy. The delay between building the native UI and starting
Cefrium was often 0.7–1.0 seconds even after the renderer manifest checks
became cheap. New debug-APK smoke-only code permits an alternative start at
root.onViewAttachedToWindow. Normal and signed release browsing retain the
safe root.post() path.

After standard smoke tests, the emulator runs a post/attach/attach/post
sequence across force-stopped processes. Both strategies must instantiate
a native Surface, execute JavaScript after DOMContentLoaded on a local HTML
fixture, and retain a running renderer host process. Per-trial phase metrics
and JS completion are retained. This is an optional experiment, not
production behavior or evidence of faster real-page paint.


## Optional Chromium Paint Timing signal for a synthetic document

The local DOM/JS smoke fixture also registers a PerformanceObserver with
type=paint and buffered=true. If the Chromium renderer reports the
first-contentful-paint entry for this data document, the fixture sends only
its integer duration via its page title (no page-to-native JS bridge).
The Android title callback logs both the browser-provided relative paint
time and the wall-clock delay to observing the callback. The DOM/JS success
marker remains the required correctness check; FCP is optional until the
SDK reliably exposes paint entries on Android 16 and 17. A missing metric is
reported as unavailable, **never zero**.

This paint metric applies to a tiny inline HTML page; it is not the FCP of
real websites or a valid cross-browser rendering benchmark.

## Opt-in fast attach start for signed Preview builds

The A/B trials in run 40333059586 recorded both strategies on Android 16:
posted startup 604 ms median view-attach wait versus early attach 48 ms;
native Surface readiness 1145 ms versus 604 ms. Both modes produced real
identical smoke-page screenshots. This is not webpage first paint.

Android 17 supplied one valid post and one valid attach trial, but two
additional trials had no application logcat, and API 37 screenshot capture
was unavailable. Hence the early strategy is **not a default** on any OS.
In the Chromium Preview browser menu, an informed tester can explicitly
toggle "高速起動（実験・次回から）". It applies next Activity launch and
preserves native initialization, renderer isolation and normal browsing.
The launcher diagnostic Activity runs in its own process and now includes
"高速起動の実験モードを解除" so a test user can recover even if the
Chromium engine fails to launch. The experiment setting is application
private, and the imported Gecko main app is unaffected. No claim of Pixel
hardware improvement has yet been substantiated.

## Direct fast-attach Velocity versus Brave, Chrome and Vivaldi

The comparison workflow builds a **disposable-signed, x86_64, R8-minified
release APK** with `-PvelocityBenchmarkFastAttach=true`. The Gradle build
permits this flag only for x86_64 minified binaries **without** permanent
Preview signing secrets. Default builds and permanently signed arm64 Preview
remain in the conservative posted startup mode unless the user explicitly
opts in from the Preview menu. The experimental build-time resource switches
on `onViewAttachedToWindow` startup in the comparison APK only.

Each Velocity benchmark sample parses the runtime log for
`CEFStartupStrategy strategy=attach`, in addition to checking native Surface,
process liveness, phase timing and post-settle PSS. The validation step fails
if the actual strategy differs; an APK name or build flag alone is not proof.

The four-browser emulator comparison uses the **same** `adb shell am start -W`
`TotalTime` metric and a rotating sample order on the same image. Thus the
reported Activity-start numbers can be compared directly *for that metric*.
They still do not compare the same webpage or measure first contentful paint,
JavaScript throughput, battery efficiency, and real Pixel hardware.

## Experimental JNI library mmap versus extraction

The separate manual native-packaging workflow builds two x86_64 R8 release
APKs from identical source, both with fast attach, both signed by the same
disposable CI key. The legacy build extracts native libraries; the mmap build
sets jniLibs.useLegacyPackaging=false to store libcef.so page-aligned in
the APK. Both must run a real Cefrium Surface and execute a local JS/DOM
fixture. A failed or incompatible mode is reported unavailable, not faster.

An ABBA legacy/mmap/mmap/legacy crossover tests cold Activity and provider
times with two warm-then-force-stopped samples per block on one Android 16
emulator. Reinstall, page cache and temperature effects remain confounders.
The permanent-key arm64 Preview always stays on legacy native packaging.
This experiment never disables renderer sandboxing, browser security updates
or native initialization. Replicate positive results on Android 17 and Pixel
hardware before changing the release default.

## Results: JNI mmap was NOT a speed win (run 40341410313)

The feature-branch experimental workflow succeeded on Android 16. Both
ZIP-DEFLATED/extracted and ZIP-STORED/direct-mapped libcef.so APKs rendered
Chromium content and passed DOM+JS execution checks. Both used the same
fast-attach startup and disposable signing identity.

| ABBA block | Mode | Activity start median | Provider median | Settled PSS |
|---:|---|---:|---:|---:|
| 1 | Legacy extraction | 2658.5 ms | 932.5 ms | 105.1 MiB |
| 2 | APK mmap | 4182.5 ms | 2165.5 ms | 106.7 MiB |
| 3 | APK mmap | 2094.0 ms | 610.0 ms | 138.5 MiB |
| 4 | Legacy extraction | 1711.0 ms | 450.5 ms | 108.2 MiB |

The per-mode median of block medians was 2184.8 ms for legacy vs 3138.2 ms
for mmap. This small, order-confounded test **does not support** changing
the default APK packaging. Keep the tested legacy extraction mode for real
Preview builds. Native mapping is still an optional isolated experiment.

The Maven Central 0.14.0 AAR contains a 238,619,136-byte x86_64 libcef.so.
readelf inspection found no .debug* or .symtab sections: this binary is
already stripped, so blindly applying strip is not a responsible startup
optimization. The .text section alone occupies approximately 183 MiB.
Across these eight trials the provider-phase and Activity startup times were
highly correlated (Pearson r approx 0.983), partly because the provider is
by definition included in the latter. The large between-launch variability
remains the real blocker to claiming a repeatable 1400-ms start. Follow-up
work requires native CPU/page-fault and storage traces, preferably under
repeatable device thermal/cache conditions, and ultimately native-engine
PGO/link-order work if appropriate; do not simply suppress engine init to
make the Activity metric appear faster.


## Native CPU, page-fault and Perfetto investigation

After JNI packaging's negative result, the key unknown is the variable
synchronous Cefrium initialization inside CefriumInitProvider. The main
process Application captures best-effort read-only /proc/self/stat
(minor/major faults), /proc/self/io (physical disk read/write bytes),
process CPU time and monotonic wall time *before* the SDK provider runs.
The Activity captures these again at onCreate and after native Surface
creation. The CEFStartupResource log exposes deltas for provider/activity/
total, using -1 for missing metrics (never interpreted as zero). Numeric
data contains no URLs, cookies, profile paths, credentials or page content.
The benchmark reports per-trial values and median CPU/wall ratios, faults
and physical read bytes. The values are whole-process, not thread-exclusive;
low CPU/wall alone cannot prove I/O is responsible.

Android 16 and 17 emulator smoke jobs also attempt a bounded 14-second
Perfetto trace with scheduler, CPU frequency and Activity/Window/GPU/View
events and app Trace spans. The async span from Application.attachBaseContext
to Activity.onCreate includes CefriumInitProvider initialization. Other
spans mark renderer service verification, rule parse and first Surface
creation. All measurements use an offline, synthetic DOM+JavaScript page
and no authenticated or private browser data. The trace may include other
system process names, so the artifact is not an anonymous recording.
Missing procfs counters and rejected Perfetto sessions are reported as
unavailable; no need for production root or a disabled sandbox.

Distinguish true cold native starts from warmed and cached starts. The
next optimization should follow evidence of where native initialization
is running on CPU versus blocked on storage/CPU scheduling, not merely
make a placeholder splash screen appear earlier.


## Main-thread CPU accounting in the startup phases

The next instrumentation version records the UI/main thread's CPU time using
`SystemClock.currentThreadTimeMillis()` alongside the whole process's
`Process.getElapsedCpuTime()` snapshots at Application attachment, Activity
creation, and native Surface readiness. The extra `*_thread_cpu_ms` fields
are never treated as zero when absent; reporting displays the ratio of
main-thread CPU to elapsed wall time separately from the existing whole-process
CPU/wall ratio (which can exceed 1 with background workers).

Both capture sites run on the main thread. A small main-thread CPU/wall ratio
suggests the provider waited or was descheduled, but it **does not** prove
native-library I/O was responsible; Perfetto/simpleperf is still needed for
attribution. The new metrics are diagnostic only and do not shorten startup.

## Bounded experimental prefetch of executable ELF pages

A controlled, separate workflow builds two disposable-signed x86_64 R8
release APKs with identical Cefrium/Chromium engine settings, native ELF
extraction mode and fast-attach startup. Both share the same temporary
signing identity; only a test-only integer resource differs:
NativeCodePrefetch disabled (0) versus enabled (16 MiB). The regular
arm64 signed Preview ALWAYS receives zero and never starts prefetch.

The prefetch variant starts a daemon worker during the main-process
Application.attachBaseContext, before CefriumInitProvider initializes
Chromium. It maps an intentionally bounded 16-MiB read-only window
beginning 48 MiB into the official 0.14 x86_64 executable ELF and calls
MappedByteBuffer.load(). This may cause the Linux kernel to fault/cache
some of those pages before CEF's main-thread initialization uses them.
The window is deliberately specific to the tested x86_64 SDK, not a
portable choice for ARM or future CEF engine link orders. The test never
maps private profile files, retains a live writer or pins pages in memory.
Failures are logged and normal engine initialization remains possible.

The ABBA test alternates OFF, prefetch16, prefetch16, OFF, with two
force-stopped Activity launch samples and a real DOM+JavaScript smoke
check per block. Runtime logs must prove both the requested window and
successful advice completion on **every** prefetch trial, otherwise the
result is invalid. The same samples include provider major/minor faults,
CPU/wall time and PSS. Order effects, CPU contention, page-cache reuse
and native-library layouts limit generalizability. An apparent win here
is NOT an automatic production feature or a Brave performance win.
Replicate under truly cold device conditions and check physical Pixel
power/memory and Android 17 correctness before considering a default.

## Buffered file-cache prefetch experiment (October 27, 2026)

The completed native mmap-based 16-MiB prefetch ABBA run
[40431157838](https://github.com/linkalls/velocity-browser/actions/runs/40431157838)
reported OFF block median 1617 ms and mmap prefetch median 1598.5 ms
in four two-sample blocks: no meaningful demonstrated benefit. The mmap
variant's settled PSS was roughly 10 MiB higher, which is undesirable
on devices with limited RAM. Another four-browser run at the same head
[40431157775](https://github.com/linkalls/velocity-browser/actions/runs/40431157775)
was markedly slower than Brave; emulator cache/host noise makes isolated
startup claims particularly unsafe.

A follow-up experiment keeps exactly the same 48-MiB ELF offset and
16-MiB maximum hint, but adds a buffered sequential read variant using
one reusable 64-KiB buffer. This should warm *file* page cache without
faulting another mapped window into the process, although it can still
consume I/O bandwidth, CPU time and global page-cache memory. The
head-to-head workflow now builds three unsigned, x86_64-only disposable
APK variants (OFF, mmap16, read16), signs all with the same temporary
key, and measures them on one emulator in the palindromic
OFF/mmap/read/read/mmap/OFF order. Every block checks native Surface,
real offline DOM+JavaScript, actual prefetch mode/status and PSS.
These six blocks still do NOT simulate a fresh installation or prove
first paint. Reject a faster activity timestamp if renderer
correctness, memory or physical-device stability regress.

The regular signed Preview retains zero prefetch and its existing
renderer/OS isolation configuration. No new optimization should be
promoted on the basis of this single emulator experiment alone.

## Controlled cold-file-cache validation

At head fd00dde, the six-block three-way emulator run
[40439065961](https://github.com/linkalls/velocity-browser/actions/runs/40439065961)
reported OFF 2074.5 ms, mmap16 2145.5 ms, and read16 1919.0 ms for
the medians of their two block medians. This nominal 7.5% improvement
for buffered reads is **not causally established**: OFF's first block
was 3283.5 ms, but its last block was only 865.5 ms, a strikingly
strong chronological cache warming trend. Settled PSS was higher for
both prefetch modes. The same-head ordinary four-browser comparison
[40439065976](https://github.com/linkalls/velocity-browser/actions/runs/40439065976)
was Velocity 4099.5 ms versus Brave 1591.5 ms, so there is no Brave
win or reliably met 1400-ms launch target.

A separate second phase now runs the exact same signed experimental
APKs and offline native-Surface/DOM+JS gates under a **rooted AOSP
Android 16 emulator**, not the Play Store signed image used by the
ordinary four-browser comparison. Before each measured launch it
force-stops the package, executes sync and requires successful
drop_caches=3 on the emulator kernel. Denied root access, a read-only
kernel setting, or any failed cache reset fails the controlled result;
it never silently substitutes a warm trial. The warmed and
controlled-cold results have separate artifacts and are not compared
as equivalent hardware configurations. This operation is **never**
attempted in production, the user's Pixel, or ordinary CI browser
comparison. AOSP image choice follows Android's official distinction
between release-signed Play Store images and rootable development
images.

Even with dropped Linux page cache, the AOSP experiment is not a
factory-reset or real-device cold start: app installation, ART
compilation, global OS state and temperature are uncontrolled. Use
repeated physical-device validation before promoting any performance
optimization.

## Rooted AOSP cold-cache page-hint results and smaller hint trial

[CI run 40479251384](https://github.com/linkalls/velocity-browser/actions/runs/40479251384)
completed all six palindrome blocks, including the rooted Android 16 AOSP
`sync; drop_caches=3` check **before each measured Activity start**. All
three variants passed native-Surface/DOM+JS smoke, runtime-mode validation and
PSS collection. Each mode appeared twice with two launches per block.

| Mode | Median of block medians (ms) | Approx. settled PSS (MiB) |
|---|---:|---:|
| OFF | 1113.25 | 109.0–109.3 |
| mmap16 | 1065.5 | 120.2–120.3 |
| read16 | 1071.5 | 113.4–113.5 |

These are **not** results from the 4-browser comparison. The 40–48-ms
apparent reduction is small and not replicated on physical devices; mmap's
~11-MiB extra PSS argues against enabling it in the Preview. The
uncached counterpart still suffers major provider page faults.

The next isolated experiment compares OFF, mmap16, read8 and read16 in
eight symmetric blocks on the same emulator, now with three launches per
block for the cold-cache experiment. The same executable ELF offset and
limited x86_64 benchmark-only configuration are retained. The 8-MiB
variant is a hypothesis, **not** an established optimization. Never switch
the arm64 permanent-key Preview automatically based on these results.

## Cefrium 0.16 migration (2026-10-28)

The experiment now pins the Gradle plugin and SDK to 0.16.0. This version
initializes only when first used, disables three background Google requests,
and provides page-cache-sensitive native ELF read-ahead plus arm64 PGO.
These are upstream claims, not independently measured Velocity improvements.
The signed arm64 Preview retains the SDK's read-ahead ON. In the legacy
Velocity native-prefetch four-way benchmark, all APK variants explicitly use
`-PvelocitySdkPrefetch=false`; do not compare those figures with an SDK-ON
Preview as if prefetch were the only varying factor.

Repeat the Android 16/17 full renderer/DOM smoke and cold-cache benchmarks
before publishing any speed or sandbox conclusions. Cefrium NetLog records
request URLs and socket endpoints: do not upload raw NetLog artifacts or
record user/private sessions without explicit opt-in.
