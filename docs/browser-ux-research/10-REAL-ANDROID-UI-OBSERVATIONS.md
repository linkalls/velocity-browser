# Real Android UI Evidence — Chrome and Vivaldi

**Observation date:** 2026-11-09. **Environment:** GitHub Actions `ubuntu-24.04` Android 15 (API 35) Google Play emulator, `sdk_gphone64_x86_64`, Pixel 7 Pro device profile, screen forced to **1080×2400 px at 440 dpi (~393dp width)**, English interface. No signed-in Google/Vivaldi accounts, user data or synthetic browser screenshots. The work is an **actual APK UI observation**, **not** a timed benchmark, an Android 17 test, or a physical Pixel 7 Pro study.

## Evidence and provenance

- [Real browser screenshots / accessibility hierarchy artifact (complete tab and menu run)](https://github.com/linkalls/velocity-browser/actions/runs/42589447896/artifacts/13661076864) — raw PNG and XML pairs, `observations.json`, package versions, setup logs. GitHub Actions artifact retention is limited; keep the reproducible workflow in `.github/workflows/browser-reference-ui-audit.yml` and `tools/observe_android_browsers.py`.
- **Additional Chrome Settings screenshot:** [Prior run](https://github.com/linkalls/velocity-browser/actions/runs/42588653860/artifacts/13660044096) reached actual Chrome Settings; last run reached tab overview instead.
- **Google Chrome:** `com.android.chrome`, observed package version **124.0.6367.219**, preinstalled in the official Google Play Android 15 emulator image. **NOT current Chrome 157**. Current release noted separately in [Chrome release notes](https://chromereleases.googleblog.com/2026/11/chrome-for-android-update_01050980700.html).
- **Vivaldi:** `com.vivaldi.browser`, observed version **8.3.4192.64**, installed from the [official Vivaldi Android archive](https://vivaldi.com/download/archive/?platform=android). Verified official SHA-256 `97e45b190386a288912b99ae22f1676dc38d1c9b77280b901ea11503ebce8a29` for x86-64 APK before installing. No APK is distributed in CI artifacts.

## Directly observed GUI, not a guess

| Screen and evidence | Chrome 124 on emulator | Vivaldi 8.3 on emulator |
|---|---|---|
| Initial setup | Actual welcome screen with `Use without an account`; subsequently `Got it` | Actual welcome screen with `Continue` and `Start Browsing`; Android notification permission prompt also observed |
| Home / new tab | Google wordmark/search affordance, shortcut icons, Discover labels in actual accessibility hierarchy | Speed Dial layout with `Home`, `Shopping`, `Travel`, `More` categories; shortcut groups and `Search Startpage or type URL` |
| Public `https://example.com/` | `Connection is secure`, URL/hostname, tab counter and accessible `Customize and control Google Chrome` | `Connection is secure`, reload, extensions, `Customize and control Vivaldi`; tab bar and bottom navigation present |
| Tab overview | **Actually tapped** `Switch or close tabs`; observed single active `Example Domain` preview card with blue selection outline; `New tab` header control; tapped new tab and recorded resulting screen | **Actually opened** via `See 3 tabs`. Observed `Tabs (3)`, `Search`, `Manage open tabs`, thumbnail cards and native bottom navigation to standard/private/stacks/synced/trashed |
| New tab via switcher | **Actually tapped** `New tab` from Chrome's tab overview | **Actually tapped** `New tab` in switcher and captured a fresh Speed Dial/new-tab page |
| Main menu | **Actually opened** in the prior run via `Customize and control Google Chrome`. Native menu includes Forward, Bookmark, Download, Page info, Refresh, New tab, New Incognito tab, History, Downloads, Bookmarks, Recent tabs, Share, Find in page, Translate, Desktop site. The final run captured the tab screen instead. | **Actually tapped** `Customize and control Vivaldi` in the final run. Captured main menu with top quick actions (Back, Homepage, Forward, Bookmark, Refresh) and visible New tab, New private tab, Duplicate tab, Add tab to new stack, Recent tabs, VPN, Extensions, Bookmarks, History, Notes, Downloads. |
| Settings | **Actually tapped** `Settings` in the prior run and captured the native Settings hierarchy | **Not tested**: main menu was captured, but the Settings entry was below the current accessible viewport and not scrolled into view by this script. Official vendor Settings docs provide the documented path, not an observed tap. |

## Concrete pixel geometry from actual native hierarchy

At **440 dpi**, 1dp = 2.75px. Values below are the **accessibility node bounding rectangle** in a single emulator state (not cross-device specifications):

| Browser / control | Measured bounds (px) | Approximate touch size (dp) |
|---|---|---|
| Vivaldi 8.3 Tab Switcher — Search | `[747,108][868,262]` | 44 × 56dp |
| Vivaldi 8.3 Tab Switcher — Manage open tabs | `[868,108][978,262]` | 40 × 56dp |
| Vivaldi 8.3 Tab Switcher — New tab FAB | `[882,1979][1036,2133]` | 56 × 56dp |
| Vivaldi 8.3 Tab Switcher — Private tabs | `[363,2202][473,2312]` | 40 × 40dp (measured clickable node) |
| Vivaldi 8.3 Tab Switcher — Tabs button | `[915,2202][1047,2334]` | 48 × 48dp |
| Chrome 124 menu — quick Forward/Bookmark/Download/Page info/Refresh | Each 142 × 132px | ~51.6 × 48dp |
| Chrome 124 — `Switch or close tabs` | `[816,108][948,262]` | 48 × 56dp |

**Important:** These are raw UI automation bounding boxes, not inferred hit-slop or recommended Android minimums. Current Chrome 157 may differ substantially. Vivaldi keyboard, animation, performance and drag/drop hit tests were **not measured**.

## What this changes for Velocity

1. Keep the Vivaldi-inspired **2-column visual tab overview with dedicated Search and group controls**, but instrument the existing Velocity lazy-view design before replacing it with RecyclerView.
2. Favor **44–56dp actionable targets** where layout permits. Avoid assuming icon artwork bounds equal click bounds; verify both ADB hierarchy and TalkBack.
3. Separate **main browsing tools** from page-specific translation/permission overlays. Accessible labels must distinguish the browser's overflow menu from webpage-generated `More options` menus.
4. The existing Velocity full-screen tab view can imitate this *information architecture* without copying Vivaldi branding, icons or proprietary UI assets.
5. **Prefer Vivaldi 8.3's visible utility-first grouping:** native top tab search, 2-column cards, bottom mode switcher, 5-item menu quick-action row. Chrome 124's single active card remains useful as a distinct historical pattern, not representative of Chrome 157. Keep Chrome 124 observations in a historical comparison column only. The [Chrome Android 157 documentation](https://support.google.com/chrome/answer/14181646?co=GENIE.Platform%3DAndroid&hl=en) is the source for current Android-specific address-bar settings until a real 157 APK has been operated.

## Not tested; do not claim success

- Chrome 157 / 158 current APK execution; Chrome 124 measurements cannot stand in for it.
- User journey completion time, tap count statistics, FPS, PSS, 500-tab handling, smooth animations or graphics profiling.
- Physical Pixel 7 Pro device behavior, landscape/split screen, private storage isolation or account sync.
- Vivaldi Settings via actual menu (menu captured but Settings was below fold).
- Browser animations/drag-to-stack/group creation: not attempted; screenshots alone do not establish feel or latency.

**Follow-up test result:** Exact native Chrome/Vivaldi labels were added to the test driver, and both native tab switcher and Vivaldi main menu were captured successfully in [run 42589447896](https://github.com/linkalls/velocity-browser/actions/runs/42589447896). Remaining gaps are current Chrome 157, Vivaldi Settings below the fold, physical-device/rotation/insets, and quantitative performance.
