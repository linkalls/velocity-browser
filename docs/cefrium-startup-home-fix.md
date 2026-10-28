# Offline home on launch — 2026-10-28

Restoring a saved session previously activated the selected remote page. If that
page was Google, merely opening the application navigated to Google again.

Startup now selects a dedicated offline home. Saved remote tabs remain dormant
until explicitly selected. A launcher-icon intent also selects home; returning
through Recents keeps the user's current page. Searches and site shortcuts still
work when explicitly submitted. An unfocused address editor cannot submit a
search through a stray editor action.

The home has its own persisted marker and reserved slot. All twelve saved user
tabs survive startup, including a full session; repeated starts reuse the same
home. Navigation from home creates a user tab instead of replacing home. Private
tabs are not used as the regular startup home or persisted.

Validation: nine TabRegistry JVM tests, 52 benchmark tooling tests, and twelve
manifest/release tooling tests pass. The Android regression seeds an actual saved
Google selection, verifies the pre-fix behavior, and checks fixed cold starts,
full-session preservation, explicit Google selection, and launcher return home.
Results: https://github.com/linkalls/velocity-browser/actions/runs/40695198424

Signed arm64 Preview 200055 (application source 1039d76):
https://github.com/linkalls/velocity-browser/actions/runs/40695198093/artifacts/12819411819
The previous audio-focus fix is included. These startup checks are emulator
checks; no physical-device performance claim is made.
