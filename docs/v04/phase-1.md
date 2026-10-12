# Phase 1 — Design system

The supplied light/dark palette, 48dp target, 16/13sp typography and 150ms animation tokens use Android Views and system fonts. No UI dependency or engine change. Existing explicit themes/preferences are preserved.

GeckoView Stable 157 public source provides GeckoView.setDynamicToolbarMaxHeight, setVerticalClipping, shouldPinOnScreen and ContentDelegate.onShowDynamicToolbar/onHideDynamicToolbar. Vertical clipping is documented for the bottom edge; top-position compatibility requires separate validation. No JS scroll polling is planned.

Validation: public source inspected, compilation passed, API36 emulator theme/large-font regression passed. Pixel 7 Pro visual acceptance remains UNVERIFIED.

## Changes and final validation

- `app/src/main/res/values/colors.xml`
- `app/src/main/java/dev/velocity/browser/ui/DesignTokens.kt`
- `app/src/main/java/dev/velocity/browser/ui/BrowserIconButton.kt`

The final per-phase results and outstanding verification are recorded in [validation](validation.md). Executed final-source checks and physical-device limitations are distinguished there.
