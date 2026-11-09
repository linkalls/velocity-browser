# Actionable Engineering Roadmap & Implementation Plan

## Feasibility & Technical Risk Evaluation

| Feature Area | Engine / API Requirements | Velocity Feasibility | Feasibility Assessment |
|---|---|---|---|
| **Consolidated Bottom Toolbar** | Native Android View layout | **High** | Pure native UI refactoring; no Cefrium engine modification needed. |
| **Edge-to-Edge System Bars** | Android `WindowCompat`, `WindowInsets` | **High** | Standard AndroidX APIs (`androidx.core.view.WindowInsetsCompat`). |
| **RecyclerView Tab Switcher** | `androidx.recyclerview.widget.RecyclerView` | **High** | Replaces `BoundedScrollView` with recycled viewholders. |
| **Real CDP Thumbnail Pipeline** | CDP `Page.captureScreenshot` | **Proven** | Already functioning in PR #19; requires thumbnail downsampling optimizations. |
| **Tab Grouping 2x2 Mosaics** | Pure-Java TabRegistry metadata | **Proven** | Extends existing `TabRegistry` group attributes and mosaic rendering. |
| **Private Biometric Lock** | `androidx.biometric.BiometricPrompt` | **Medium** | Requires adding Jetpack Biometric dependency and device crypto checks. |
| **Inactive Tab Archiving** | Timestamp metadata in `TabRegistry` | **High** | Simple metadata comparison: if `System.currentTimeMillis() - lastActive > 14 days`. |
| **String Extraction (i18n)** | Android resource system | **High** | Clean extraction of 196 Japanese strings to `res/values/strings.xml`. |

---

## Prioritized Task Backlog

### Priority P0: Critical Usability, Performance & Ergonomics (Immediate)

#### Task P0.1: Extract Hardcoded Strings & Provide Multi-Language Resources
- **Problem:** 237 hardcoded Japanese string literals in `ChromiumTestActivity.java` block internationalization.
- **Action:** Extract all strings into `res/values/strings.xml` (default English) and `res/values-ja/strings.xml` (Japanese). Update automated tests to handle localized resource lookup.
- **Affected Files:** `ChromiumTestActivity.java`, `res/values/strings.xml`, `res/values-ja/strings.xml`.

#### Task P0.2: Implement Edge-to-Edge & System WindowInsets
- **Problem:** Lack of WindowInsets handling leads to bottom toolbar clipping beneath gesture navigation bars on Android 15/16/17.
- **Action:** Invoke `WindowCompat.setDecorFitsSystemWindows(window, false)` and bind `ViewCompat.setOnApplyWindowInsetsListener` to dynamically pad toolbars and handle IME animation.
- **Affected Files:** `ChromiumTestActivity.java`.

#### Task P0.3: Convert Tab Switcher to Recycled `RecyclerView`
- **Problem:** `BoundedScrollView` inflates all cards simultaneously, risking OOM crashes and jank on 20+ tabs.
- **Action:** Replace with `RecyclerView` using a 2-column `GridLayoutManager` and `DiffUtil` tab adapter.
- **Affected Files:** `ChromiumTestActivity.java`, `TabPreviewStore.java`.

#### Task P0.4: Consolidate Bottom Toolbar into Unified 56dp Bar
- **Problem:** Address bar at bottom currently stacks two separate rows (100dp total), wasting screen space.
- **Action:** Merge navigation buttons and omnibox pill into a single, compact 56dp row.
- **Affected Files:** `ChromiumTestActivity.java`, `BrowserUiLayout.java`.

---

### Priority P1: Core Browser Chrome & Navigation Enhancements

#### Task P1.1: Replace Modal Dialogs with True Bottom Sheets
- **Problem:** 16 `AlertDialog` and `Dialog` calls cause window-stacking flickers and disconnect from activity layout.
- **Action:** Migrate Main Menu and Settings into genuine `BottomSheetDialogFragment` components.
- **Affected Files:** `ChromiumTestActivity.java`.

#### Task P1.2: Standardize Touch Targets to 48x48dp
- **Problem:** Several toolbar and speed dial icons measure 42dp or 28dp, violating accessibility criteria.
- **Action:** Set minimum dimensions of all interactive view touch targets to `dp(48)`.
- **Affected Files:** `ChromiumTestActivity.java`.

#### Task P1.3: Isolate Tab-Switching Swipes to Toolbar Bounds
- **Problem:** Full-screen horizontal touch detector conflicts with webpage panning and horizontal carousel widgets.
- **Action:** Attach horizontal swipe listener exclusively to the top/bottom toolbar containers.
- **Affected Files:** `ChromiumTestActivity.java`.

#### Task P1.4: Sticky Tab Search in Switcher
- **Problem:** No search mechanism exists to find a tab among 100+ open tabs.
- **Action:** Add a persistent search filter input in the Tab Switcher header.
- **Affected Files:** `ChromiumTestActivity.java`, `TabRegistry.java`.

---

### Priority P2: Advanced Features & Polish

#### Task P2.1: Implement Inactive Tab Auto-Archiving
- **Problem:** Large tab collections degrade navigation clarity.
- **Action:** Add a `lastAccessedTimestamp` to `TabRegistry.Tab`. Auto-move tabs unused for 14 days to a collapsed section.
- **Affected Files:** `TabRegistry.java`, `ChromiumTestActivity.java`.

#### Task P2.2: Add Biometric Authentication for Private Tabs
- **Problem:** Private tabs left open can be viewed by anyone with physical phone access.
- **Action:** Implement optional biometric authentication prompt upon reopening private tabs.
- **Affected Files:** `ChromiumTestActivity.java`.

---

## Proposed Pull Request Sequence

```
========================================================================================
PR #   Branch Name                  Scope & Deliverables
========================================================================================
PR 1   feat/ui-i18n-strings         Extract 196+ strings to res/values/strings.xml & ja
PR 2   feat/edge-to-edge-insets     Android 15+ WindowInsetsCompat & IME handling
PR 3   feat/unified-bottom-toolbar  Consolidate 100dp double bar into single 56dp bar
PR 4   feat/recyclerview-switcher   Recycled grid tab switcher with swipe-to-dismiss
PR 5   feat/bottom-sheet-menu       Unified Material 3 bottom sheet for menu & settings
PR 6   feat/tab-search-archiving    Sticky tab search & 14-day inactive tab archiving
========================================================================================
```
