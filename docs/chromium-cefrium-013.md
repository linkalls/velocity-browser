# Cefrium 0.13.0 Java renderer migration

PR #9's service-name audit is the starting point. This project uses the official
Cefrium 0.13.0 Java child services; it does not implement Native-only renderers.
The old 0.9.4 project and audit-only native experiment remain unchanged.

## Independent app

- Gradle root: `experiments/chromium-013/`
- applicationId: `dev.velocity.browser.chromium013`
- App label: `Velocity Chromium 0.13 (Test)`
- SDK and Gradle plugin: `0.13.0`; Chromium `156.0.8078.17`
- Toolchain: JDK 25, Gradle 9.7.1, AGP 9.4.1, SDK 37.1, build tools 36.0.0
- minSdk 29 / targetSdk 35, matching the earlier experiment

The separate ID allows side-by-side installation without changing the old
app's data. Profiles are not imported. The SDK's POM supplies AndroidX, Guava
and the other external libraries. The old `listenablefuture` exclusion is removed.
Java classes, resources, plugin and native libraries all come from 0.13.0.

The provider now prepares the process and leaves native initialization lazy.
Before the first browser is created, the Activity initializes CommandLine if
necessary and sets `javaless-renderers=disabled` on API 37+. It then compares
Chromium's selected Java service name with application metadata and checks all
40 services, isolation, non-exported/enabled status and service 0's app zygote
flag through PackageManager. This validates declarations and selection, not the
runtime renderer's UID/seccomp policy.

Navigation remains a single-tab prototype. Forward availability uses the SDK's
loading-state callback. Address/preset actions cannot dereference an engine
that failed to initialize. No JavaScript query bridge is installed.

## Build and checks

```sh
cd experiments/chromium-013
# JAVA_HOME: JDK 25; ANDROID_HOME: SDK with the packages above
gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug
# Emulator build:
gradle --no-daemon --max-workers=2 -PchromiumAbi=x86_64 :app:assembleDebug
```

From the repository root:

```sh
python3 -m unittest discover -s experiments/chromium-013/tools -p '*_test.py' -v
apkanalyzer manifest print experiments/chromium-013/app/build/outputs/apk/debug/app-debug.apk > /tmp/chromium-013-manifest.xml
python3 experiments/chromium-013/tools/audit_apk_manifest.py /tmp/chromium-013-manifest.xml
```

The dedicated **Chromium Cefrium 0.13 Java experiment APK** workflow builds arm64,
checks the actual packaged Manifest (40 continuous isolated, non-exported
services with the renamed metadata prefix and zygote flag), and defines x86_64
startup smoke jobs for API 36 and 37. The smoke gate requires the browser
process to survive, renderer selection validation and Surface creation to finish,
and no fatal startup exception. It does not prove page pixels or performance.

Install the arm64 artifact `Velocity-Chromium-CEF-013-arm64-EXPERIMENT`. The launcher
opens the separate diagnostic process; tap **Chromiumエンジンを起動** to browse.
The APK uses a development debug signature.

## Validation record

2026-10-24 local verification:

- The pinned source fetch verified 26 upstream files. The actual Gradle-resolved
  0.13.0 AAR matched PR #9's SHA256
  `18f52c00e1e6b75e75668042ad56c1e9e58442cce3800fc4d98d427ac7f40f83`.
- arm64 `:app:testDebugUnitTest :app:assembleDebug`: **PASS**. Both navigation
  policy tests passed; Java compilation, duplicate-class checks and packaging
  succeeded. APK size: 210,210,542 bytes, arm64-v8a only.
- arm64 APK SHA256:
  `bc11e29084ecdd7cc41c1e05ba60fb404b259129671a6cec6222086eed391718`.
- x86_64 `:app:assembleDebug`: **PASS**, x86_64 only, 216,671,536 bytes.
  APK SHA256:
  `fba63d91b6e22354b19f01e128ccbb3c60cf1b3e21f3c8a6251e967af5e79c77`.
  Its packaged Manifest passed the same gate.
- `apkanalyzer manifest print` on the arm64 APK followed by `audit_apk_manifest.py`:
  **PASS**, 40 isolated services, expected metadata, non-exported services,
  app zygote and separate applicationId.
- Five packaged-Manifest gate tests and the existing 14 AAR audit tests:
  **PASS**. The new workflow passes actionlint; the smoke script passes `bash -n`.
- The build reports removed translated SDK resources with missing default values
  (including biometric strings). No interactive biometric/credential UI test
  was performed; successful packaging does not verify those paths.
API 36/37 emulator, Pixel 7 Pro, actual renderer UID/seccomp, page rendering,
scroll/CPU/PSS and renderer crash recovery are **NOT RUN** locally. The workspace
has no `/dev/kvm`; adding CI jobs is not a successful CI result.

Tabs, private mode, downloads, permission handling, adblock and other Velocity
features remain unported. Binary redistribution also requires the appropriate
Cefrium/CEF/Chromium licenses, notices and corresponding-source arrangements;
this migration does not establish a public release's readiness.
