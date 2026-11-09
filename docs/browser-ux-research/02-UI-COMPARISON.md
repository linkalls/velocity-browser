# Chrome, Vivaldi and Velocity — Corrected UI/Component Comparison

**2026-11-09; evidence labels:** **A** = Android official docs; **B** = Velocity `main` source; **C** = Velocity-only emulator CI; **D** = design proposal; **E** = unverified. The reference browsers were **not** installed or benchmarked here. See [08-SOURCES.md](08-SOURCES.md).

## Current reference versions

- **Chrome Android Stable 157.0.8103.46**, published Nov 5 ([official](https://chromereleases.googleblog.com/2026/11/chrome-for-android-update_01050980700.html)); 158.0.8127.22 Early Stable to a limited subset on the same date is **not** the normal stable baseline.
- **Vivaldi Android 8.3.4192.64**, Nov 6 minor update ([official APK archive](https://vivaldi.com/download/archive/?platform=android)); [8.3 release announcement](https://vivaldi.com/blog/vivaldi-on-mobile-8-3/).
- **Velocity** `main` at `8c9c3d1`, Java/Cefrium implementation, not an installed browser comparative measurement.

## Evidence-backed functionality matrix

| Topic | Chrome Android 157 | Vivaldi Android 8.3 | Velocity `main` |
|---|---|---|---|
| Address bar position | **A** Top/bottom option in Settings, long-press relocation; portrait-specific ([Android help](https://support.google.com/chrome/answer/14181646?co=GENIE.Platform%3DAndroid&hl=en)) | **A** Top/bottom option, landscape/tablet behavior documented ([interface](https://help.vivaldi.com/android/android-appearance/android-vivaldi-mobile-interface/)) | **B** `address_bar_bottom` setting and `applyBrowserBarPosition`; 2-row controls remain |
| Tab overview | **A** Tab switcher supports opening, closing, grouping, reordering; exact card dp not measured ([tab help](https://support.google.com/chrome/answer/2391819?co=GENIE.Platform%3DAndroid&hl=en)) | **A** Tab switcher, search by title/link ([tabs](https://help.vivaldi.com/android/android-browse/android-tabs/)) | **B** Full-height overview, 2-column narrow layout, groups; no live tab-title search |
| Tab grouping | **A** Grouping and group-management documented; exact colors unverified | **A** Drag/drop, two-level/accordion stacks, tab stack toolbar ([stacks](https://help.vivaldi.com/android/android-browse/android-tab-stacks/)) | **B** Named 2×2 mosaic groups, expand/rename/ungroup; no drag/drop grouping |
| Tab virtualization | **E** Internal cache/recycling of specific 157 build not profiled | **E** Internal cache/recycling of specific 8.3 build not profiled | **B** `Supplier<View>` visible-window binding with offscreen removal, NOT all cards inflated |
| Inactive tabs | **A** Default 14-day inactive section; help describes six-month auto-close options | **E** Re-check behavior with currently installed APK | **B** No age-based inactive archive; metadata/renderer cap are different concerns |
| Private tab mode | **A** Incognito UI documented; detailed `FLAG_SECURE`/cache settings not audited | **A** Private UI controls documented; exact screenshot policy not audited | **B** Regular/private UI filtering and no private preview disk persistence; storage isolation needs device/engine verification |
| Start page | **A** Customizable new-tab shortcuts ([Chrome tabs help](https://support.google.com/chrome/answer/2391819?co=GENIE.Platform%3DAndroid&hl=en)) | **A** Speed Dial and configurable UI (Vivaldi Android help) | **B** Native editable Speed Dial tiles, search affordance |
| Menu | **E** Exact five-button order depends on version/context | **A** Configurable 5-item compact menu ([official menu help](https://help.vivaldi.com/android/android-appearance/main-menu-on-vivaldi-on-android/)) | **B** Quick-action menu plus Advanced submenu, not yet equivalent to configurable Vivaldi menu |
| Site information | **E** UI details not version-pinned here | **A** Security / content blocker entry in address bar documented | **B** Minimal host/HTTPS dialog exists; not a complete permissions sheet |
| Toolbar auto-hide | **E** Exact behavior, compositor parameters unmeasured | **A** Optional show-toolbars-while-scrolling setting documented | **B** Current behavior should be profiled; no claim of Chrome-equivalent scroll animation |

## Measurements: what we know vs what we do not

| Component | Velocity code-backed size/behavior | Reference browser measurement | Candidate design target (NOT verified competitor spec) |
|---|---|---|---|
| Action row | 49dp (`toolbarActions`) | Not measured | Prefer 48dp minimum *interactive* target |
| Address row | 51dp (`addressRow`), plus layout padding | Not measured | Compare compact single-line to two-line at 320dp/393dp |
| Bottom chrome | Two stacked rows, **at least ~100dp** | Not measured | **56dp is only a prototype hypothesis**; likely tight with five actions and readable URL |
| Card grid | Columns decided by `BrowserUiLayout` and widths, 2 at tested 320dp | Not measured | Keep two columns on typical phones; verify large text and tap bounds |
| Preview height | Code: 264dp/205dp/168dp/140dp for 1/2/3/4 columns | Not measured | Ratio should derive from actual card width plus UI content |
| Tab bitmap | CDP screenshot, bounded cache/preview downscale | Not measured | Preserve real compositor pixels; optimize only after profiling |
| Colors | `CANVAS=#16171C`, `SURFACE=#25272E`, `ACCENT=#7EA9F4` in current Activity | No systematic sample from browser APK | Future semantic theme tokens with light/dark styles |
| Interactions | Some icon layout bounds below 48dp | Not measured | Google recommends >=48×48dp touch targets ([Android accessibility](https://developer.android.com/guide/topics/ui/accessibility/apps)) |

**Important:** A touch target and the visible vector icon are different measurements. Do not treat a 24dp icon as a 24dp interactive hit area. Likewise, an estimated visual width is not a verified vendor dimension.

## Behavior checks and remaining design gaps

1. **Browser address editing:** Current Velocity already shows domain at rest and full URL on focus, selected for replacement. Missing richer suggestions, security permission controls, and keyboard transition QA. A `TextView`/`EditText` replacement is optional, not necessary to fix the falsely reported raw-URL display.
2. **Tab switcher:** Current View implementation uses viewport-limited binding and runs on CI. No direct evidence of 20+ tab OOM. Add tab title/URL filtering first and profile 20/100/500 tabs before considering RecyclerView.
3. **Tab groups:** Keep 2x2 real previews and current metadata behavior; introduce drag/drop and undo in isolated, tested PRs.
4. **Private mode:** Never infer separate Chromium storage profiles from color/UI separation; test cookie/storage and OS screenshots with controlled accounts.
5. **Speed Dial:** Editable entries already exist; real site icons, folder model and rearrangement are potential features rather than currently missing basic shortcut editing.
6. **Menu and settings:** Current category settings and quick-action menu exist. Do not implement a decorative Library/Downloads button until a real backend supports it.
7. **Navigation bars/IME:** Target SDK 35 makes edge-to-edge safe areas a real concern. Run Android 16/17 and Pixel 7 Pro visual checks for keyboard, three-button nav, and landscape.

## Secondary browsers

Samsung Internet, Brave, Firefox and Edge were not freshly checked against their November 2026 Android releases. Their earlier version ranges, feature lists and subjective usability rankings remain **research leads**, not verified facts. Add each to this matrix only after version-pinned official sources or repeatable install/screenshot evidence.

## Decision rule

Choose designs based on **verified browser behavior + Velocity source + measured user-device results**, not reverse-engineered guesses. See the conditional engineering sequence in [07-IMPLEMENTATION-ROADMAP.md](07-IMPLEMENTATION-ROADMAP.md).
