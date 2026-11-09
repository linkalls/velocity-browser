# Verification, Revalidation & Empirical Testing Backlog

## 1. Audit Corrections & Reality Check
Following independent review, several factual corrections and methodological precision guardrails have been codified into the Velocity Browser UX research corpus:

1. **Version Baseline Calibration:**
   - **Google Chrome for Android:** Stable v157.0.8103.46 (released November 5, 2026); v158 Early Stable remains in limited staging rollout.
   - **Vivaldi Browser for Android:** Stable v8.3.4192.64 (released November 6, 2026).
2. **Current `main` Code Verification:**
   - In `experiments/chromium-013/`, tab cards in `ChromiumTestActivity` already utilize viewport-bound `Supplier<View>` lazy card construction rather than inflating all card subtrees eagerly at initialization.
   - The narrow 320dp layout already adapts to a 2-column grid (`BrowserUiLayout.columnsForAvailableWidthDp`).
   - Hostname-at-rest with full-URL-on-focus and auto-select-all (`address.selectAll()`) is already implemented.
   - Omnibox swipe detection is already scoped to address bounds in `ChromiumTestActivity`.
3. **Explicit "NOT TESTED" Classification:**
   - Internal WebContents residency caps in closed-source binaries, exact private `FLAG_SECURE` platform guarantees across OEM ROMs, tab OOM thresholds on diverse RAM configurations, and cross-browser FrameTimeline FPS values are strictly labeled **[NOT TESTED]** unless proven via reproducible AOSP/emulator perfetto traces.
   - Tap counts and interaction sequences derived from secondary reviews are marked as **[ESTIMATED / SPECIFIED]** rather than verified runtime device telemetry.
4. **P0 Engineering Plan Calibration:**
   - The immediate P0 engineering priority begins with **WindowInsets edge-to-edge handling**, **touch target standardization (>=48dp)**, **string resource extraction (i18n)**, and **reproducible 20/100/500-tab memory and layout measurements**, rather than an immediate speculative rewrite of the layout hierarchy.

---

## 2. Empirical Follow-Up Verification Backlog

To achieve 100% empirical verification on target hardware, the following hands-on interaction and measurement protocol must be completed when a physical Android device or nested virtualization emulator becomes available:

| Item | Target Device | Environment / Tools | Verification Goal | Status |
|---|---|---|---|---|
| **E-1** | Pixel 7 Pro (Physical) | ADB + Screencap | Capture real edge-to-edge insets under gesture navigation and 3-button navigation | Pending physical device |
| **E-2** | Chrome v157 Stable APK | Physical device / AOSP | Measure exact omnibox translation animation duration with high-speed video / FrameTimeline | Pending device install |
| **E-3** | Vivaldi v8.3 Stable APK | Physical device / AOSP | Verify bottom toolbar merge touch targets and menu width scaling | Pending device install |
| **E-4** | Velocity 20-Tab Test | AOSP Emulator (API 36/37) | Profile PSS memory and frame latency during continuous vertical scroll | Pending JDK 25 runtime APK build |
| **E-5** | Velocity 100-Tab Test | AOSP Emulator (API 36/37) | Measure `BoundedScrollView` view inflation times vs. `RecyclerView` prototype | Pending JDK 25 runtime APK build |
| **E-6** | Velocity IME Transition | Physical device | Verify `WindowInsetsAnimationCompat` smooth translation without viewport clipping | Pending physical device |
