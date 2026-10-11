# Velocity 0.3.0 validation

App source: `576a641ca16a0e11391c61bdd4d77921b80e9fc2`. Four staged PRs: [1](https://github.com/linkalls/velocity-browser/pull/1), [2](https://github.com/linkalls/velocity-browser/pull/2), [3](https://github.com/linkalls/velocity-browser/pull/3), [4](https://github.com/linkalls/velocity-browser/pull/4). [Changed files](v03-changed-files.txt).

## Executed locally

- JVM 40 tests: 0 failure/error/skip. Bun 21: 0 failures; frozen install and typecheck pass. App JVM task is NO-SOURCE.
- lint 0 errors / 37 warnings. Hardcoded UI strings, deprecations and touch accessibility warnings remain.
- Full debug, Android-test compilation, release, AAB, minimal-Gecko and benchmark builds passed. Final close-guard source was recompiled and release/AAB/instrumentation/lint passed in `/tmp/velocity-v03-final-guard-build.log` (1m34s). Full preceding build took 2m47s. An earlier moving-workspace build failed because sources changed after core compilation; it is not treated as a pass.
- v2/v3 test signature, 16KiB alignment and ZIP integrity pass. All 13 native Gecko libraries match the baseline. Release contains no debug fixture providers/activities and no profileable element.

## Emulator and CI

[Run 38147354368](https://github.com/linkalls/velocity-browser/actions/runs/38147354368) on `4c5bca4` passed build, Bun and **20/20 API36 x86_64 real-Gecko tests**, no failure/error/skip. This covers protected third/seventh/twentieth sessions and form preservation after rotation/pressure, 100 logical-tab search/pin/undo without preload, file upload/download/auth, real Gecko WebAuthn abort/invalid-RP, Activity bridge, metadata ordering/private exclusion, reading replay, real 250px layout collapse/restore, 100 insertions and picker apply/undo.

[Final source run 38147819890](https://github.com/linkalls/velocity-browser/actions/runs/38147819890) passed Bun and **all 20 device tests** (0 failure/error/skip) on the exact final application source. Its complete build job also passed, including APK-only artifact creation. Final source additionally rejects stale close confirmations. Its nullable-receiver compile issue was fixed, compiled locally and the full device suite rerun. Main integrates the four stages with application/core/tools/workflow files identical to the tested final sources. Later report-only commits do not change application code. [Exact 20 case outcomes](benchmarks/v03-device-results.json).

External-fixture tests are excluded, not counted as passes: signed AdGuard XPI install/options, configured AdGuard blocking and trusted HTTPS Lite fixture. Physical-device Passkey creation/login/provider behavior, camera/microphone/payment/background media, TalkBack/200% font/OEM gestures, abrupt process loss and physical APK install remain UNVERIFIED.

Earlier failed runs and fixes are listed in [machine-readable report](benchmarks/v03-validation.json); no failed, skipped or partial execution is counted as success. APK-only Actions artifact remains intact. Device smoke runs only on workflow_dispatch.

## Size

Same-condition **unsigned arm64 release**: 191,375,171 bytes (182.509585 MiB). Minimal Gecko 191,118,123 bytes. Additional Velocity bytes: **257,048 (251.023438 KiB)**. Signed download: 191,394,502 bytes; SHA-256 `b5b09ba471c2a5da7b7cff1f4c85049b0dfa033560fe6bd2a26c4e6acd73d134`.

Signing uses the same local test Android Debug certificate as prior local 0.2.x downloads, not a production key. CI's runner key may differ. [Artifact record](benchmarks/release-artifact.json). PSS/CPU/Speedometer/startup distributions/jank/Chrome-Firefox comparison/long-idle memory/block rate are NOT_MEASURED. APK bytes do not establish RAM or speed.

## Boundaries

GeckoView remains the sole engine. Balanced six is a soft target; UNKNOWN/protected sessions may exceed it. OS or engine allocation failures cannot guarantee unlimited tabs or survival of process termination. Private tab metadata/rules remain excluded from disk. Undo reloads a URL and cannot recreate closed JS state. No full AdGuard compatibility or universal empty-space removal is claimed. [Known limitations](known-limitations.md).
