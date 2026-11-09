# Screen-by-Screen UI/UX Concepts (Proposals, Not Current Behavior)

> **Status — proposed future screens.** The layouts, animation numbers, tap sequences, biometric locks, Library screens and `BottomSheetDialogFragment` references below are **design hypotheses**. They are NOT evidence that Chrome 157 or Vivaldi 8.3 uses these exact measurements, and most are NOT implemented by Velocity `main`. The validated implementation inventory is in [04-VELOCITY-AUDIT.md](04-VELOCITY-AUDIT.md). Reference browsers were not installed in this research. Add actual screenshots and behavior recordings before treating these wireframes as product acceptance tests.


This document sketches candidate component hierarchies, interaction states and 10 future screen concepts. Numeric dimensions are **design targets only**, subject to usability/physical-device validation at 320dp, 393dp, landscape and accessibility font scaling.

---

## Screen 1: Browser Home (Start Page / New Tab)

### 1.1 Purpose & Layout Structure
The primary launch screen when opening a new blank tab. Designed for instantaneous access to search and favorite bookmarks without clutter.

```
+-------------------------------------------------------------+
| [Status Bar - Edge-to-Edge]                                 |
+-------------------------------------------------------------+
|                                                             |
|                          VELOCITY                           |
|                    Fast. Private. Clean.                    |
|                                                             |
|       +---------------------------------------------+       |
|       | [Search] Search or enter URL            [x] |       |
|       +---------------------------------------------+       |
|                                                             |
|       +------+   +------+   +------+   +------+             |
|       |  Y!  |   |  G   |   | Wiki |   | Reddit|            |
|       +------+   +------+   +------+   +------+             |
|        Yahoo!     Google     Wiki       Reddit              |
|                                                             |
|       +------+   +------+   +------+   +------+             |
|       |  YT  |   | Amazon|  |  +   |   |      |             |
|       +------+   +------+   +------+   +------+             |
|       YouTube    Amazon     Add Site                        |
|                                                             |
+-------------------------------------------------------------+
| [<]   [>]      [   velocity.dev/home   ]    [(1)]    [...]  | <- Unified Bottom Bar
+-------------------------------------------------------------+
```

### 1.2 Component Hierarchy & Dimensions
1. **Brand Header:**
   - Monogram / Logo: 48x48dp vector.
   - App Title: 22sp SemiBold `text_primary`.
   - Subtitle: 13sp Regular `text_secondary`. Top margin: 48dp from status bar safe inset.
2. **Hero Search Field (Optional Quick Tap):**
   - Height: 48dp, corner radius: 24dp (pill).
   - Background: `surface_card`, border: 1dp `border_subtle`.
   - Placeholder text: "Search or enter address" (15sp `text_secondary`).
   - Tapping transitions immediately into **Screen 3: Address Editing**.
3. **Speed Dial Grid:**
   - 4 columns on phone (<500dp), 6 on tablet.
   - Tile: 56x56dp rounded rectangle (14dp radius), centered 28dp vector favicon.
   - Label: 12sp Regular `text_primary`, single line with ellipsis, centered below tile.
   - "Add Site" tile: Dotted outline border with a "+" vector icon.

---

## Screen 2: Normal Browsing (Active Webpage)

### 2.1 Layout Structure
The primary web content viewport with an auto-collapsing unified toolbar.

```
+-------------------------------------------------------------+
| [Status Bar - Transparent]                                  |
| [=================== Loading Progress Line (2.5dp) ========] |
+-------------------------------------------------------------+
|                                                             |
|                                                             |
|                    Active Web Content                       |
|                  (Cefrium Native Surface)                   |
|                                                             |
|                                                             |
+-------------------------------------------------------------+
| [<]   [>]      [ (i)  nytimes.com/tech  ]    [(4)]    [...]  | <- Consolidated Bottom Bar (56dp)
+-------------------------------------------------------------+
| [System Navigation Bar Inset]                               |
+-------------------------------------------------------------+
```

### 2.2 Proposed Interaction Rules & Scroll Dynamics
- **Proposed scroll-following animation (not implemented):**
  - When the user scrolls **down** on the page: The bottom bar translates down (`+56dp + navBarInset`) into the bottom inset.
  - On upward scroll, reveal the bar using an animation whose threshold and duration are selected **after profiling**. `8dp` and `200ms` in the first draft were guesses, not benchmarks.
  - The web content viewport does not resize on every scroll tick, eliminating reflow jank.
- **Swipe-to-Switch Gesture (proposal):**
  - Preserve current address-field swipe behavior while testing whether a wider toolbar gesture improves one-handed use. Do not intercept webpage horizontal swipes.

---

## Screen 3: Address Editing & Autocomplete

### 3.1 Layout Structure
The overlay that appears when the user taps the omnibox to type or search.

```
+-------------------------------------------------------------+
| [Back] [ https://example.com/long-path/page.html       [X] ] | <- Top Omnibox
+-------------------------------------------------------------+
| [Paste & Go: "https://github.com/linkalls"]                 | <- Quick Clipboard Chip
+-------------------------------------------------------------+
| [Search] example search query                   [Up-Arrow]  |
| [Globe]  example.com/subpage                    [Up-Arrow]  |
| [Tab]    Switch to tab: "Example Title"         [Tab Icon]  |
|                                                             |
+-------------------------------------------------------------+
|                   [ Virtual Keyboard (IME) ]                |
+-------------------------------------------------------------+
```

### 3.2 Interaction Sequence
1. User taps the omnibox.
2. The omnibox translates to the top of the screen (or docks above the keyboard depending on user preferences).
3. All text is automatically selected (`selectAll()`).
4. If a valid URL is detected in the system clipboard, a pill-shaped chip `Paste & Go: {url}` appears above the suggestion list.
5. Live suggestions update dynamically as the user types.

---

## Screen 4: Tab Switcher (Full-Screen Visual Grid)

### 4.1 Layout Structure

```
+-------------------------------------------------------------+
| [Close X]         Regular (8)   |   Private (2)       [Search]| <- Segmented Header
+-------------------------------------------------------------+
|                                                             |
|   +--------------------+     +--------------------+         |
|   | [ico] NYTimes  [x] |     | [ico] GitHub   [x] |         |
|   |                    |     |                    |         |
|   |    [Thumbnail]     |     |    [Thumbnail]     |         |
|   +--------------------+     +--------------------+         |
|                                                             |
|   +--------------------+     +--------------------+         |
|   | [Fold] Research [x]|     | [ico] YouTube  [x] |         |
|   | [2x2 Mosaic Grid]  |     |                    |         |
|   |                    |     |    [Thumbnail]     |         |
|   +--------------------+     +--------------------+         |
|                                                             |
+-------------------------------------------------------------+
| [Trash (2)]       [Close All]              [+ New Tab]      | <- Bottom Action Bar
+-------------------------------------------------------------+
```

### 4.2 Interaction Rules
- **Grid:** Existing source uses viewport-bound `ScrollView` holders. Keep it unless 20/100/500-tab benchmark data supports replacing it with `RecyclerView`. Candidate columns: 2 on phones, 3–4 on larger screens.
- **Card Interaction:** Single tap selects and focuses tab. Top-right `X` closes tab.
- **Swipe-to-Dismiss:** Horizontal swipe dismisses the card with an immediate undo toast at the bottom: `Tab closed [Undo]`.
- **Drag-and-Drop Stacking:** Dragging card A over card B highlights card B with an accent border; releasing creates a new tab group.

---

## Screen 5: Expanded Tab Group Sheet

### 5.1 Purpose & Layout
Opened when a user taps a 2x2 mosaic tab group card in the tab switcher.

```
+-------------------------------------------------------------+
|                                                             |
|     +-------------------------------------------------+     |
|     | [Color Dot]  Work Research (3 tabs)         [X] |     |
|     +-------------------------------------------------+     |
|     |                                                 |     |
|     |   +-------------------+   +-------------------+ |     |
|     |   | [ico] Doc 1   [x] |   | [ico] Doc 2   [x] | |     |
|     |   |   [Thumbnail]     |   |   [Thumbnail]     | |     |
|     |   +-------------------+   +-------------------+ |     |
|     |                                                 |     |
|     |   +-------------------+                         |     |
|     |   | [ico] Doc 3   [x] |                         |     |
|     |   |   [Thumbnail]     |                         |     |
|     |   +-------------------+                         |     |
|     |                                                 |     |
|     |   [+ Add Tab to Group]         [Ungroup All]    |     |
|     +-------------------------------------------------+     |
|                                                             |
+-------------------------------------------------------------+
```
- **Editable Title:** Tapping the group title converts it into an inline `EditText`.
- **Color Picker:** Tapping the color dot reveals 8 pastel color options for the group badge.
- **Drag-Out to Ungroup:** Dragging a card out of the sheet boundary removes it from the group.

---

## Screen 6: Private Browsing Mode

### 6.1 Purpose & Layout
Identical layout to the standard browsing and tab switcher screens, but styled with a **distinctive dark security palette** (`#0A0D12` canvas, `#1E2430` elevated bars, `#A78BFA` violet accents).
- **Incognito Mask:** Displayed permanently in the omnibox and top switcher bar.
- **Privacy requirements (future tests):** Explicitly decide screenshot policy (`FLAG_SECURE` as an optional proposal), verify storage/profile separation, and never write private thumbnails to disk. Do not claim those controls are all implemented now.

---

## Screen 7: Consolidated Main Menu (Bottom Sheet)

### 7.1 Layout Structure

```
+-------------------------------------------------------------+
|                       [Drag Handle]                         |
|  Velocity                                          [Close X]|
+-------------------------------------------------------------+
|    [<]       [>]       [Reload]     [Share]     [+ New Tab] | <- Circular Action Icons
|   Back       Fwd        Reload       Share        New Tab   |
+-------------------------------------------------------------+
| TABS                                                        |
|   [Mask]   New Private Tab                                  |
|   [Layers] Tab Groups Overview                              |
|   [Clock]  Recent & Closed Tabs                             |
+-------------------------------------------------------------+
| PAGE                                                        |
|   [Find]   Find in Page                                     |
|   [Globe]  Desktop Site                              [Toggle|
|   [Zoom]   Page Zoom (100%)                                 |
+-------------------------------------------------------------+
| BROWSER                                                     |
|   [Shield] Ad & Tracker Blocker                       [ON]  |
|   [Gear]   Settings                                         |
+-------------------------------------------------------------+
```
- **Proposed architecture:** an in-Activity sheet or `BottomSheetDialogFragment` with predictable Back/dismiss/IME handling. Current code still uses `Dialog`; this wireframe is not implemented.

---

## Screen 8: Settings Interface

### 8.1 Layout Structure
Clean, standard Android settings hierarchy with search bar at the top:
1. **Appearance:** Address bar position (Top vs. Bottom), Theme (System, Light, Dark), Show Tab Bar toggle.
2. **Tabs:** Close tab swipe gesture, Auto-close inactive tabs (Never, 1 week, 1 month), Tab stacking style.
3. **Privacy & Security:** Clear browsing data, HTTPS-only mode, Biometric lock for private tabs.
4. **Ad & Content Blocking:** Shield rules, cosmetic filter level, whitelist management.
5. **About Velocity:** Version info, Chromium build revision, Open source licenses.

---

## Screens 9 & 10: Library (Bookmarks/History) & Share Flows
- **Screen 9 (Library):** Unified 3-tab bottom sheet: **Bookmarks** | **History** | **Downloads**.
- **Screen 10 (Share Flow):** Clean action sheet offering "Copy URL", "Send to Device", "QR Code", and direct invoke of Android System Sharesheet (`Intent.ACTION_SEND`).


## Reality check before implementing any screen

1. Compare each proposed control against the source-backed implemented UI: Speed Dial editing, top/bottom address toggle, existing menu quick actions, the 2×2 group mosaic, current private-mode picker and real-CDP thumbnail path already exist.
2. Do not draw a bookmarks/history/downloads panel or biometric lock as if it works without implementing and testing the underlying data/security behavior.
3. Every new UI entry must have a real action, stable Back flow, keyboard/IME behavior, touch target and accessibility label.
4. Capture Velocity screenshots in the Android 16/17 CI; capture Chrome/Vivaldi reference screens separately only when their apps are actually installed.
