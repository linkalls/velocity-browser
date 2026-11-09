# Usability Benchmarking & Interaction Flow Analysis

## Methodology & Metrics
We evaluate 15 fundamental browser workflows across **Google Chrome**, **Vivaldi Browser**, and **Velocity Browser (Current Prototype)**. Each flow is measured and evaluated along 6 quantitative and qualitative dimensions:
1. **Tap Count:** Number of physical screen taps required.
2. **Gesture Count & Complexity:** Swipes, long-presses, or drag-and-drops.
3. **One-Handed Reachability:** Zone access on a standard 6.7-inch display (Top Danger Zone vs. Bottom Natural Thumb Zone).
4. **Cognitive Load:** Visual search time, hierarchy depth, and disambiguation requirements.
5. **Visual & Haptic Feedback:** Intermediate state rendering, press ripples, and transition smoothness.
6. **Error Recovery & Reversibility:** Availability of undo toasts, non-destructive back navigation, and confirmation sheets.

---

## Workflow Evaluations

### Workflow 1: Open a New Tab
- **Chrome:**
  - *Top Bar:* Tap Switcher (1) -> Tap "+" in top-left (2). *Total: 2 taps.* (Top danger zone).
  - *Shortcut:* Long-press Switcher (1 gesture) -> Select "New Tab" (1 tap).
- **Vivaldi:**
  - *Bottom Bar:* Tap "+" in bottom bar directly (if on Start Page) or Tap Switcher (1) -> Tap "+" (2). *Total: 1–2 taps.* (Natural thumb zone).
- **Velocity Current:**
  - Tap Menu button (1) -> Tap "新規" in top quick actions (2). *Total: 2 taps.*
- **Velocity Proposed Target:**
  - Long-press Tab Counter button -> popup with "New Tab" and "New Private Tab" (1 drag/tap).
  - Direct "+" button inside the bottom Tab Switcher toolbar (1 tap once in switcher).

---

### Workflow 2: Search for a Website
- **Chrome:**
  - Tap Omnibox (1) -> Keyboard opens immediately -> Type query -> Tap keyboard "Go" (2). *Total: 2 taps + typing.*
- **Vivaldi:**
  - Tap Omnibox (1) -> Optional: tap search engine icon above keyboard (e.g. DuckDuckGo) -> Type -> Tap "Go" (2). *Total: 2–3 taps.*
- **Velocity Current:**
  - Tap address bar (1) -> Clear existing text manually if not auto-selected -> Type -> Tap "Go" (2).
- **Velocity Proposed Target:**
  - Single tap on Omnibox auto-selects all text and exposes a floating "Paste & Go" chip if clipboard contains a valid URL or search phrase.

---

### Workflow 3: Switch Between Two Recent Tabs
- **Chrome:**
  - *Gesture:* Horizontal swipe across the bottom/top omnibox directly slides into the previous/next tab. *Total: 1 simple horizontal swipe.*
  - *Switcher:* Tap tab counter (1) -> Tap tab card (2). *Total: 2 taps.*
- **Vivaldi:**
  - *Gesture:* Swipe left/right on bottom toolbar or address bar. *Total: 1 swipe.*
  - *Switcher:* Tap tab switcher icon in bottom-right (1) -> Tap card (2). *Total: 2 taps.*
- **Velocity Current:**
  - Swipe gesture is implemented on the root view (`touchDetector`), but requires a >80dp horizontal swipe and often conflicts with webpage panning.
- **Velocity Proposed Target:**
  - Restrict the horizontal tab-switching swipe strictly to the toolbar container (top and bottom bars) rather than the web content surface, eliminating webpage scroll interference.

---

### Workflow 4: Find a Specific Tab Among 100+ Tabs
- **Chrome:**
  - Tap tab switcher (1) -> Tap Search icon in top bar (2) -> Type keywords -> Matching tabs filter live in the grid -> Tap match (3). *Total: 3 taps.* Inactive tabs are auto-collapsed, reducing visual clutter.
- **Vivaldi:**
  - Tap tab switcher (1) -> Tap Search icon (2) -> Type query -> Tap match (3). *Total: 3 taps.*
- **Velocity Current:**
  - Tap tab switcher (1) -> Scroll through unbounded `BoundedScrollView` containing 100+ inflated views. No search input exists. High risk of UI stuttering (OOM or skipped frames).
- **Velocity Proposed Target:**
  - Add a sticky search field at the top of the Tab Switcher.
  - Convert `BoundedScrollView` into a recycled `RecyclerView` to ensure steady 60/120fps scrolling even with 500+ open tabs.

---

### Workflow 5: Create and Manage a Tab Group
- **Chrome:**
  - Tap switcher (1) -> Long-press & drag card onto another card (1 drag-and-drop). Group created with automatic 2x2 mosaic preview.
- **Vivaldi:**
  - Tap switcher (1) -> Drag tab onto another (1 drag-and-drop) -> Opens stack preview -> Edit name/color.
- **Velocity Current:**
  - Tap switcher (1) -> Tap "..." on a card (2) -> Tap "グループに追加" (3) -> Type group name in an `AlertDialog` (4). *Total: 4 taps + modal dialog.*
- **Velocity Proposed Target:**
  - Support both direct drag-and-drop grouping in the grid and a 2-tap context menu action.
  - Render an interactive group detail sheet with inline rename and color picker chips.

---

### Workflow 6: Reopen a Recently Closed Tab
- **Chrome:**
  - Immediate: Tap "Undo" toast within 4 seconds (1 tap).
  - Later: Tap Menu (1) -> Recent tabs (2) -> Tap entry (3). *Total: 3 taps.*
- **Vivaldi:**
  - Tap tab switcher (1) -> Tap "Trash" segmented tab (2) -> Tap closed tab (3). *Total: 3 taps.*
- **Velocity Current:**
  - Immediate: Undo toast appears for 3.5 seconds with "元に戻す" action (1 tap).
  - Later: Open Tab Overview (1) -> Tap "最近閉じたタブ" icon (2) -> Reopen from list (3). *Total: 3 taps.* (Working well, matches Vivaldi closely).
- **Velocity Proposed Target:**
  - Maintain the RAM-only recently closed tab registry; enhance the list view with relative timestamp ("5m ago") and favicon badges.

---

### Workflow 7: Enter and Exit Private Browsing
- **Chrome:**
  - Enter: Tap Menu (1) -> "New Incognito tab" (2). Or Tap Switcher (1) -> Tap Incognito mask tab (2) -> Tap "+" (3).
  - Exit: Tap Switcher (1) -> Tap Regular tabs tab (2) -> Select active tab (3).
- **Vivaldi:**
  - Enter: Tap Switcher (1) -> Swipe or tap Private tab icon (2) -> Tap "+" (3).
  - Exit: Tap Switcher (1) -> Tap Regular tabs icon (2).
- **Velocity Current:**
  - Enter: Tap Menu (1) -> Tap "新しい非公開タブ" (2).
  - Exit: Tap Tab Switcher (1) -> Tap "通常タブ画面" button (2) -> Tap tab card (3).
- **Velocity Proposed Target:**
  - Segmented top header in the tab switcher: **Regular [Count]** | **Private [Count]**.
  - Distinct dark visual palette (`#12161C`) with prominent purple/indigo accents for private mode.

---

### Workflow 8: Share a Webpage
- **Chrome:**
  - Tap Menu (1) -> Tap "Share..." (2) -> Android system share sheet launches (3). *Total: 2 taps.* (Or directly from active omnibox chip).
- **Vivaldi:**
  - Tap Menu (1) -> Tap "Share" in quick action bar (2). *Total: 2 taps.*
- **Velocity Current:**
  - Tap Menu (1) -> Tap "共有" in top quick actions (2) -> Android Intent launched. *Total: 2 taps.*
- **Velocity Proposed Target:**
  - Maintain 2-tap share in the main menu; also add a dedicated Share icon in the active omnibox action row.

---

### Workflow 9: Switch Between Desktop and Mobile Modes
- **Chrome:**
  - Tap Menu (1) -> Checkbox "Desktop site" (2). Page reloads. *Total: 2 taps.*
- **Vivaldi:**
  - Tap Menu (1) -> Toggle "Desktop site" (2). Page reloads. *Total: 2 taps.*
- **Velocity Current:**
  - Tap Menu (1) -> Scroll to "PC版サイト" (2) -> Tap toggle (3). *Total: 2–3 taps.*
- **Velocity Proposed Target:**
  - Position "Desktop site" toggle in the first section of the bottom sheet menu with an instant visual switch.

---

### Workflow 10: Open Bookmarks or History
- **Chrome:**
  - Tap Menu (1) -> Tap "Bookmarks" or "History" (2). *Total: 2 taps.*
- **Vivaldi:**
  - Tap "Panels" icon in bottom bar (1) -> Opens sliding drawer with Bookmarks, History, Notes, Downloads. *Total: 1 tap.*
- **Velocity Current:**
  - History exists only as recently closed tabs in the switcher. Bookmarks are partially represented by Start Page speed dials.
- **Velocity Proposed Target:**
  - Provide a cohesive "Library" bottom sheet grouping Bookmarks, History, and Downloads accessible from the main menu and start page.

---

### Workflow 11: Change Address Bar Position (Top vs. Bottom)
- **Chrome:**
  - Tap Menu (1) -> Settings (2) -> Address Bar (3) -> Select Top or Bottom (4). (Also supported via long-press on iOS, rolling out to Android).
- **Vivaldi:**
  - Tap Menu (1) -> Settings (2) -> Tabs (3) -> Toggle "Address Bar At Bottom" (4).
- **Velocity Current:**
  - Tap Menu (1) -> Settings (2) -> Toggle "アドレスバーを下に配置" (3) -> Activity instantly shifts layout without restart. *Total: 3 taps.*
- **Velocity Proposed Target:**
  - Keep this 3-tap settings toggle; add support for long-pressing an empty space on the address bar to reveal "Move to bottom / top".

---

### Workflow 12: Access Site Information & Permissions
- **Chrome:**
  - Tap "Tune / Sliders" icon in omnibox (1) -> Sheet opens showing connection security, cookies, and site permissions (location, notifications).
- **Vivaldi:**
  - Tap Shield / Lock icon in omnibox (1) -> Modal opens showing SSL certificate details, tracker blocking stats, and origin permissions.
- **Velocity Current:**
  - Tap lock/info icon (1) -> Shows custom dialog with host name and dummy connection details.
- **Velocity Proposed Target:**
  - Standardize into a clean Material 3 bottom sheet showing HTTPS status, Cefrium cosmetic blocker rule statistics, and clear cookies/site data action.

---

### Workflow 13: One-Handed Usability
- **Chrome:**
  - When top toolbar is active: Reachability is poor on modern tall screens (6.5"+). Requires shifting hand grip to tap tabs or omnibox.
  - When bottom toolbar is active: Reachability is excellent; 90% of navigation happens in the bottom 40% of the screen.
- **Vivaldi:**
  - Highly optimized: bottom address bar + bottom navigation bar concentrates all critical actions at thumb level.
- **Velocity Current:**
  - Bottom layout option exists, but the bottom area stacks two separate bars (49dp + 51dp = 100dp), consuming excessive vertical screen space.
- **Velocity Proposed Target:**
  - Consolidate bottom layout into a single, compact 56dp bar that embeds navigation icons and a compact omnibox.

---

### Workflow 14: Interaction with Virtual Keyboard (IME) Open
- **Chrome:**
  - Seamless animated transition via `WindowInsetsAnimationCompat`. The suggestion list resizes smoothly without jumpiness or blank frames.
- **Vivaldi:**
  - Keyboard accessory view sits directly on top of the keyboard with search engine shortcuts and autofill suggestions.
- **Velocity Current:**
  - No `WindowInsetsAnimationCompat` listener. The layout relies on default window resizing, which can cause jarring jumps and overlap on Android 15+.
- **Velocity Proposed Target:**
  - Implement an insets-aware layout coordinator that translates the omnibox and suggestions smoothly with IME velocity.

---

### Workflow 15: Application Resume After OS Process Termination
- **Chrome:**
  - Restores active tab immediately from saved disk cache snapshot. Inactive background tabs remain dormant until selected.
- **Vivaldi:**
  - Restores full tab list. Shows Start Page or active tab based on "Continue where you left off" preferences.
- **Velocity Current:**
  - Restores tab list from `cefrium_tabs_v1` SharedPreferences. Limits live resident CefriumBrowser instances to `MAX_RESIDENT_BROWSERS = 6`.
- **Velocity Proposed Target:**
  - Maintain the 6-renderer cap to prevent OOM on lower-tier Android devices while preserving unlimited dormant tab metadata in the registry.

---

## Usability Benchmarking Summary Table

| Workflow | Chrome (Android) | Vivaldi (Android) | Velocity (Current) | Velocity (Target Proposed) |
|---|---|---|---|---|
| **1. Open New Tab** | 2 taps (Top) / 1 long-press | 1–2 taps (Bottom) | 2 taps (Menu modal) | **1 long-press / 1 tap** |
| **2. Search Web** | 2 taps (Top) | 2–3 taps (Bottom/Top) | 2 taps | **1 tap + Paste&Go** |
| **3. Switch Recent Tabs** | 1 swipe on bar | 1 swipe on bar | 1 wide swipe (conflicts) | **1 toolbar swipe (no conflict)** |
| **4. Find in 100+ Tabs** | 3 taps (Live search) | 3 taps (Live search) | Infeasible (Linear scroll) | **2 taps (RecyclerView search)** |
| **5. Create Tab Group** | 1 drag-and-drop | 1 drag-and-drop | 4 taps (Modal dialog) | **1 drag / 2 taps** |
| **6. Reopen Closed Tab** | 1 tap (Toast) / 3 taps | 3 taps (Trash tab) | 1 tap (Toast) / 3 taps | **1 tap (Toast) / 3 taps** |
| **7. Private Mode Toggle** | 2 taps (Segmented) | 2 taps (Switcher tab) | 2 taps | **1 tap segmented switcher** |
| **8. Share Webpage** | 2 taps | 2 taps | 2 taps | **2 taps** |
| **9. Toggle Desktop Mode**| 2 taps | 2 taps | 3 taps (Deep scroll) | **2 taps (Top section)** |
| **10. Access Bookmarks** | 2 taps | 1 tap (Bottom panel) | N/A | **2 taps (Library sheet)** |
| **11. Change Bar Position**| 4 taps | 4 taps | 3 taps | **3 taps / 1 long-press** |
| **12. Site Information** | 1 tap | 1 tap | 1 tap | **1 tap (Clean sheet)** |
| **13. One-Hand Reach** | Moderate / High | Very High | Moderate (Double bar) | **Very High (Unified bar)** |
| **14. Keyboard Transitions**| Fluid animated | Fluid accessory | Abrupt jump | **Fluid WindowInsets** |
| **15. Process Kill Resume**| Seamless | Seamless | Working (Metadata only)| **Seamless + Thumbnail cache** |
