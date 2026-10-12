# Phase 6 — QA and optimization

Regression must cover compact menu routes, list/grid pin/search/Undo, private separation, protected draft/cancel, theme changes, metadata restore, file upload/download, HTTP auth, WebAuthn abort/invalid RP and cosmetic/picker cleanup. The existing cases are retained and adapted only for the explicit menu and row-menu routes.

Device target: Pixel 7 Pro. It is not attached to this environment. Physical light/dark, portrait/landscape, 200% font, TalkBack, system gestures, resume, hardware Passkey and pre/post startup/PSS/jank comparison remain UNVERIFIED/NOT_MEASURED. Emulators do not establish physical-device performance.

Each phase's implementation/source status is recorded separately; passing compilation is not a device validation result.

## Changes and final validation

- `app/src/androidTest/java/dev/velocity/browser/V04UiIntegrationTest.kt`
- `.github/workflows/android.yml`

The final per-phase results and outstanding verification are recorded in [validation](validation.md). Executed final-source checks and physical-device limitations are distinguished there.
