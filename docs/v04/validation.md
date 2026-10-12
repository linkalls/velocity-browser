# Velocity Browser 0.4.0 — implementation and validation

Application source: `f019c57a064bd281f0c0c52fca2f33de868f515b`. GeckoView Stable 157 remains the only page engine. Android Views/system fonts only; no new UI library. Existing soft live target, protection states, normal metadata schema and private disk exclusion are preserved.

| Phase | Changed files | Executed validation | Outstanding |
| --- | --- | --- | --- |
| 1 Design | colors.xml, DesignTokens.kt, BrowserIconButton.kt, BrowserPalette/ThemeController consumers | Kotlin compilation; API36 theme regression, final source 24/24 suite | Physical palette/gesture/TalkBack QA |
| 2 Toolbar | BrowserChromeView.kt, MainActivity.kt | Compact geometry/menu/identity test on final application source `f019c57`; no visible six-button/title rows; saved placement preserved | Physical caret and inset synchronization |
| 3 Tabs | TabSwitcherView.kt | Existing 100-logical-tab search/pin/Undo and protected-close tests on final application source `f019c57` | Physical swipe/TalkBack/200% tab manager |
| 4 Settings/Home | SettingsView.kt, HomeInputState.kt, MainActivity.kt, VelocityApp.kt | 500ms timer removed; Bun source contract; event-driven switch/search test; 200% landscape/private home on final application source | Process termination loses RAM-only native drafts; real OEM keyboard behavior |
| 5 Scroll | GeckoSessionAdapter.kt, BrowserHost toolbar callback, BrowserChromeView.kt, tracing/fixture tools | Published 157 public API inspection; real static and dynamic swipes preserve engine identity/epoch on final application source `f019c57`; current emulator host frame-time captured | Pixel 7 Pro Perfetto/CPU/PSS/startup comparison |
| 6 QA | V04UiIntegrationTest.kt, existing updated regression cases, android.yml, optional profile workflow | Local release/instrumentation compilation and lint; final-source API36 emulator run passed all 24 | Pixel 7 Pro physical acceptance and performance optimization unfinished |

## Executed locally

- Core JVM: 40 existing cases, 0 failures/errors in local XML; CI executes core:test. App JVM module has no standalone unit-test cases (its empty task is not a test count).
- Bun 25 tests passed and typecheck passed. Architectural guards reject settings polling and session ownership/reload/JS polling in the toolbar.
- Release and Android-test assembly passed. Latest lint: 0 errors / 35 warnings; deprecated APIs, hardcoded strings/accessibility warnings are not hidden.
- Debug instrumentation compiles; no local emulator/KVM or physical device is attached. Compilation is not a device test result.
- APK verification: v2/v3 test signatures, 16KiB zip alignment and ZIP integrity passed. Native Gecko libraries (13) match the minimal baseline. [Artifact/size record](artifact.json).

## CI history

- `38183793517` / `2a8a679`: build and Bun passed; device 24/4 failures. The menu was too tall and direct show/hide notifications alone did not respond to real scroll direction. Compact menu and the official compositor scroll delegate fixed the routes/direction.
- `38184858147` / `690f1b2`: device 24/1 failure at an immediate menu accessibility query. Explicitly waiting for the real route fixed synchronization; the actual route remains required.
- [38185648287](https://github.com/linkalls/velocity-browser/actions/runs/38185648287) / `b951188`: build, Bun and **24/24 API36 x86_64 real-Gecko device cases passed**, 0 failures/errors/skips.
- `38186653911` / `df1bdfd`: partial 13-case run, 1 reported failure. AndroidRuntime identified NoSuchElementException while snapshotting WeakHashMap dialog keys in Activity.onDestroy. This partial run is not a pass. `f019c57` uses a sequence snapshot holding strong dialog references, and the large-font/rotation test exercises collected dialog keys.
- Final application run [38187030374](https://github.com/linkalls/velocity-browser/actions/runs/38187030374) on `f019c57`: build, Bun and **all 24 device tests passed**, 0 failures/errors/skips. This includes GC/rotation native-draft preservation, real static/dynamic scroll direction and unchanged Gecko identity/epoch.

The later measurement-tool-only commits leave application code identical; normal CI `38188460416` and `38189014283` also passed build, Bun and device-smoke.

## Measurement status

The manual profile workflow builds isolated baseline `8de8e0b` (0.3.1) and current x86_64 R8 benchmark APKs. Its ABI and shared Kotlin/AndroidX runtime keep overrides are identical and confined to that workflow. An optional UiAutomation driver verifies the actual fixture before captures; this driver is excluded from the 24-case smoke suite. Normal APK Actions artifacts remain APK-only; measurement artifacts are separate and explicitly named; triggered manually or by measurement-tool changes.

Preparation failures remain failures: fixture readiness initially failed, then the shrunk target lacked shared Kotlin/AndroidX classes required by instrumentation. Identical measurement-only keep rules address that harness configuration. Run `38187778446` built both APKs and rendered the actual fixture, but Android SELinux prevented Perfetto reading a shell-labelled config file; it yielded no completed comparison. The capture now supplies the config through stdin. Run `38188460428` captured one actual baseline static trace plus CPU/PSS and a successful fixture-driver result, but stopped because ripgrep was not installed on the CI runner. Python now verifies the driver output. Local analysis of that partial trace found no app-attributed FrameTimeline rows; SurfaceFlinger/SystemUI rows are not substituted. [38189014222](https://github.com/linkalls/velocity-browser/actions/runs/38189014222) completed successfully: 20 actual captures, CPU/PSS/Activity TotalTime measured for all 20. Current host FrameTimeline rows exist in all 10 current samples; baseline coverage is only 0/5 static and 1/5 dynamic, so a jank improvement cannot be established. CPU medians increased in both scenes; no performance-win claim is made. [Actual comparison and limitations](emulator-comparison.md), [raw records/hashes](emulator-results.json). Captured traces or instrumentation success alone are not proof of speedup.

Pixel 7 Pro, actual Passkey creation/login/provider behavior, actual AdGuard signed XPI blocking/options/update, TalkBack, OEM system gestures and a fully controlled pre/post release startup/PSS/CPU/jank study are **UNVERIFIED/NOT_MEASURED**. External-fixture tests are excluded, not passed. No 60/120fps or optimization-success claim is made.

## Boundaries

Home native drafts are process-RAM only, partitioned by logical tab ID, retained across rotation and cleared on logical close. A draft-changing confirmation is rejected. No private query is serialized. Process death cannot guarantee recovery of native drafts or Web JS/forms; normal URL/order/title persistence remains the existing guarantee.

Top dynamic-toolbar translation and bottom clipping use public APIs; emulator scrolling/identity tests do not establish caret/fixed-position correctness across all websites/OEMs. CSS ad hiding/network blocking remain active during scrolling; Lite's optional layout inspection retains its existing bounded/idle policy. Undo reloads a URL and cannot recreate a closed page's JS state.

The emulator current host-window per-run p99 medians are 390.41 ms (static) and 326.93 ms (dynamic). Long-tail frames remain; fixture capture is not evidence that the reported stutter is resolved. Baseline application frame data is insufficient to establish a regression or improvement in jank, and this measurement does not isolate animation/startup/engine causes.
