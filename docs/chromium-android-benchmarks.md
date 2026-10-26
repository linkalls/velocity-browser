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

- Three trials per browser: `adb shell am force-stop`, then `am start -W` for
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
