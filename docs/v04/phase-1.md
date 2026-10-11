# Phase 1 — Design system

The supplied light/dark palette, 48dp target, 16/13sp typography and 150ms animation tokens use Android Views and system fonts. No UI dependency or engine change. Existing explicit themes/preferences are preserved.

GeckoView Stable 157 public source provides GeckoView.setDynamicToolbarMaxHeight, setVerticalClipping, shouldPinOnScreen and ContentDelegate.onShowDynamicToolbar/onHideDynamicToolbar. Vertical clipping is documented for the bottom edge; top-position compatibility requires separate validation. No JS scroll polling is planned.

Validation: public source inspected; compilation and palette/contrast device regression pending. Pixel 7 Pro and 200% font rendering remain UNVERIFIED.
