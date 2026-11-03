# Velocity GeckoView to Cefrium 0.13.0: migration tracker

Status: experimental, not ready to replace the GeckoView default.
Starting point: feat/chromium-cefrium-0.13 (PR #10).
This layer remains the separate dev.velocity.browser.chromium013 app.
Do not merge into main or migrate user profiles until release gates pass.

## Implemented in this branch

| Gecko behavior | Cefrium implementation | Limitations |
|---|---|---|
| Multiple tabs | TabRegistry and CefriumBrowser.createWithSurface per tab | 12-tab conservative limit; no eviction |
| Private browsing | createWithSurface(activity, true) | SDK manages shared off-the-record context |
| Private metadata isolation | Exclude private URLs/titles/IDs from preferences | Physical kill/restart not yet verified |
| Back, forward, reload | Native CEF browser navigation | No history/JS state persisted |
| Regular tab metadata | JSON snapshot with lazy re-creation | No live-form restoration |
| Tab visibility | GONE surface and setPageVisible(false) | Multi-surface stacking must be validated |
| Sharing | Android ACTION_SEND for HTTP(S) page | No blank-page sharing |
| Find in page | Cefrium find API | No next/previous controls |
| Network domain blocking | Native Cefrium request interceptor + bundled Velocity Lite and Peter Lowe domain lists | No CSS picker/cosmetic hide or custom per-site exceptions; built-in SDK EasyList is separate |
| Startup audit | Existing PR #10 service checks | Not sandbox runtime proof |

No cross-engine cookie/database migration is attempted. GeckoView and Cefrium
sessions are incompatible. Later migration must validate versioned URL
metadata. Private data must never be exported.

## Required parity gates before switching default

1. Port Velocity's bottom toolbar, home shortcuts, accessible tab switcher,
   themes, search settings, downloads, site permissions and prompt UX.
2. Expand the implemented Cefrium network-only Velocity Block Lite
   port to reviewed site exceptions, user rules, cosmetic CSS and picker.
   Gecko AdGuard XPI cannot execute inside Chromium/CEF.
   The bundled Peter Lowe list is a dated snapshot, not an update service.
   The extra domain blocker defaults ON for normal/private; users can toggle
   it independently of Cefrium's built-in EasyList blocker.
3. Validate scoped file input, downloads, geolocation/camera/mic prompts,
   HTTP auth, Passkeys, WebRTC, media playback, popups, and external intents.
4. Verify renderer recovery, Surface lifecycle, orientation/IME,
   accessibility and memory pressure on a Pixel 7 Pro.
5. Benchmark Speedometer 3.1, scroll/frame times, launch, RAM and battery
   against the same current GeckoView baseline on a physical device.
6. Validate runtime isolated UIDs, seccomp filter counts, certificate
   errors, site permissions and private-profile boundaries.

Java-less native-only renderer work is independent. The official 0.13.0
Cefrium AAR lacks native-only exports, so use isolated Java services.

## Verification commands

Existing chromium-013-experiment.yml CI executes unit tests, builds debug
APK, audits merged service Manifest and boots API 36/37 emulators.
Emulator startup does NOT verify tab compositing or physical sandbox rules.
Add a multi-surface instrumentation test before default promotion.

    cd experiments/chromium-013
    gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug
