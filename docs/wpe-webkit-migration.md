# Velocity WPE WebKit experiment (2026-10-13)

## Status / scope

This branch adds a **genuine WPE WebKit** engine build, NOT an Android System WebView and NOT a Chromium/GeckoView wrapper.

The original `:app` GeckoView code remains unchanged for a fair, reversible comparison. The separate `:wpe-browser` Android application is an **early migration candidate**, not a feature-complete replacement. This is intentional: much of the existing Gecko-based tab coordinator, dynamic toolbar, permission model, extension controller, Passkey integration, download manager, and history/state saving is coupled to Gecko APIs. Porting them without a safe WPE mapping would create a broken or misleading browser.

* Application ID: `dev.velocity.browser.wpe`, alongside the existing `dev.velocity.browser`
* Maven dependency: `org.wpewebkit.wpeview:wpeview:0.3.3` (**WPE WebKit 2.50.6**)
* Android widget: `org.wpewebkit.wpeview.WPEView`; **never** `android.webkit.WebView`
* Runtime application: `org.wpewebkit.WPEApplication` (required for WPE's native processes)
* Minimum OS: Android 12 / API 31 (the WPE 0.3.3 AAR declares this minimum). Pixel 7 Pro qualifies.
* Native packaging: extracted JNI libraries via Gradle `jniLibs.useLegacyPackaging`, arm64-v8a for first Pixel 7 Pro build
* Basic functions: navigate/search HTTP(S), back, forward, reload, page title/progress, sample pages
* Diagnostics: developer extras are enabled in this **experiment**, not in production

### Critical SDK compatibility distinction

Upstream `wpe-android/main` in autumn 2026 has been refactored to use
`org.wpewebkit.wpeview.WebView`, `WebContext`, `WebViewClient`, and `WebChromeClient`.
**Those classes are NOT the published v0.3.3 artifact API.** WPE 0.3.3 instead
uses `WPEView`, `WPEContext`, `WPEViewClient`, and `WPEChromeClient`.
Do not blindly switch imports to the new classes or assume the October 2026
WPEPlatform/Mali GPU fixes are in the March 2026 Maven artifact.

Official sources:
- https://github.com/Igalia/wpe-android/tree/v0.3.3
- https://github.com/Igalia/wpe-android/blob/main/docs/architecture.md
- https://central.sonatype.com/artifact/org.wpewebkit.wpeview/wpeview
- https://blogs.igalia.com/alex/2026/09/11/wpe-webkit-on-android-now-with-wpeplatform/

## APK

GitHub Actions → **WPE WebKit experimental APK** → latest green run →
artifact `Velocity-WPE-WebKit-arm64-EXPERIMENT`.
The unrelated normal Android workflow continues to build `app` with GeckoView.

Local build with Android SDK 37.1 and Java 17:

```sh
./gradlew --max-workers=1 :wpe-browser:testDebugUnitTest :wpe-browser:assembleDebug
adb install -r wpe-browser/build/outputs/apk/debug/wpe-browser-debug.apk
```

Do **not** uninstall your old Velocity Browser, and do not log in to important
accounts on this prototype. WPE's independent state lives in its own sandbox.

## Compare on Pixel 7 Pro

1. Set Android fixed 60Hz, disable battery saver, keep device temperature and page conditions similar.
2. On Gecko Velocity and WPE Velocity, open **Yahoo!ニュース** with the same URL and allow page load to settle. Compare 20 manual pans/flings in each, alternating versions.
3. Repeat at 120Hz (verify actual display mode via `adb shell dumpsys display`; the configured rate alone doesn't guarantee physical refresh).
4. Repeat on Wikipedia (less dynamic content), Google Search, Reddit.
5. Observe visual reverse jumping vs actual frame drops using synchronized video/Perfetto/Gecko profiling. WPE prototype presently has no integrated FPS logger.

Do not claim WPE is faster until the same-device test controls actual rendering, network/cache, blockers, native toolbar behavior and cold/warm starts. Differences may reflect these features, not engine-only costs.

## Explicit limitations (NOT migrated yet)

No tab manager, adaptive scheduling, Gecko extensions/Lite/adblock, Passkey,
private mode, file chooser, download manager, history persistence, sophisticated
permission prompts, browser-level site isolation assessment, sync, or full
accessibility validation. WPE v0.3.3 is an older embedding engine: verify
browser security updates and public-site support before trusting it for general browsing.
The fixed nonanimated toolbar avoids Velocity's present dynamic
`engineContainer.translationY` and `setVerticalClipping` path.

## Next milestones

1. Verify installed APK loads 6 preset HTTPS sites on Pixel, handles screen rotation and touch scrolling.
2. Compare actual pan/gesture behavior and crash/ANR logs against Gecko and Firefox.
3. Build/package newer WPE `main` WPEPlatform code (new public API) with current WPE 2.54.x and retest Mali/120Hz; its native bootstrap is heavyweight and Linux-specific.
4. Design engine-neutral tab interface and migrate tabs, permissions, privacy isolation, adblock/network hooks, downloads, Passkeys and persistence one subsystem at a time.
5. Security review plus broad compatibility suite before any default-engine replacement.

This branch is intentionally experimental; neither compatibility nor performance wins have yet been demonstrated.
