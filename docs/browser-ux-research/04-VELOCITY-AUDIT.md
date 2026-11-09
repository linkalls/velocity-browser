# Technical & UX Audit of Velocity Browser (Current Prototype)

## Executive Summary
This document provides a forensic audit of the Velocity Browser implementation under `experiments/chromium-013/`, examining the architectural foundations, UI components, interaction paradigms, performance limits, and accessibility conformance introduced in PRs #19, #20, and #21.

While the existing implementation successfully proves that real Chromium/Cefrium rendering, CDP-based thumbnail capture, and tab persistence can function without heavy external dependencies, the user interface currently exhibits severe architectural and usability shortcomings that prevent it from feeling like a polished, production-grade Android browser.

---

## 1. Codebase Architecture & Cefrium Integration

### 1.1 Monolithic Activity Anti-pattern
- **The Core Issue:** `ChromiumTestActivity.java` spans **3,417 lines of code** in a single file.
- **Architectural Consequences:**
  - UI construction, layout calculations, touch gesture handling, CDP JSON protocol parsing, SharedPreferences persistence, dialog lifecycles, and engine event dispatches are coupled into a single god class.
  - Testability is severely impaired. Aside from `BrowserUiLayoutTest.java` (which tests static arithmetic in isolation), none of the actual view hierarchy or user interaction flows can be unit-tested without booting an emulator.
  - UI states frequently race with asynchronous Cefrium callbacks. For example, closing a tab while an asynchronous CDP screenshot is executing can post a completed bitmap into `TabPreviewStore` for a tab that was already deleted.

### 1.2 Programmatic UI Construction vs. Modern Declarative Architecture
- The entire UI is built programmatically using raw Android View instances (`new LinearLayout(this)`, `new TextView(this)`).
- **Maintenance Deficiencies:**
  - 255 separate invocations of the custom helper `dp(int v)`.
  - Inline hardcoded color constants (`PRIMARY`, `SECONDARY`, `BG`, `CARD`, `ACCENT`, `BORDER`).
  - No integration of standard Android theme attributes, Material Design components, or modern Jetpack Compose layouts.

---

## 2. Forensic UI/UX Component Deficiencies

### 2.1 Hardcoded Strings & Missing Localization (i18n)
- **Finding:** The activity contains **237 hardcoded Japanese string literals** across 196 unique values (e.g., `"ショートカットを追加"`, `"ブラウザメニュー"`, `"新しい非公開タブ"`, `"タブ一覧"`).
- **Impact:**
  - Zero strings are extracted to `strings.xml`.
  - Non-Japanese users cannot understand critical navigation or privacy prompts.
  - Changing a label requires modifying Java business logic and risks breaking automated uiautomator tests that assert against hardcoded Japanese strings (e.g., `verify_browser_shell_ui.py`).

### 2.2 Modal Dialogs vs. Standard Bottom Sheets
- **Finding:** The prototype makes heavy use of Android `Dialog` and `AlertDialog` instances (16 separate `new AlertDialog` occurrences) styled to look like bottom sheets.
- **Usability Failures:**
  - When an `AlertDialog` or `Dialog` opens, it creates a separate top-level `PhoneWindow`. This forcibly pauses underlying window interactions and detaches the UI from the Activity’s view hierarchy.
  - Opening a sub-menu (e.g., tapping "詳細ツール" or "設定" inside `showMenu()`) requires closing the parent `dialog[0].dismiss()` and constructing a whole new `Dialog` instance, causing visible window flicker and losing scroll positions.
  - Back gestures trigger platform dialog dismissal rather than standard predictive back transitions.

### 2.3 Bottom Layout Ergonomic Bloat
- **Finding:** When the user toggles "アドレスバーを下に配置" (Address bar at bottom), the UI does not consolidate controls into a unified bar. Instead, it vertically stacks:
  1. The navigation action row (`toolbarActions`, 49dp height)
  2. The address input row (`addressRow`, 51dp height)
- **Impact:** Stacking two bars consumes **100dp of vertical screen estate** at the bottom of the screen, severely crowding the web viewport and creating unnecessary visual noise.

### 2.4 Lack of Edge-to-Edge & WindowInsets Handling
- **Finding:** There is **zero integration with modern Android WindowInsets** (`WindowInsetsCompat.Type.systemBars()` or `ime()`).
- **Impact on Android 15, 16, and 17:**
  - Android 15+ strictly enforces edge-to-edge drawing. Because Velocity does not consume system bar insets, on edge-to-edge devices the bottom toolbar overlaps directly with the system gesture navigation bar.
  - When the virtual keyboard (IME) opens, the window resizes abruptly without interpolation, causing visual stuttering and jumping.

### 2.5 Touch Target & Accessibility Violations
- **Finding:** Multiple interactive icons have touch targets below the Android Accessibility standard minimum of 48x48dp.
  - Action icons inside the bottom toolbar measure 42x42dp or 43x43dp (`new LinearLayout.LayoutParams(dp(43), dp(43))`).
  - Speed Dial delete buttons measure 28x28dp.
  - Only 15 view elements have explicit `contentDescription` attributes; hundreds of clickable views (e.g., individual tab cards, close buttons, menu dividers) lack descriptive labels for screen readers.

### 2.6 Scalability Limits in the Tab Switcher
- **Finding:** The Tab Switcher is constructed using a `BoundedScrollView` wrapping a vertical `LinearLayout` with dynamically added card rows.
- **Performance Danger:**
  - Every open tab inflates an entire card view hierarchy and holds references to loaded thumbnail bitmaps.
  - While this functions acceptably for 5–10 tabs in a test bed, opening **50 to 200 tabs will trigger immediate FrameTimeline jank, extreme garbage collection pressure, and Out-Of-Memory (OOM) crashes**.
  - A production tab switcher must use a recycling view architecture (`RecyclerView` with `DiffUtil`).

---

## 3. Summary of Velocity UI/UX Anti-Patterns

| Anti-Pattern | Current Implementation | Severity | Corrective Architecture |
|---|---|---|---|
| **Monolithic God Activity** | 3,417-line `ChromiumTestActivity.java` | High | Modularize into Controllers/Presenters per screen surface |
| **Hardcoded Japanese Strings** | 237 string literals in Java code | High | Extract all strings to `res/values/strings.xml` with i18n support |
| **Window-Stacking Modal Dialogs** | `new Dialog(this)` for menus/settings | High | Replace with true in-hierarchy `BottomSheetDialogFragment` |
| **Stacked Double Bottom Bar** | 49dp actions + 51dp address = 100dp | Medium | Consolidate into a unified 56dp bottom omnibox bar |
| **No WindowInsets / Edge-to-Edge** | 0 insets listeners | High | Implement `ViewCompat.setOnApplyWindowInsetsListener` |
| **Sub-48dp Touch Targets** | 42dp and 43dp action targets | Medium | Standardize all clickable targets to >= 48dp |
| **Un-recycled Tab Switcher** | `BoundedScrollView` + `LinearLayout` | High | Implement `RecyclerView` with grid layout manager |
| **Gesture Navigation Conflicts** | Full-screen swipe listener for tabs | Medium | Restrict tab-switching gestures strictly to toolbar bounds |
