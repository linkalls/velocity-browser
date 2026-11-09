# Velocity Browser — Main-Branch Code Audit (Corrected)

**Audit date:** 2026-11-09. **Baseline:** `main` commit `8c9c3d1` (PRs #19, #20, #21 merged). **Method:** static code inspection and previously completed Velocity-only Android emulator CI. **Not performed in this code audit:** Chrome/Vivaldi app runtime; that work was subsequently performed separately for older Chrome 124 and current Vivaldi 8.3, with raw screenshots only ([real browser evidence](10-REAL-ANDROID-UI-OBSERVATIONS.md)). A controlled Chrome 157 comparison, measured interaction timing and a Pixel 7 Pro physical-device test remain unperformed. Do not present source-level findings as measured FPS or real-device UX.

## Source locations

All paths below are relative to the repository root, at the audited commit. Line ranges are approximate anchors and should be refreshed after future edits.

- `experiments/chromium-013/app/src/main/java/dev/velocity/chromium/ChromiumTestActivity.java` (**Activity**)
- `experiments/chromium-013/app/src/main/java/dev/velocity/chromium/BrowserUiLayout.java` (**layout arithmetic**)
- `experiments/chromium-013/app/src/main/java/dev/velocity/chromium/TabRegistry.java` (**tab metadata**)
- `experiments/chromium-013/app/src/main/java/dev/velocity/chromium/TabPreviewStore.java` (**disk + RAM thumbnail cache**)
- `experiments/chromium-013/app/build.gradle.kts` (**target SDK**)
- `experiments/chromium-013/tools/ui-layout-screenshot-smoke.sh` (**Android CI screenshot workflow**)

## Confirmed current behavior (do not reimplement as if missing)

| Feature | Actual `main` implementation | Evidence | Confidence |
|---|---|---|---|
| Tab grid | `showTabOverview()` uses a `ScrollView` with lightweight row/holder placeholders and `Supplier<View>` factories; it binds cards near the visible viewport and calls `holder.removeAllViews()` offscreen. **It does not eagerly inflate every card.** | Activity L2119–2301, especially L2197 and L2270–2299 | High, static |
| Narrow-phone columns | `BrowserUiLayout.columnsForAvailableWidthDp()` uses 2 columns when enough width remains after side padding, including the tested 320dp viewport. | `BrowserUiLayout.java` L11–36; `BrowserUiLayoutTest.java` | High, static + unit tests |
| URL field | Unfocused URL displays hostname via `displayAddress()`; focusing selects the full URL for editing. | Activity L341, L393–405, L1523–1565 | High, static |
| Tab swipe | Swipe detector is registered on `address.setOnTouchListener`; **not** on the entire webpage/root Surface. | Activity L413–442 | High, static |
| Capture | CDP `Page.captureScreenshot` with fallbacks; previews excluded for private tabs, backed by `TabPreviewStore`. | Activity L1982+, `TabPreviewStore.java` | High, static; real-device behavior unverified |
| Screenshot caching | 12MiB `LruCache` plus bounded app-private JPEG files. It is incorrect to describe thumbnails as an unlimited set of full-resolution bitmaps. | `TabPreviewStore.java` L23–128 | High, static |
| Groups | Named groups, 2x2 mini-cards, expansion, rename and ungroup exist; drag-and-drop grouping is NOT implemented. | Activity L2119–2580 | High, static |
| Private vs regular modes | Distinct tab-mode filtering and private preview exclusion exist. This **does not by itself prove cookie/storage partition isolation** or screenshot security. | `TabRegistry.java`, Activity mode handling, preview store | High for UI; unverified for engine isolation |
| Closed tab history | RAM-only `recentlyClosedRegularTabs`, capped at 20; not a full browsing history. | Activity L106 and L2650–2690 | High, static |
| Resident browser cap | `MAX_RESIDENT_BROWSERS = 6`; tab metadata can outnumber resident renderer instances. | Activity L111 and L1419+ | High, static |
| Start page | Editable local Speed Dials (`readSpeedDials`, `editSpeedDial`) and native search affordance are implemented. | Activity L580–800 | High, static |
| Menu / settings | Quick-action menu and category-based full-screen settings are implemented, though several UI surfaces still use native `Dialog`. | Activity L2775–3240 | High, static |
| Bar placement | User preference `address_bar_bottom` repositions toolbar; layout currently uses separate address and actions rows. | Activity L300–390 and L1597+ | High, static |
| Android target | `targetSdk = 35`, `compileSdk` release 37.1. Thus Android 15+ edge-to-edge requirements apply; proper insets and IME transitions need device verification. | `app/build.gradle.kts` L79–90; Android official docs | High for SDK; unresolved for interaction |
| Existing tests | Android 16/17 emulator smoke and screenshots are present; they are **Velocity-only** tests, not real Chrome/Vivaldi benchmark recordings. | `.github/workflows/velocity-android-smoke.yml` and screenshot tool | High, repository/CI |

## Verified defects and engineering risks (vs. hypotheses)

### High-confidence design/maintenance issues

1. **Large Activity:** `ChromiumTestActivity.java` is ~3,417 lines at the audited revision. Layout construction, tab state, preview callbacks, site policy, and dialogs live in one file. This is a maintainability and test-isolation problem, not proof of current performance failure.
2. **Inline UI constants and strings:** UI colors and text are frequently authored in Java rather than semantic theme/resource tokens. Extracting strings improves maintainability/localization. The earlier precise counts of 237 strings / 196 unique strings were not reproduced in this re-audit and must not be treated as verified measurements.
3. **Two stacked bottom rows:** `toolbarActions` is 49dp and the `addressRow` is 51dp, plus margins/padding. Combined native chrome occupies at least ~100dp. This is measurable from layout source; savings from a 56dp replacement require responsive design validation.
4. **Touch-target audit:** Many controls have visual or layout dimensions below Google's recommended **48×48dp touch target**. Actual interactive bounds can be expanded by hit slop; inspect the real node bounds before reporting a user-visible accessibility failure.
5. **Dialogs:** Menus and settings use Dialog-based UI surfaces; modal transitions and focus/keyboard interactions should be profiled on device before claiming a proven flicker.

### Plausible risks that require testing (not already-proven failures)

- **500-tab scrolling:** Visible-only binding reduces eager inflation but still creates holder rows for all tabs. Need frame-time percentiles, memory allocation counts, decode latency, and tests on 20 / 100 / 500 tabs before replacing with `RecyclerView`.
- **Insets/IME:** No explicit `WindowInsetsCompat` coordinator appears in the Activity; targetSdk=35 makes safe-area behavior a priority. Existing `fitsSystemWindows` and platform layout may affect actual clipping. Verify device and gesture/3-button modes.
- **Tab screenshot races:** Verify asynchronous capture identity and URL validation during close/reopen/rapid switch. Do not assume a stale write is possible without checking existing guards.
- **Private mode:** Verify real cookie, cache, storage and screenshot behavior independently of tab list separation. Do not claim `FLAG_SECURE` or biometric lock is present if it has not been implemented.
- **Inter-app audio ducking:** Earlier fixes did not prove a real Pixel 7 Pro audio-focus regression is eliminated. Keep a device-level reproduction path.

## Correction log for Jules' first draft

| Original claim | Correction |
|---|---|
| “Every tab card is inflated simultaneously” | False: viewport-bound `Supplier<View>` binding already exists. |
| “One column at 320dp” | False: responsive arithmetic and tests provide two columns on the measured narrow viewport. |
| “URL must be manually cleared” | False: full URL is selected on focus. |
| “Tab swipe is on the root view” | False: listener is on address field. |
| “Opening 20+ tabs causes OOM” | Unsupported. Requires repeatable performance data. |
| “No accessibility descriptions anywhere” | Overstated: multiple buttons/cards have descriptions; a systematic coverage audit is still needed. |
| “Activity-level `FLAG_SECURE` by default in other browsers” | Not established via direct tests; distinguish app privacy UI from screenshot policy. |
| “Chrome/Vivaldi directly tested” | Not true of this research environment. Reference claims are documentation-derived. |

## Validation gates for engineering work

Before changing tab virtualization or native window architecture: record screen captures and XML hierarchy at 320dp/393dp, establish baseline profile frame times and `dumpsys meminfo` for 20/100/500 tabs, run in both navigation modes, check IME open/close, test regular/private tab switching, and repeat on Pixel 7 Pro. Reuse current CI and only replace architecture where the evidence justifies it.
