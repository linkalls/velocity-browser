# Phase 5 — Scroll UX

Gecko 157 CompositorScrollDelegate SOURCE_USER_INTERACTION updates cross a 24dp direction threshold and drive a 150ms ValueAnimator; programmatic scrolling is ignored. ContentDelegate show/hide requests remain supported. GeckoView.setDynamicToolbarMaxHeight and setVerticalClipping communicate visible height. Bottom overlays the clipped bottom; top translates the same full-size GeckoView so its obscured bottom is clipped. Toolbar/view translation and native clipping avoid resizing the layout per frame. No session detach/recreation, reload, JS polling or scroll-time view reconstruction.

URL focus, IME, modal focus loss, home, TalkBack and the scroll-toggle setting pin the toolbar. Gecko shouldPinOnScreen prevents hiding during caret interaction. Android animator duration scale is respected. Layout/insets/placement changes update geometry separately.

API availability confirmed against published 157 source. Actual static/dynamic user swipes, hide/show direction and unchanged Gecko identity/epoch passed on API36. Full geometry and fixed footer/caret synchronization still require physical verification. Top rendering is particularly device-sensitive. Pixel 7 Pro frame-time, CPU/PSS, startup and jank comparison are NOT_MEASURED. No FPS claim.

## Changes and final validation

- `app/src/main/java/dev/velocity/browser/engine/GeckoSessionAdapter.kt`
- `tools/scroll-trace.sh`
- `tools/emulator-profile.sh`
- `.github/workflows/v04-profile.yml`

The final per-phase results and outstanding verification are recorded in [validation](validation.md). Executed final-source checks and physical-device limitations are distinguished there.
