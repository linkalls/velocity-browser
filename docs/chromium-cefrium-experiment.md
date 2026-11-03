# Velocity Chromium CEF experiment

This branch adds an independent Chromium engine using **Cefrium 0.9.4**, an experimental Android port of Chromium Embedded Framework (CEF). It does **not** use Android's `android.webkit.WebView`, WPE WebKit or GeckoView. Existing Gecko/WPE Velocity files are left intact.

**Reality check:** Cefrium's own published comparison reports a higher PSS than GeckoView for the tested page (Cefrium 242/263 MB versus GeckoView 180/206 MB on its two test devices), and native `libcef.so` alone is approximately **203 MB uncompressed** on arm64. This branch is a **feasibility/performance experiment**, not an assertion that Chromium will be lighter. Do not merge or distribute as a general-purpose browser without security review.

## Build and install

The repository root Gradle environment continues using Java 17, Gradle wrapper 9.6 etc. Cefrium 0.9.4 instead requires **JDK 25, Gradle 9.7.1, AGP 9.4, compileSdk 37 (SDK 37.1 minor release on this CI runner)**. To avoid breaking the existing Gecko builds, the Chromium experiment is its own Gradle root under `experiments/chromium/`. GitHub Actions supplies the experimental toolchain.

GitHub Actions > **Chromium CEF experiment APK** > `Velocity-Chromium-CEF-arm64-EXPERIMENT` artifact. Install APK from its ZIP. The package is `dev.velocity.browser.chromiumtest` and cannot overwrite the existing `dev.velocity.browser` / `dev.velocity.browser.wpe`.

For local builds with the required Gradle and Android SDK already installed:

```sh
cd experiments/chromium
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Scope

Single Chromium Surface browser, stable fixed toolbar, URL entry, HTTP(S) search, back, forward, reload, six standard real-site presets. No active scrolling instrumentation. Nothing uploaded or logged by the app itself.

### Not yet migrated

Tabs, adaptive tab scheduler, adblock parity and cosmetic collapse, history persistence, downloads/permissions, Passkeys, private mode UI, user data import, safe browsing UX, security threat model, full integration test suite. This is NOT a Gecko drop-in swap. Keep test accounts off it.

## Physical Pixel 7 Pro comparison

1. Confirm **60Hz** or **120Hz** physically; configure Android display settings and check `adb shell dumpsys display`. Dynamic refresh can differ from the setting.
2. Repeat Yahoo News, Wikipedia, Google search for 20 manual pans/flings. Compare same network / cached page state / battery temperature / device resolution / toolbar auto-hide OFF. Alternate app order instead of first doing one app exclusively.
3. Compare with Gecko and WPE. **Do not attribute differences solely to engines** if network filters, site variants or page load times differ.
4. Inspect `adb shell dumpsys meminfo dev.velocity.browser.chromiumtest` plus child processes and use Perfetto FrameTimeline and actual screen video/touch timestamps. A single-process meminfo number is insufficient for CEF's multi-process costs.
5. Record cold start, process count, APK size, PSS total, scroll jitter, visual jumps, crashes and failure rates. Do not claim victory based on CPU/GPU counters that cannot see compositor output.

## Sources and constraints

- https://cefrium.com/quickstart/
- https://cefrium.com/benchmarks/
- https://cefrium.com/docs/com/cefrium/CefriumBrowser.html
- https://cefrium.com/about/

Cefrium is third-party and pre-1.0. Its API, Android behavior, and security updates are not verified by Chromium/Google as a complete browser product. Its native Chromium binary can be hundreds of MB. Source-review and reproducible-build work are future milestones.
