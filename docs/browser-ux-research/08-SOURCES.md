# Sources, Provenance, and Limitations (Revalidated 2026-11-09)

## Mandatory evidence labeling

- **A — Official documentation / release note:** product behavior documented for Android; not a hands-on timing measurement.
- **B — Repo code:** exact behavior or UI size confirmed by the repository at a named commit; not proof that the app feels smooth on a real device.
- **C — Velocity emulator:** recorded Velocity screenshots, UI hierarchy and CI logs; **not** equivalent to running Chrome or Vivaldi.
- **D — Design proposal / inference:** suggested UX improvement, color, spacing, tap count or performance target; may be useful but **not verified**.
- **E — Unverified claim:** missing version-specific primary source or current direct observation. Do not treat as fact and do not build P0 work from it.

The first Jules draft incorrectly presented nearly all comparative observations as directly verified. **At the time of the original Jules research, neither browser had been installed or directly used. A later independent Android 15 emulator test DID launch Chrome 124.0.6367.219 and Vivaldi 8.3.4192.64, with actual screenshots and accessibility hierarchies; see [10-REAL-ANDROID-UI-OBSERVATIONS.md](10-REAL-ANDROID-UI-OBSERVATIONS.md). Current Chrome 157 was not installed and neither browser was performance-profiled.** Real side-by-side-viewable screenshots of different browser builds are now available in CI artifacts, but no controlled Chrome 157 vs Vivaldi 8.3 same-version device task timing or interactive FPS measurement exists. The code audit was static; use the earlier Velocity Android 16/17 emulator CI artifacts for Velocity-only screen evidence. Chrome 157 release version was verified via public primary sources only; Vivaldi 8.3.4192.64 was additionally confirmed by `dumpsys package` following checksum-verified official APK installation on the ephemeral emulator. The older Chrome 124 version was also verified by package metadata.

## Primary sources (official Android pages, explicitly platform scoped)

| ID | Reference / URL | Version or date | Evidence that it actually establishes |
|---|---|---|---|
| C1 | [Chrome Android stable release](https://chromereleases.googleblog.com/2026/11/chrome-for-android-update_01050980700.html) | Nov 5, 2026: **157.0.8103.46** | Official public Stable version; no screen layout measurements. |
| C2 | [Chrome Android early stable release](https://chromereleases.googleblog.com/2026/) | Nov 5, 2026: **158.0.8127.22**, small rollout | Clearly separate Early Stable from broad Stable. |
| C3 | [Chrome: Address bar position — **Android**](https://support.google.com/chrome/answer/14181646?co=GENIE.Platform%3DAndroid&hl=en) | accessed Nov 9, 2026 | Settings > Address bar; portrait-only top/bottom; long-press move. |
| C4 | [Chrome: Manage tabs — **Android**](https://support.google.com/chrome/answer/2391819?co=GENIE.Platform%3DAndroid&hl=en) | accessed Nov 9, 2026 | Open/close/group/reorder; inactive section after 14 days and archive settings. |
| V1 | [Vivaldi: Android 8.3 release](https://vivaldi.com/blog/vivaldi-on-mobile-8-3/) | Nov 5, 2026 | Android 8.3, redesigned blocker, tab stack/media improvements. |
| V2 | [Vivaldi: Android 8.3 minor update](https://vivaldi.com/blog/android/updates/) | Nov 6, 2026 | Minor update and fixes; check currently shipped build separately. |
| V3 | [Vivaldi Android APK version archive](https://vivaldi.com/download/archive/?platform=android) | Nov 6, 2026: **8.3.4192.64** arm64 | Public Vivaldi Android build identifier (not evidence it was run here). |
| V4 | [Vivaldi: Browser interface on Android](https://help.vivaldi.com/android/android-appearance/android-vivaldi-mobile-interface/) | accessed Nov 9, 2026 | Address bar location, Tab Bar, Navigation Bar, toolbar shortcut, scroll hiding. |
| V5 | [Vivaldi: Tabs on Android](https://help.vivaldi.com/android/android-browse/android-tabs/) | accessed Nov 9, 2026 | Tab switcher, tab search, tab positions and gestures. |
| V6 | [Vivaldi: Tab stacks on Android](https://help.vivaldi.com/android/android-browse/android-tab-stacks/) | accessed Nov 9, 2026 | Drag/drop grouping, accordion/two-level grouping and stack toolbar. |
| V7 | [Vivaldi: Main menu on Android](https://help.vivaldi.com/android/android-appearance/main-menu-on-vivaldi-on-android/) | accessed Nov 9, 2026 | Menu quick-action customization. |
| A1 | [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15) | Android 15 / target API 35 | Edge-to-edge enforcement. Applies to Velocity's targetSdk=35. |
| A2 | [Android Views edge-to-edge guide](https://developer.android.com/develop/ui/views/layout/edge-to-edge) | accessed Nov 9, 2026 | Insets and system bars implementation guidance. |
| A3 | [Android accessibility touch targets](https://developer.android.com/guide/topics/ui/accessibility/apps) | accessed Nov 9, 2026 | **Recommendation** of >=48×48dp touch target (not a guarantee for every component). |

**Platform URL issue corrected:** `https://support.google.com/chrome/answer/14181646` can resolve to an iPhone/iPad or Android variant depending on context. Always use `?co=GENIE.Platform%3DAndroid&hl=en` to establish Android behavior. The iOS variant is **not** evidence of Android behavior.

## Repository evidence

- **R0:** `main` at `8c9c3d1` includes merged PRs [#19](https://github.com/linkalls/velocity-browser/pull/19), [#20](https://github.com/linkalls/velocity-browser/pull/20), [#21](https://github.com/linkalls/velocity-browser/pull/21).
- **R1:** [`ChromiumTestActivity.java`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/ChromiumTestActivity.java). See L341–442 (address gesture/focus), L1523–1597 (address/toolbar), L2119–2301 (viewport binding), L2650–2690 (recent tabs).
- **R2:** [`BrowserUiLayout.java`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/BrowserUiLayout.java) and its unit tests: responsive grid.
- **R3:** [`TabPreviewStore.java`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/TabPreviewStore.java): app-private JPEGs, RAM LRU, cache limits.
- **R4:** [`TabRegistry.java`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/TabRegistry.java): tab IDs, private mode fields, groups.
- **R5:** [`app/build.gradle.kts`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/build.gradle.kts): `targetSdk=35`.
- **R6:** [Velocity Android 16/17 UI emulator CI run](https://github.com/linkalls/velocity-browser/actions/runs/42568264408): Velocity screenshots, not Chrome/Vivaldi screenshots. Source audit of smoke assertions is not a user-interaction study.

## Excluded / downgraded claims

Do not label these as verified without additional direct evidence: Chrome's internal 1–3 WebContents resident limit, exact 8dp scroll hide threshold, universal incognito `FLAG_SECURE`, Chrome JPEG/WebP policies, Chrome/Vivaldi card px/dp measurements, Vivaldi's exact group color count, cross-browser FPS rankings, tap counts inferred from changing UIs, subjective UX rankings, and claims that 20+ tabs cause OOM in Velocity.

## Reproduction checklist for future primary research

For Chrome and Vivaldi, install version-pinned official Android binaries in an isolated emulator or physical test device only if licensing and environment permit it. Save the package version, device profile, locale, navigation mode, full-screen/keyboard state, raw screenshots and Android hierarchy. Test every major interaction with the same device/settings. Keep non-runtime descriptions tagged **documentation-derived**. Include an explicit result or **NOT TESTED** per workflow. Do not infer a browser's current layout from an old screenshot.

## Actual Android UI artifact evidence (2026-11-09 follow-up)

- **E1 / Chrome 124 + Vivaldi 8.3 native UI:** [Android 15 emulator Actions run](https://github.com/linkalls/velocity-browser/actions/runs/42589447896) and [screenshots/XML artifact](https://github.com/linkalls/velocity-browser/actions/runs/42589447896/artifacts/13661076864). Both Android packages were directly launched; tab grids were captured; the Vivaldi main menu and new-tab screen were captured. Version metadata and SHA verification are included.
- **E2 / Chrome 124 menu & Settings:** [Earlier Actions run](https://github.com/linkalls/velocity-browser/actions/runs/42588653860) and [artifact](https://github.com/linkalls/velocity-browser/actions/runs/42588653860/artifacts/13660044096). Chrome settings were reached by an actual menu tap.
- **E3 / Vivaldi 8.3 official APK:** [Archive listing](https://vivaldi.com/download/archive/?platform=android) with SHA-256 `97e45b190386a288912b99ae22f1676dc38d1c9b77280b901ea11503ebce8a29`. The official binary is not redistributed in our artifact.

None of these prove current Chrome 157 UI measurements, physical Pixel 7 Pro tap comfort, performance/FPS or real user preference.
