# Independent Revalidation of Jules UX Research — 2026-11-09

## Verdict

**Research draft: revised; hands-on competitor benchmark: NOT PERFORMED.** PR #22 contains valuable UI ideas, but its original text conflated official vendor help pages with direct testing and misread parts of Velocity's merged source. This review corrects those issues **without modifying production application code**. Do not cite this PR as proof of browser FPS or Pixel 7 Pro touch ergonomics.

## Reproducible reference version check

| Browser | Verified official version | Publication date | Reference |
|---|---|---|---|
| Chrome Android Stable | `157.0.8103.46` | Nov 5, 2026 | [Chrome Releases stable](https://chromereleases.googleblog.com/2026/11/chrome-for-android-update_01050980700.html) |
| Chrome Android Early Stable | `158.0.8127.22` **small rollout** | Nov 5, 2026 | [Chrome Releases](https://chromereleases.googleblog.com/2026/) |
| Vivaldi Android | `8.3.4192.64` | Nov 6, 2026 | [Official Android archive](https://vivaldi.com/download/archive/?platform=android), [Android updates](https://vivaldi.com/blog/android/updates/) |

Chrome 131–134 are NOT November 2026 baseline releases. Version verified ≠ actual reference APK installed during this project.

## Evidence map to actual source

**Revision pinned:** [`main@8c9c3d1`](https://github.com/linkalls/velocity-browser/tree/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3).

| Claim rechecked | Code evidence | Corrected conclusion |
|---|---|---|
| Tab grid creates every view in advance | [`ChromiumTestActivity.java#L2197-L2300`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/ChromiumTestActivity.java#L2197-L2300) | **False.** Row placeholders are allocated, but card view factories mount only near viewport and detach on scroll. |
| 320dp is one column | [`BrowserUiLayout.java#L11-L36`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/BrowserUiLayout.java#L11-L36) | **False.** Two columns at tested narrow viewport; width/side padding still matter. |
| Address always shows full URL | [`ChromiumTestActivity.java#L1523-L1566`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/ChromiumTestActivity.java#L1523-L1566) | **False.** Hostname at rest; full URL in edit state. |
| URL isn't selected on focus | [`ChromiumTestActivity.java#L341-L405`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/ChromiumTestActivity.java#L341-L405) | **False.** `setSelectAllOnFocus` and `selectAll` present. |
| Full-screen swipe steals webpage gestures | [`ChromiumTestActivity.java#L413-L442`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/ChromiumTestActivity.java#L413-L442) | **False as described.** Listener belongs to the address field. Gesture collisions within that field still merit QA. |
| 20+ tabs necessarily OOM | [`ChromiumTestActivity.java#L2119-L2300`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/ChromiumTestActivity.java#L2119-L2300) | **Unverified performance prediction.** Benchmark 20/100/500 tabs before redesign. |
| Private-mode profile fully isolated | `TabRegistry.java`, `ChromiumTestActivity.java`, Cefrium context handling | **Unverified as a security guarantee.** UI filtering and no-private-preview are not proof of cookie/storage isolation. |
| Bottom toolbar single compact row | [`ChromiumTestActivity.java#L300-L390`](https://github.com/linkalls/velocity-browser/blob/8c9c3d11983a1eb56b3b21a2d58d9338b95649e3/experiments/chromium-013/app/src/main/java/dev/velocity/chromium/ChromiumTestActivity.java#L300-L390) | **False.** Two rows 51dp address + 49dp actions; one-row design remains an experiment. |
| Exact device UX / FPS comparison | No Chrome/Vivaldi APK runtime test artifacts | **NOT TESTED**. No directly measured cross-browser timings. |

## Primary-source behaviors actually confirmed

- Chrome Android [address-bar location setting](https://support.google.com/chrome/answer/14181646?co=GENIE.Platform%3DAndroid&hl=en): top/bottom selection, long-press move, portrait-only choice; landscape stays top.
- Chrome Android [tabs](https://support.google.com/chrome/answer/2391819?co=GENIE.Platform%3DAndroid&hl=en): groups, reorder, inactive section; docs say 14 days inactivity and options concerning six-month auto-close.
- Vivaldi Android [interface](https://help.vivaldi.com/android/android-appearance/android-vivaldi-mobile-interface/): navigation bar, bottom location, tab bar and scroll toolbar settings.
- Vivaldi Android [tabs](https://help.vivaldi.com/android/android-browse/android-tabs/): switcher, search and configurable tab options.
- Vivaldi Android [stacks](https://help.vivaldi.com/android/android-browse/android-tab-stacks/): drag/group and accordion/two-level/stack toolbar.
- Vivaldi Android [menu](https://help.vivaldi.com/android/android-appearance/main-menu-on-vivaldi-on-android/): customizable compact quick menu.
- Android [targetSDK 35 edge-to-edge](https://developer.android.com/about/versions/15/behavior-changes-15) and [48dp target recommendation](https://developer.android.com/guide/topics/ui/accessibility/apps): OS design constraints; not evidence that Velocity presently clips or fails TalkBack.

## Limitations and next reproducible experiment

1. **No new reference app screenshots.** Version numbers are from official release notes, not installed APK package inspection. Install Chrome and Vivaldi in a separate permitted test environment if a visual benchmark is desired.
2. **No measured tap counts, animation timings or FPS.** The source docs contain user paths; they do not reveal input latencies or scrolling smoothness.
3. **No physical Pixel 7 Pro validation in this audit.** Previous Velocity-only Android 16/17 CI screenshots demonstrate Android rendering but not all lifecycle/audio or one-handed usability scenarios.
4. **No automatic `main` changes.** Research-only PR #22 should be merged only after acceptance of the corrected documents. Future P0 tasks need explicit tests and acceptance criteria.

**Recommended next action:** P0 device matrix and tab baseline (not an unconditional `RecyclerView` rewrite), then tab search and responsive toolbar design experiment. See [07-IMPLEMENTATION-ROADMAP.md](07-IMPLEMENTATION-ROADMAP.md).
