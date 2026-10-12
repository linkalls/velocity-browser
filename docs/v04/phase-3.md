# Phase 3 — Tab manager

A missing tabs_list preference now defaults to a compact vertical list; existing grid/list selection survives. Recycled rows show title, domain, optional small state marker, menu and 48dp close. Long press and the explicit row menu offer pin/unpin, close and organize. Grid remains optional. Search, private grouping, exact stable IDs, protected-close confirmation and URL-only Undo are retained. No thumbnails are allocated or persisted.

Validation: compilation and API36 pin/search/Undo/protected-close regression passed. Physical gestures, TalkBack and 200% font are UNVERIFIED.

## Changes and final validation

- `app/src/main/java/dev/velocity/browser/ui/TabSwitcherView.kt`

The final per-phase results and outstanding verification are recorded in [validation](validation.md). Executed final-source checks and physical-device limitations are distinguished there.
