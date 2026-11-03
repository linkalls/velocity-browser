# Velocity Preview: deliberate browser UX

Branch feat/cefrium-tabs-core. Experimental, not production.

## Changes

- Content-first canvas and bottom thumb-reachable URL/search pill.
- Android native edit gestures and keyboard Go action, hiding unused toolbar when typing.
- Swipe the unfocused address field horizontally to switch tabs.
- Compact bottom tab sheet with active-tab indication, URL, close, normal/private new tab actions.
- Closing a tab exposes a 5-second undo that reopens its URL, not its form or JS state.
- Consistent restrained dark palette and 48dp navigation controls; no novelty gradients.
- Browser Activity is the actual launcher; separate diagnostics remain available from menu and ADB.
- Updates menu opens the signed-preview workflow; no silent external APK installation.

## Real device verification still required

1. Pixel 7 Pro: Japanese IME, input position, Go key, keyboard open/close, touch selection.
2. Swipe-to-switch without interfering with native text cursor/selection.
3. Twelve-tab sheet on TalkBack, large fonts, screen rotation and gesture navigation.
4. Private tab undo does not persist secret URL or title.
5. Real Chromium Surface z-order, tab visibility, recovery, and memory.
6. Benchmark scrolling, startup, battery, RAM against GeckoView and alternatives.

The Gecko production app has not been modified. Reusable UI components
and security features must still be ported before switching its engine.

## Background tabs and idle renderers

Only the foreground Cefrium tab resumes its compositor and media on Activity
resume. Switching away calls `onPause()` on the old engine, rather than
merely hiding its Surface. After tab closure or under Android memory pressure,
`CefriumBrowser.reapUnusedRenderers()` asks the SDK to reclaim zero-frame
renderer processes only; it never kills renderers that host live tabs.
Physical-device stress tests with 1/5/12 tabs remain outstanding.
