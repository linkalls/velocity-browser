# Phase 5 — Scroll UX

Gecko 157 ContentDelegate show/hide toolbar signals drive a 150ms ValueAnimator. GeckoView.setDynamicToolbarMaxHeight and setVerticalClipping communicate visible height. Bottom overlays the clipped bottom; top translates the same full-size GeckoView so its obscured bottom is clipped. Toolbar/view translation and native clipping avoid resizing the layout per frame. No session detach/recreation, reload, JS polling or scroll-time view reconstruction.

URL focus, IME, modal focus loss, home, TalkBack and the scroll-toggle setting pin the toolbar. Gecko shouldPinOnScreen prevents hiding during caret interaction. Android animator duration scale is respected. Layout/insets/placement changes update geometry separately.

API availability confirmed against published 157 source. Geometry, fixed footer/caret synchronization and actual scroll notification behavior still require emulator and physical verification. Top rendering is particularly device-sensitive. Pixel 7 Pro frame-time, CPU/PSS, startup and jank comparison are NOT_MEASURED. No FPS claim.
