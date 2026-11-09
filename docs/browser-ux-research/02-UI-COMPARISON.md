# Exhaustive Mobile Browser UI & Component Comparison Matrix

## 1. Main Browsing Screen & Chrome Dimensions

| Dimension / Component | Chrome (Android 14–16) | Vivaldi (Android 8.3) | Samsung Internet (v26) | Velocity (Current Exp-013) | Velocity Target (Proposed) |
|---|---|---|---|---|---|
| **Top Toolbar Height** | 56dp | 56dp (without Tab Bar) | 56dp (if top layout) | 56dp | **56dp** |
| **Desktop Tab Bar Height** | N/A on phones; 40dp tablet | 36dp (phone/tablet toggle) | N/A on phones | None | **36dp (opt-in toggle)** |
| **Bottom Navigation Bar Height** | 56dp (when enabled) | 48dp–52dp | 54dp | 49dp (actions) + 51dp (address) = 100dp | **56dp consolidated** |
| **Omnibox Height** | 44dp (pill shape) | 40dp (rounded rect) | 42dp (rounded rect) | 48dp | **44dp (pill shape)** |
| **Omnibox Corner Radius** | 22dp (pill) | 8dp | 21dp (pill) | 16dp | **22dp (Material 3 pill)** |
| **Minimum Touch Target** | 48x48dp | 44x44dp | 48x48dp | 42x42dp / 43x43dp (Non-standard) | **48x48dp strictly enforced** |
| **Status Bar Treatment** | Transparent (`enableEdgeToEdge`) | Translucent / custom theme | Dynamic color | Opaque / unpadded | **Transparent with WindowInsets** |
| **System Nav Bar Treatment** | Transparent gesture handle | Transparent gesture bar | Transparent gesture bar | Opaque / clipped | **Transparent with WindowInsets** |
| **Loading Progress Bar** | 2dp top progress (accent) | 2dp top progress (accent) | 2.5dp top progress | 2dp | **2.5dp (Material motion easing)** |

---

## 2. Address Bar & Search Interface (Omnibox)

### 2.1 Resting vs. Active State
- **Chrome:**
  - *Resting:* Strips scheme (`https://`), shows domain with bolded eTLD+1 (`nytimes.com`), secondary path. Tapping security icon opens security certificate sheet and origin permissions.
  - *Active:* Replaces with full text, selects all text, launches IME. Shows Share, Copy, and Edit quick-action chips immediately below the omnibox.
  - *Autocomplete:* Top search match + history matches + "Switch to this tab" intent buttons for duplicates.
- **Vivaldi:**
  - *Resting:* Displays full or simplified domain depending on settings. Displays dedicated content-blocker shield and customizable action button.
  - *Active:* Retains full URL string, brings up search engine shortcuts bar along the top of the keyboard. Allows engine switching with 1 tap or prefix nicknames.
- **Velocity (Current):**
  - *Resting:* Single `EditText` showing raw URL (`address.setText(url)`).
  - *Active:* Focuses the field, but does not present search suggestions, clipboard chips, or one-tap copy/share tools.
- **Velocity (Target Recommendation):**
  - Implement two visual layers: a resting formatted `TextView` (highlighting domain) that transitions on click to an active `EditText` with Select-All.
  - Render a top action strip when active: `Paste & Go` (if URL in clipboard), `Copy`, `Clear`.

---

## 3. Tab Switcher & Tab Management Architecture

### 3.1 Layout & Grid Metrics

| Aspect | Chrome Android | Vivaldi Android | Samsung Internet | Velocity Current | Velocity Proposed Target |
|---|---|---|---|---|---|
| **View Type** | Vertical RecyclerView Grid | Vertical Grid + Horizontal Pager | Grid / List / 3D Stack | BoundedScrollView + LinearLayout | **Lazy Vertical Grid (RecyclerView)** |
| **Columns (Phone <500dp)** | 2 Columns | 2 Columns | 2 Columns (or 1 in List) | 1 Column (<350dp), 2 Columns (>=350dp) | **2 Columns (Responsive to 320dp)** |
| **Columns (Tablet >=600dp)**| 3–4 Columns | 3–5 Columns | 3–4 Columns | 3–4 Columns | **3–4 Columns** |
| **Card Aspect Ratio** | ~3:4 (~0.75) | ~3:4 (~0.75) | ~3:4 or List row | Hardcoded heights (205dp / 168dp) | **1:1.33 (~3:4) proportional** |
| **Card Corner Radius** | 16dp | 8dp | 12dp | 12dp | **16dp** |
| **Elevation / Shadow** | 2dp with 1dp border | Flat with active border | 2dp shadow | 1dp border (no elevation) | **2dp elevation + 1dp outline** |
| **Active Tab Indicator** | Bold blue outline (2dp) | Accent outline + badge | Blue highlight border | Cyan border (`#00B4D8`) | **2dp Theme Accent border** |
| **Close Affordance** | Top-right 24dp `X` icon | Top-right 24dp `X` icon | Top-right `X` button | Top-right `X` button | **Top-right 28dp target `X`** |
| **Swipe-to-Dismiss** | Horizontal swipe (L/R) | Configurable horizontal swipe | Horizontal swipe | None (Click only) | **Horizontal swipe with spring friction** |
| **Group Preview Design** | 2x2 thumbnail mosaic | 2x2 mosaic + Stacks pane | Folder badge icon | 2x2 mini previews | **2x2 mosaic with fallback favicons** |

### 3.2 Thumbnail Capture & Cache Lifecycle
- **Chrome:** Composited directly from Skia/GPU layer into native memory bitmap. Scaled to device resolution and saved to disk cache asynchronously as WebP. Memory cache handles currently visible cards (~6 cards).
- **Vivaldi:** Uses Chromium compositor capture. Inactive tabs unload bitmap textures from GPU RAM and load disk JPEGs on scroll.
- **Velocity Current:** Captured via CDP (`Page.captureScreenshot`) or PixelCopy fallback. Scaled down to 320px width JPEG (quality 64). Stored in `files/tab_previews/{UUID}.jpg` with 12MB in-memory `LruCache`.
- **Velocity Target Recommendation:**
  - Maintain the CDP / native compositor JPEG pipeline.
  - Implement placeholder shimmering skeleton during capture or asynchronous decode.
  - Add explicit downsample decoding (`BitmapFactory.Options.inSampleSize`) based on exact card pixel dimensions.

---

## 4. Private Browsing Architecture

| Feature | Chrome Incognito | Vivaldi Private | Velocity Current | Velocity Proposed Target |
|---|---|---|---|---|---|
| **Palette** | Fixed Dark (`#1F1F1F`) | Fixed Dark (`#242424`) | Dark overlay (`#0B111A`) | **Fixed Dark (`#12161C`)** |
| **Switcher Access** | Segmented Tab Switcher Bar | Horizontal Swipe / Switcher Tab | Button toggle | **Segmented Top Header Bar** |
| **Thumbnail Disk Persistence** | Strictly blocked (RAM-only) | Strictly blocked | Blocked in TabPreviewStore | **Strictly blocked (RAM-only)** |
| **Window Security Flag** | `FLAG_SECURE` enabled | `FLAG_SECURE` enabled | Not enabled | **`FLAG_SECURE` enabled** |
| **Biometric Lock Support** | Yes ("Lock Incognito on exit") | Yes ("Lock Private tabs on exit") | None | **BiometricPrompt API integration** |
| **Cookie/Storage Isolation** | Separate non-persistent profile | Separate non-persistent profile | Ephemeral Cefrium profile | **Isolated Cefrium RequestContext** |

---

## 5. Start Page & Speed Dial

| Feature | Chrome New Tab Page | Vivaldi Speed Dial | Velocity Current | Velocity Proposed Target |
|---|---|---|---|---|---|
| **Branding / Header** | Google "G" Logo / Doodle | Optional Vivaldi wordmark | "Velocity" + "Browse your way" | **Clean Velocity Monogram + Search** |
| **Tile Grid Layout** | 2 rows of 4 circular favicons (8 total) | Configurable grid (S/M/L/List) | 2x3 or 2x4 grid tiles | **Adaptive Grid (4 columns on mobile)** |
| **Tile Appearance** | 48dp circle with 24dp favicon | Rounded rect with site snapshot | 60x60dp tile with 23dp icon | **56x56dp rounded rect (16dp radius)** |
| **Custom Tile Addition** | Yes (Add Shortcut modal) | Yes (Bookmarks folder link) | Yes ("ショートカットを追加" dialog) | **Modern Bottom Sheet Add Dialog** |
| **Background Wallpaper** | Static or curated daily theme | Preset images, solid colors, custom gallery | None (Solid `#0F141C`) | **Subtle dynamic gradient / custom wallpaper** |
| **Feed / Cards Integration** | Google Discover Feed | None (Clean speed dial focus) | None | **None (Velocity focuses on speed/privacy)** |

---

## 6. Overflow Menu & Settings

### 6.1 Menu Structure Comparison
- **Chrome:** Single vertical list in a top-anchored popup menu. Includes a top horizontal quick-actions row: Forward, Bookmark, Download, Page Info, Reload.
- **Vivaldi:** Bottom-anchored pop-up or bottom sheet. Features a customizable 5-icon quick action bar, followed by grouped sections for Tab actions, Page tools, and Browser tools.
- **Velocity Current:** Dual menu implementation: `showMenu()` (quick icons + 10 list items) and `showAdvancedMenu()` (experimental toggles). Both are launched in modal dialogs.
- **Velocity Proposed Target:**
  - Consolidate into a single **Material 3 Bottom Sheet Menu**.
  - Top row: 5 circular quick actions: **Back**, **Forward**, **Reload**, **Share**, **New Tab**.
  - Section 1: **Tabs** (New Private Tab, Tab Groups, Recent Tabs).
  - Section 2: **Page** (Find in Page, Desktop Mode toggle, Page Zoom, Copy URL).
  - Section 3: **Browser** (Settings, Adblock Shield, Downloads).
