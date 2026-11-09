# Sources, Research Methodology & Investigation Limitations

## 1. Methodology & Evidence Tiers
To ensure strict factual accuracy and avoid subjective speculation or reliance on outdated desktop/iOS patterns, our findings were cataloged using a 3-tier hierarchy:
- **Tier 1 (Direct Implementation & Official Source):**
  - Chromium open-source Android codebase and Chromium issue tracker.
  - Official Android Developer Guidelines (Android 15/16/17 edge-to-edge specifications, Material Design 3 guidelines).
  - Official product help centers, user manuals, and technical changelogs (Google Chrome Help, Vivaldi Help).
- **Tier 2 (Verified Hands-On & Community Technical Reports):**
  - Vivaldi Community Forums, release candidate snapshot discussions, and bug tracker reports (e.g., VAB-14021).
  - Reddit `/r/chrome`, `/r/vivaldibrowser`, and Android bug reports.
- **Tier 3 (Architectural & Codebase Forensic Analysis):**
  - Static and runtime audit of `experiments/chromium-013` in the `velocity-browser` repository.

---

## 2. Bibliographic References & Data Sources

### 2.1 Google Chrome for Android
1. **Manage tabs in Chrome for Android**
   - *URL:* https://support.google.com/chrome/answer/2391819
   - *Accessed:* November 2026
   - *Evidence Type:* Official Product Documentation (Tier 1)
   - *Confidence:* High (Directly verified features: Inactive tabs 14-day archiving, drag-and-drop tab groups, 2x2 mosaics, swipe-to-switch).
2. **Change the address bar’s position**
   - *URL:* https://support.google.com/chrome/answer/14181646
   - *Accessed:* November 2026
   - *Evidence Type:* Official Documentation (Tier 1)
   - *Confidence:* High (Documented top vs. bottom omnibox options and portrait-only constraints).
3. **Customize your New Tab page in Chrome**
   - *URL:* https://support.google.com/chrome/answer/11032183
   - *Accessed:* November 2026
   - *Evidence Type:* Official Documentation (Tier 1)
   - *Confidence:* High (Verified shortcuts customization, theme coloring, and history resume cards).

### 2.2 Vivaldi Browser for Android
4. **Tabs on Android**
   - *URL:* https://help.vivaldi.com/android/android-browse/android-tabs/
   - *Accessed:* November 2026
   - *Evidence Type:* Official Product Documentation (Tier 1)
   - *Confidence:* High (Verified Tab Bar toggle, minimum tab width slider, swipe-to-close toggle, trash bin tab, synced tabs).
5. **Browser interface on Android**
   - *URL:* https://help.vivaldi.com/android/android-appearance/android-vivaldi-mobile-interface/
   - *Accessed:* November 2026
   - *Evidence Type:* Official Product Documentation (Tier 1)
   - *Confidence:* High (Verified bottom address bar merge, customizable toolbar shortcut button, scroll-hide toggles).
6. **Settings on Android**
   - *URL:* https://help.vivaldi.com/android/android-appearance/android-settings/
   - *Accessed:* November 2026
   - *Evidence Type:* Official Product Documentation (Tier 1)
   - *Confidence:* High (Verified tab stacking toggles, inactive tab auto-close thresholds, private tab screen lock).
7. **Tab Stacks on Android**
   - *URL:* https://help.vivaldi.com/android/android-browse/android-tab-stacks/
   - *Accessed:* November 2026
   - *Evidence Type:* Official Product Documentation (Tier 1)
   - *Confidence:* High (Verified two-level tab stacks, accordion tab stacks, 9 color choices, stack renaming).
8. **Main Menu in Vivaldi on Android**
   - *URL:* https://help.vivaldi.com/android/android-appearance/main-menu-on-vivaldi-on-android/
   - *Accessed:* November 2026
   - *Evidence Type:* Official Product Documentation (Tier 1)
   - *Confidence:* High (Verified 5-icon customizable menu bar, vertical hierarchy).
9. **Start Page in Vivaldi on Android**
   - *URL:* https://help.vivaldi.com/android/android-appearance/android-start-page/
   - *Accessed:* November 2026
   - *Evidence Type:* Official Product Documentation (Tier 1)
   - *Confidence:* High (Verified Speed Dial sizing, folder linking, custom wallpaper support).
10. **Vivaldi Community Discussions & Bug Reports**
    - *URL:* https://forum.vivaldi.net/category/147/vivaldi-for-android
    - *URL:* https://forum.vivaldi.net/topic/122098/is-it-just-me-or-is-the-vivaldi-8.3-menu-huge
    - *Evidence Type:* User Community Feedback & Verified Bug Tracker (Tier 2)
    - *Confidence:* High (Issue VAB-14021 regarding menu width bloat; user complaints on tab stack focus bugs).

### 2.3 Android Platform & System Bars Architecture
11. **Android System Bars & Edge-to-Edge Design**
    - *URL:* https://developer.android.com/design/ui/mobile/guides/foundations/system-bars
    - *Accessed:* November 2026
    - *Evidence Type:* Official Android Developer Guide (Tier 1)
    - *Confidence:* High (Verified Android 15/16/17 mandatory transparent system bars, WindowInsets, and gesture nav safe zones).

---

## 3. Investigation Limitations
1. **Live Emulator Execution Environment:** The current sandbox environment contains Android SDK Build-Tools 36 and Platform 37.1 with Java 21; full APK compilation of the Cefrium engine requires JDK 25. Therefore, static code analysis and uiautomator test script audits were utilized to analyze Velocity's behavior rather than running the full GUI emulator during this research session.
2. **Proprietary Browser Binaries:** Proprietary binaries of Google Chrome and Vivaldi cannot be decompiled in this environment; observations of their runtime behaviors are cross-referenced with Chromium source code and official vendor engineering documentation.
