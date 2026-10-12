# Velocity 0.5 — scroll and native UI validation

## Baseline and scope

Latest main checked on 2026-10-12: `16be2395f1ea863144bd695012c56b60af1ab125`;
clean worktree, no open issues, open PR #5 inspected. Work lives on
`codex/scroll-ui-overhaul`, [PR #6](https://github.com/linkalls/velocity-browser/pull/6).
Spec: [supplied implementation specification](implementation-spec.md). Android Views,
GeckoView 157.0.20261005135250, Gradle 9.6.0, compile 37.1 / build tools 36.0.0,
Java 17; no new application dependency, no Compose/WebView/TextureView.

## P0 / P1 implementation mapping

| Scope | Files | Result |
|---|---|---|
| P0 geometry | core/ui/ToolbarMotion.kt, BrowserChromeView.kt | Static positive occlusion versus dynamic negative displacement; px rounding; max-height cache reset; top engine parent translation |
| P0 state/events | ToolbarMotion.kt, GeckoSessionAdapter.kt, BrowserHost, MainActivity | Current/target separation, duplicate suppression, 24dp distance + short time hysteresis, compositor arbitration, pin/defer, UI lock / tab reset |
| P0 cosmetic | velocity-block/cosmetic.js | Reuse PR #5's bounded 4ms/12-element idle slices and deterministic tests; preserve quiet period and safe rules |
| P1 tokens/controls | colors.xml, BrowserPalette, DesignTokens, VelocityComponents, BrowserIconButton, ThemeController | Mint light/dark; AA text contrast checked on background/surface/subtle/selected; shared ripple/radii, sheets/insets, dialogs/system bars/cursors |
| P1 home/address | BrowserChromeView, MainActivity shortcut management | Native weighted grid, 12 max, add/delete/reorder; process-RAM tab drafts; domain display, full URL editing/copy, gesture isolation, visible load-stop, private marker |
| P1 tabs | TabSwitcherView | Recycling list/grid kept; alternative favicon, selected background, mode counts, empty state, per-row 48dp close/menu, pin/protected close, normal-only Undo |
| P1 menu | BrowserMenuSheet, MainActivity | Dedicated bottom action sheet, forward/reload/share/new quick actions, tab/page/browser sections; disable unavailable actions; dismiss before next route |
| P1 settings | SettingsView, MainActivity | Shared search/row/button/header, category cards, live switches, zero-result state, shortcut/diagnostic entry; same keys and listeners |
| QA/distribution | V04UiIntegrationTest, android.yml, capture-baseline-ui.sh, overhaul-profile.yml | Reject partial/skipped runs; separate APK-only, screenshot/test and measurement artifacts |

Light secondary text is `#586860` instead of suggested `#5D6D65`, because the
latter is only 4.357:1 on the selected background. Shared contrast tests require
4.5:1 for primary, secondary, accent, danger, private and on-accent text.

## Commands / results

| Check | Result |
|---|---|
| Baseline local core/app/lint | PASS; core 40; app unit NO-SOURCE; lint 0 errors/35 warnings |
| Baseline Actions | [38223687014](https://github.com/linkalls/velocity-browser/actions/runs/38223687014): build/Bun/24 real-Gecko cases PASS |
| Isolated P0 Actions | [38223971269](https://github.com/linkalls/velocity-browser/actions/runs/38223971269): build/Bun/25 real-Gecko cases PASS |
| Core JVM including toolbar | 46 PASS (6 new); not an Android device result |
| Bun frozen install/test/typecheck | PASS: 29 tests including contrast and idle batching |
| Local Debug / AndroidTest / Release / Bundle / benchmark | PASS; final production sources `a77c244`, updated instrumentation `88578f7`; lint 0 errors/38 warnings |
| Final native UI device suite | [38228147415](https://github.com/linkalls/velocity-browser/actions/runs/38228147415) / `88578f7`: all 27 PASS, 0 failures/errors/skips; build/Bun PASS |
| Physical Pixel 7 Pro | NOT_MEASURED |

Executed locally:
```
./gradlew --max-workers=1 :core:test :app:testDebugUnitTest :app:lintDebug
./gradlew --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew --max-workers=1 :app:assembleRelease :app:bundleRelease :app:assembleBenchmark
cd tools/bench-runner
bun install --frozen-lockfile
bun test
bun run typecheck
```
Connected tests run on CI's API36 x86_64 emulator with KVM, with external fixture
annotations explicitly excluded. Excluded Passkey/provider/AdGuard external
fixture scenarios are not counted as passed. Existing non-external upload,
permission/auth/Gecko-activity, protected-close, search/Undo/pin/draft/session and
large-font/rotation tests remain in the suite. New tests check the clipping matrix
with Lite ON/OFF and Animator Scale 0/1, address display/edit, home ordering/disabled share/menu routes,
and capture ten major-screen images. The native search hint/background contrast
is checked after actual Android tint resolution, not only palette constants. Session identity/epoch are asserted.

Initial local failures were environment setup (missing Java 17 / SDK and Java
proxy/CA setup), not baseline regressions. The first P0 CI failed because a test
still reflected the removed hidden boolean; it now waits for actual visible
fraction. The first UI run reported all 27 cases but four failed: settings was below the
menu viewport, HTTP-auth waited for a relocated reload control, and a not-yet-
shown dialog had no decor for the new insets call. These were corrected with an
always-visible settings header, an actual reload menu assertion after the same
loading-state wait, and a public peekDecorView guard. Capture succeeded but
Gradle's target uninstall deleted app-local images; fixture-only files are now
exported before cleanup, and CI requires ten after images. Visual inspection
then found Android's background tint recoloring custom search surfaces; all
three search fields now preserve their token backgrounds, with an actual native
contrast regression assertion. Partial/failed attempts are not reported as a
successful final suite.

## Screenshots

CI captures main and overhaul, five screens × Light/Dark = 20 PNGs. Baseline is
installed first in a disposable emulator under the same generated test key;
current installation upgrades code 7 to 8. Both use the same instrumentation
capture method and localhost content fixture. [Before / after comparison](screenshots.md): 20 actual PNGs, copied from CI and
retained in this repository. Private capture is intentionally omitted; existing
FLAG_SECURE stays enabled for private tabs. Physical Pixel screenshots: NOT_CAPTURED.

## Performance / compatibility

[Investigation and expected geometry matrix](../scroll-jank-investigation.md).
The [completed emulator comparison](emulator-comparison.md) profiles main
`16be2395` against initial overhaul `e5abcd8`, whose exact APK/source hashes are
stored in the artifact. Later address/menu/search polish is outside that run;
final-binary performance is NOT_MEASURED. The engine/cosmetic/toolbar state
implementation is unchanged. It alternates old/new for five rounds in static and
dynamic scenes (20 captures), same API36 x86_64 R8 variant, Kotlin runtime keep
rules, gestures, default Lite, bottom toolbar and native viewport. Traces, raw
CPU/PSS, Activity launch output, display/density/font info and APK SHA256 are
retained. Report p50/p95/p99, jank and >50ms host frames separately from Gecko.
No physical refresh-rate/GPU equivalence, real web-content FPS or overall
optimization win is inferred. Physical CPU/PSS, Gecko profiler, video position
jump/caret/fixed-element/tap alignment and 60/120Hz remain NOT_MEASURED.

Private URLs/forms/native queries are not logged or serialized. Old settings
keys/schema/tab protections are unchanged. There is no new session creation,
reload/detach, recurring settings IO or thumbnail capture in chrome motion.
Normal-only Undo reloads URL and does not recreate JS/form state. Passkey provider,
physical AdGuard behavior, TalkBack/RTL/OEM gestures remain UNVERIFIED.

## APK

Version 0.5.0, code 8, app ID `dev.velocity.browser`; same signing configuration.
Release APK is unsigned; downloadable arm64 benchmark APK is nondebuggable,
profileable and debug-test-key signed, not production-signed. CI debug keys can
vary across runners, so upgrades from a different signing key are not guaranteed.
Local APK [size/hash/signature record](local-apk.json). ZIP integrity, 16KiB
alignment and v2 signature verify PASS; v3 is not enabled by this signing
configuration. The [downloaded CI APK record](ci-apk.json) independently verifies the artifact
contents, APK hash, ZIP integrity, alignment and signature. Final Actions APK
provenance is recorded below.

## Final CI / artifact provenance

- Validated source: `88578f7c46da41d1bc74c12c82b673d5017b540a`; final
  production changes are `a77c244`, followed only by instrumentation/CI changes.
- [Final Actions](https://github.com/linkalls/velocity-browser/actions/runs/38228147415):
  build, Bun and device-smoke all PASS. [PR checks](https://github.com/linkalls/velocity-browser/pull/6/checks).
- [APK-only artifact](https://github.com/linkalls/velocity-browser/actions/runs/38228147415/artifacts/11738270520):
  one `Velocity-Browser-arm64.apk`, no source archive.
- [UI validation artifact](https://github.com/linkalls/velocity-browser/actions/runs/38228147415/artifacts/11737147838):
  all test XML and 20 screenshot PNGs. [Verified totals / PNG hashes](ci-results.json).
- [Measurement artifact](https://github.com/linkalls/velocity-browser/actions/runs/38224987096/artifacts/11736329250):
  20 full traces with hashes, CPU/PSS/host frames, same-condition metadata.
- Source commits: P0 `dc6f1ac` / test selector `3e91fa3`, UI `073fb5a`,
  menu/insets `31ae41e`, capture export `3438a2d`, native search contrast
  `c995d2e`, native popup tokens `a77c244`, animation matrix `88578f7`.

## Observed static fixture geometry

In the same 320×640, density-1 dark fixture capture, main's fixed footer spans
Y=435..479 while its toolbar starts at Y=536, leaving **56px extra space**.
Final overhaul footer spans Y=491..535 and ends at the toolbar start, removing
that extra gap. These positions were read from the unedited PNG's known footer
background color. This is evidence of the clipping baseline defect at rest;
it is not optical proof that real-device pan/flick motion is now smooth.
The baseline Light browser PNG precedes painted Gecko content despite title/
accessibility readiness; it is retained honestly for chrome comparison and is
not used to evaluate web paint or footer geometry. Dark baseline and both final
browser images show the real fixture.

## Remaining acceptance

Pixel 7 Pro 60/120Hz, slow finger tracking/coasting/rapid reversals, caret dragging,
fixed controls and tap alignment under IME, physical TalkBack/RTL/foldables,
Passkey/provider and official AdGuard operation remain UNVERIFIED/NOT_MEASURED.
Host p99/CPU comparison did not establish a performance win. Final-binary
performance is NOT_MEASURED; the measured initial overhaul is explicitly identified.
All available automated checks and distributable APK generation are complete.
