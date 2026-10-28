# Velocity Chromium · Cefrium 0.16.0

Independent Java-renderer experiment; applicationId `dev.velocity.browser.chromium013`.

See [migration, build instructions and verification](../../docs/chromium-cefrium-013.md).
The 0.9.4 baseline is in `../chromium/`; this root uses the 0.16.0 SDK and Gradle plugin.

The tabbed/private Cefrium migration is tracked in
[the migration tracker](../../docs/chromium-cefrium-013-migration.md).
This is not the default Velocity browser until device verification.

Network-only Velocity Block Lite uses its original domain-rule subset, the
snapshot from 2026-10-10, and the retained attribution in assets/licenses.
This is not cosmetic blocking or Gecko XPI compatibility. The separate
Cefrium built-in EasyList behavior is not controlled by this switch.

The Preview browser now has a compact bottom address bar and native tab
management. See the [UX plan](../../docs/velocity-cefrium-preview-ux.md)
and [signed APK update guide](../../docs/velocity-cefrium-signing.md).
The fixed-key APK is not upgrade-compatible with older debug-signed builds.

## Cefrium 0.16.0 update (2026-10-28)

The SDK and Gradle plugin are upgraded together from 0.14.0 to 0.16.0.
Chromium remains 156.0.8078.17. The application ID, profile paths and permanent
Preview signing configuration are unchanged.

The [upstream release notes](https://cefrium.com/releases/#v0.16.0) report removal
of automatic Google ListAccounts requests, AI Mode eligibility checks and
default-search-engine preconnects. This is an upstream claim pending a Velocity
NetLog/device audit, not a claim that the app makes no background connections.
Documented engine component updates and user-initiated navigation are separate.

0.16 also introduces lazy engine initialization, native library prefetch and
app-zygote preloading. Existing provider/activity phase labels describe their
measured intervals; provider time alone no longer represents native startup.
Do not compare old startup or custom-prefetch results directly with this SDK: its
built-in prefetch is enabled by default, including in custom-prefetch experiments.
The custom prefetch window was selected for the 0.14 ELF and requires revalidation.
No new speed or RAM claim is made.

Validation gates remain APK build, packaged renderer-manifest audit, and real
DOM+JavaScript smoke tests on Android 16 and 17, followed by physical-device
regular/private-tab, profile migration and network checks. Production readiness
and full browser feature parity are still pending.

## Prefetch strategy and privacy diagnostics

The signed arm64 Preview keeps Cefrium 0.16's native cold-start read-ahead ON,
while Velocity's older custom page warming remains OFF. To avoid misleading
benchmarks, Gradle rejects builds that would run both prefetchers together.
Only unsigned, minified x86_64 benchmarking APKs may use
`-PvelocitySdkPrefetch=false`; the historical four-way prefetch workflow
now makes this explicit for every variant. It is not an SDK-on comparison.

Cefrium's YouTube ad heuristics are loaded explicitly before the first
navigation because they are no longer added implicitly with a filter list.
The SDK's NetLog feature is not enabled in ordinary or private browsing:
raw logs contain request URLs and socket endpoints. Any future privacy
audit must use a purpose-built test profile and protect or discard raw logs.

## Measured Preview optimization (2026-10-28)

Release builds now enable R8 by default using the SDK consumer rules. Android 16
Preview defaults to attach startup, while a saved user preference still overrides
that default; other Android versions keep post. The diagnostic reset remains
available. Paired actual-page measurements and limitations are recorded in
[the page-speed report](../../docs/cefrium-page-speed-2026-10-28.md).

The `velocityEarlyEngineInit` flag remains restricted to disposable x86_64/R8
APKs. It now tests Activity initialization plus pre-draw Surface creation;
it is not enabled in signed Preview or background process wakes.
