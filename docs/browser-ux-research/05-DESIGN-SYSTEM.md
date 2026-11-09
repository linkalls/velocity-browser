# Unified Velocity Design System Specification

## Design Vision & Guiding Principles
The Velocity Browser Design System marries the **cognitive clarity and speed of Google Chrome**, the **spatial flexibility and customization of Vivaldi**, and **Velocity's core philosophy of lightweight, distraction-free performance**.

1. **Velocity First:** Every interaction must feel instantaneous. No heavy decorative blur shaders, unnecessary parallax animations, or multi-second transitions.
2. **Predictable & Ergonomic:** Primary navigation actions are anchored in the natural thumb zone. Critical touch targets must measure >= 48dp.
3. **Adaptive Density:** The UI adapts gracefully from narrow 320dp budget handsets to 393dp mainstream phones, foldables, and landscape tablets.
4. **Strict Isolation by Design:** Private browsing uses distinct visual surfaces and never leaks cached state or pixels into standard browsing.

---

## 1. Visual Foundation

### 1.1 Color Palette & Semantic Tokens

```
========================================================================
Token Name                Light Mode Hex       Dark / Private Mode Hex
========================================================================
surface_canvas            #F8F9FA              #0F141C (Dark) / #0A0D12 (Priv)
surface_card              #FFFFFF              #181F2A (Dark) / #151A22 (Priv)
surface_elevated          #F1F3F5              #202937 (Dark) / #1E2430 (Priv)
border_subtle             #E2E8F0              #2D3748 (Dark) / #323D4E (Priv)
border_active             #00B4D8              #38BDF8 (Accent Cyan)

text_primary              #0F172A              #F1F5F9 (High contrast)
text_secondary            #64748B              #94A3B8 (Medium contrast)
text_tertiary             #94A3B8              #64748B (Disabled / hint)

brand_accent              #0284C7              #38BDF8 (Velocity Sky Blue)
brand_accent_variant      #0369A1              #0284C7
private_accent            #8B5CF6              #A78BFA (Private Violet)
error_accent              #EF4444              #F87171 (Danger Red)
success_accent            #10B981              #34D399 (Security Green)
========================================================================
```

### 1.2 Surface Hierarchy & Elevation
- **Level 0 (Canvas):** Underlying viewport background (`surface_canvas`).
- **Level 1 (Docked Bars & Sheets):** Top Toolbar, Bottom Omnibox Bar, Tab Switcher Header (`surface_elevated` with 1dp `border_subtle` bottom/top outline).
- **Level 2 (Cards & Tiles):** Tab Switcher Preview Cards, Speed Dial Tiles, Menu List Cards (`surface_card`, 2dp elevation shadow or 1dp crisp border).
- **Level 3 (Overlays & Dialogs):** Context Menus, Toast SnackBar, Security Origin Info Sheet (`surface_elevated`, 8dp elevation shadow).

### 1.3 Typography Scale

```
========================================================================
Role          Font Family          Size (sp)   Weight        Line Height
========================================================================
Display       Inter / Roboto       22sp        SemiBold (600)  28sp
Title Large   Inter / Roboto       18sp        SemiBold (600)  24sp
Title Medium  Inter / Roboto       15sp        Medium (500)    20sp
Body Regular  Inter / Roboto       14sp        Regular (400)   20sp
Body Small    Inter / Roboto       12sp        Regular (400)   16sp
Caption/Badge Inter / Roboto       11sp        Medium (500)    14sp
Omnibox URL   Roboto Mono/Inter    14sp        Regular (400)   18sp
========================================================================
```

### 1.4 Corner Radii & Spacing Grid
- **Spacing Scale:** Standard 4dp grid: `4dp`, `8dp`, `12dp`, `16dp`, `20dp`, `24dp`, `32dp`.
- **Corner Radii:**
  - Omnibox Input Field: **22dp** (Full pill).
  - Tab Switcher Preview Cards: **16dp**.
  - Speed Dial Tiles: **14dp**.
  - Bottom Sheet Top Corners: **20dp**.
  - Badges & Buttons: **8dp**.

---

## 2. Core Components Specification

### 2.1 Consolidated Bottom Toolbar & Omnibox
Instead of stacking two separate rows (consuming 100dp), Velocity adopts a **unified 56dp bottom bar**:

```
+-----------------------------------------------------------------------+
|  [<]   [>]   [  (i)  velocity.dev/spec                ]   [(3)]  [...] |
| Back   Fwd   |--------- Omnibox Pill (40dp) ----------|   Tabs   Menu  |
+-----------------------------------------------------------------------+
```
- **Dimensions:** Total height: **56dp** + `WindowInsets.navigationBars().bottom`.
- **Elements:**
  1. `Back` button: 48x48dp touch target, 24dp icon vector. Disabled state: 38% alpha.
  2. `Forward` button: 48x48dp touch target, 24dp icon vector.
  3. `Omnibox Pill`: Flexible width (expands to fill available horizontal space). Height: 40dp.
     - Contains: Site Security Indicator (18dp), Formatted URL Text, and Clear/Voice icon.
  4. `Tab Counter`: 48x48dp touch target with a rounded rect badge displaying open tab count.
  5. `Overflow Menu`: 48x48dp touch target with three-dots vector icon.

### 2.2 Tab Switcher Card Architecture

```
+-----------------------------------------+
| [Favicon]  Tab Page Title          [X]  | <- Header Bar (36dp)
+-----------------------------------------+
|                                         |
|                                         |
|       Real Compositor Thumbnail         | <- 3:4 Proportional Aspect Ratio
|              (JPEG/WebP)                |
|                                         |
|                                         |
+-----------------------------------------+
```
- **Dimensions:** Width: dynamically calculated via `GridLayoutManager`. Height: `width * 1.33`.
- **Header:** 36dp height, contains 16dp high-resolution site favicon, 13sp title (truncated with ellipsis), and 28dp touch target `X` close button.
- **Active State:** 2dp solid `border_active` (`#38BDF8`) surrounding the card with 4dp outer halo.
- **Swipe Dismissal:** Card accepts horizontal swipe. Velocity threshold: 300dp/s or 40% card width delta triggers dismissal with a smooth scale-down exit animation.

### 2.3 Tab Group 2x2 Mosaic Card

```
+-----------------------------------------+
| [Folder] Research (4 tabs)         [X]  |
+-------------------+---------------------+
|   Tab 1 Preview   |    Tab 2 Preview    |
+-------------------+---------------------+
|   Tab 3 Preview   |    Tab 4 Preview    |
+-------------------+---------------------+
```
- When a tab group is collapsed in the main grid, it renders as a single card with a **2x2 mosaic grid** showing up to 4 mini-thumbnails of group members.
- Tapping the group card expands it into an dedicated in-sheet view displaying the full-size group cards and group title editor.

### 2.4 Speed Dial Tile (Start Page)
- **Grid Layout:** 4 columns on phones (viewport < 600dp), 6–8 columns on tablets.
- **Tile Dimensions:** 56x56dp rounded square (14dp radius) inside a 72x84dp cell including text caption.
- **Background:** Subtle tinted elevation surface with 1dp border.
- **Favicon Resolution:** 28x28dp centered vector or high-DPI raster icon.

---

## 3. Responsive & Multi-Form-Factor Adaptation

```
========================================================================================
Form Factor           Width Class   Columns   Toolbar Layout          Tab Strip
========================================================================================
Compact Phone (<360dp)    Compact     2       Consolidated Bottom     Hidden (Switcher only)
Standard Phone (360–480dp)Medium      2       Consolidated Bottom     Hidden (Opt-in toggle)
Foldable Unfolded (500–700dp)Expanded 3       Dual Top/Bottom         Opt-in Tab Strip
Tablet / Landscape (>700dp)  Expanded 4       Top-locked Desktop Bar  Persistent Tab Strip
========================================================================================
```

### Edge-to-Edge System Bars Rule
1. The app window always sets `WindowCompat.setDecorFitsSystemWindows(window, false)`.
2. All toolbar layouts must apply bottom insets:
   `view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom);`
3. Toolbars never clip beneath display cutouts (camera notches); horizontal safe padding is applied in landscape orientations.
