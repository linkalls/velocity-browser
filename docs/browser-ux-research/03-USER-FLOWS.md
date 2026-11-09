# Browser Interaction Flows — Verified Steps and Test Plans

**Scope:** Chrome Android 157 as a documented target, Vivaldi Android 8.3 as a documented and partially observed target, Velocity `main` at `8c9c3d1`. The original draft installed no reference APK. The 2026-11-09 follow-up **ran older Chrome 124 and current Vivaldi 8.3** on an Android 15 emulator and captured a small set of real actions and screens; see [10 — real UI observations](10-REAL-ANDROID-UI-OBSERVATIONS.md). Those screenshots do not measure task latency or prove the full Chrome 157 UX. The flows below labeled **documented** are vendor help descriptions, not timed direct taps. Velocity paths labeled **source** follow implementation inspection; no timing or physical-device gesture ergonomics were measured.

## Methodology

A "tap count" is meaningful only with a specified app build, entry screen, navigation mode, locale, and whether the keyboard is already visible. The previous Jules draft stated exact cross-browser counts as measured without logging a test run; those numbers are withdrawn. The *target taps* column below is an engineering UX goal, not a competitor benchmark.

| # | Flow | Chrome / Vivaldi evidence | Actual Velocity state (source) | Unverified behavior / suggested acceptance test |
|---|---|---|---|---|
| 1 | Open new tab | Both browser docs describe switcher/menu new tab flows. [Chrome tabs](https://support.google.com/chrome/answer/2391819?co=GENIE.Platform%3DAndroid&hl=en), [Vivaldi tabs](https://help.vivaldi.com/android/android-browse/android-tabs/) | New-tab menu, switcher FAB and long-press tab counter implemented. | Test home vs page, private/normal, 320dp buttons and the keyboard state. |
| 2 | Search | Android address editing is supported; exact suggestion ranking/version-dependent UI unmeasured. | `address.setSelectAllOnFocus(true)` and focus handler select full URL, `displayAddress` shortens the resting field. | Verify IME insets, input method action, autocomplete, paste-and-go design. |
| 3 | Switch between two tabs | Vivaldi documents tab switcher and address swipe; direction/settings are version-dependent. | `GestureDetector` is on **address field** (not root Surface) and tab selection scopes to same privacy mode. | Test flings both directions without triggering text selection/keyboard. |
| 4 | Find among 100+ | Vivaldi officially documents switcher **search by tab name/link**. | Viewport `Supplier<View>` grid exists; **tab search not yet implemented**. | Add mode-aware query; measure 100/500 tab index/filter latency. |
| 5 | Group tabs | Vivaldi officially documents long-press/drag tab onto another. | Named 2x2 groups, rename, ungroup, expand; grouping via UI dialogs, no drag/drop. | Test drag/drop UX and accidental gesture/scroll interactions. |
| 6 | Reopen closed tab | Vivaldi offers recently closed tabs; Chrome exposes history/recent tab flows. | `recentlyClosedRegularTabs` RAM list with 20 entries + undo; **not full browsing history**. | Confirm process death behavior, undo timing and private tab exclusion. |
| 7 | Enter private | Both browser docs describe private tab switchers. | Separate mode filtering and menu action; private thumbnail capture disabled. | Test cookies/storage, OS screenshot policy; no secure-profile claim from UI alone. |
| 8 | Share | Android share flows exist; exact vendor UI may change. | `Intent.ACTION_SEND` via `shareCurrentPage()`. | Test share/cancel/app return with WebContents retained. |
| 9 | Desktop site | Browser menus provide desktop mode choices; don't infer viewport emulation internals. | `toggleDesktopMode()` changes per-tab override. | Compare User-Agent/viewport on real sites and re-enter app. |
| 10 | Bookmarks & history | Vivaldi panel provides bookmarks/history/downloads ([interface](https://help.vivaldi.com/android/android-appearance/android-vivaldi-mobile-interface/)). | Speed Dials exist; recently closed tabs are not full history; full bookmarks/history UI not complete. | Build persistent backends before making new UI buttons. |
| 11 | Move address bar | Chrome Android now documents Settings selection **and long-press move**, portrait only. [Chrome Android](https://support.google.com/chrome/answer/14181646?co=GENIE.Platform%3DAndroid&hl=en) | Settings toggle `address_bar_bottom`; `applyBrowserBarPosition` reparents. | Test portrait/landscape, rotation while IME visible, safe insets. |
| 12 | Site information | Vivaldi documents site info and blocker controls. | `showCurrentSiteInfo` displays host and scheme-based text. | Treat HTTPS text as an indicator, **not** independently validated certificate/security permission UI. |
| 13 | One-hand use | Vendor official docs establish bottom bar placement options, **not quantitative reachability**. | 49dp action row + 51dp address row: large vertical footprint. | Record task completion and reach comfort on Pixel 7 Pro before choosing one-row design. |
| 14 | IME behavior | Vendor animation internals were not examined. | `SOFT_INPUT_ADJUST_RESIZE`, focused address actions and native EditText are present. | Record keyboard opening/closing at 320/393dp; inspect insets and frame jank. |
| 15 | Restore after process kill | Vendor details depend on profile/device and were not directly reproduced. | SharedPreferences tab metadata restore and `MAX_RESIDENT_BROWSERS = 6`. | Kill process intentionally; verify active URL, back stack, group, thumbnails, and private session cleanup. |

## Required controlled usability measurements

Use the same Android build, display density, system font scale, locale and input method for each browser. For each flow, record:

- **Version/build:** Chrome APK version, Vivaldi APK version, Velocity APK SHA, Android SDK/device model.
- **Starting state:** Home/page/switcher; number of tabs; normal/private; portrait/landscape; keyboard open or closed.
- **Inputs:** Video or ADB input sequence, precise taps/gestures, any failed attempts.
- **Results:** Successful completion, steps, elapsed time, mis-taps, backtracking, accessibility/TalkBack status, visual motion and persistent state.
- **Source:** Raw screen video and UI hierarchy or vendor help page. Mark documented paths separately from directly tested paths.

### Suggested controlled scenarios

- 20, 100 and 500 blank/synthetic tabs; find one titled `Article 042`, switch and return.
- 10 groups with 4 tabs each; create/rename/ungroup while scrolling.
- Open/close keyboard on both address positions; rotate, switch split-screen, use gesture and 3-button navigation.
- Turn on TalkBack / 200% font; evaluate reachability of tab close, menu and toolbar actions.
- Start private tab, close it, return via Android Recents and OS screen capture; verify intended security policy.

**Do not substitute theoretical touch-count tables for logs of actual user interactions.** A good prospective target is one or two steps for common navigation, but optimize by observed task success and physical reach rather than an arbitrary raw count.
