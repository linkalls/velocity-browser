# Startup crash isolation — Pixel 7 Pro (13 October 2026)

## Why the launcher is now separate

Cefrium 0.9.4 initializes through its manifest ContentProvider, **before**
`ChromiumTestActivity.onCreate()`. User reports immediate crash. The
SDK can fail before Activity-level `try/catch` or UI logging is available.
A Java View/Suface reparent fix cannot address provider startup.

The normal launcher has therefore been moved to `StartupDiagnosticActivity`
running in `:diagnostic`. It never imports or instantiates Cefrium.
Press **Chromiumエンジンを起動** to start
`ChromiumTestActivity` in the normal app process (where the provider runs).
Then use **クラッシュ情報を更新** after it returns/crashes; the app
displays Android API 30+ process exit reason/status codes, not private browsing data.
This isolates stages and avoids the appearance that successful launcher
startup means Cefrium itself works.

## Get decisive evidence

With Android platform tools (Windows terminal):

```powershell
adb logcat -c
adb shell am force-stop dev.velocity.browser.chromiumtest
adb shell am start -W -n dev.velocity.browser.chromiumtest/dev.velocity.chromium.StartupDiagnosticActivity
# Tap 'Chromiumエンジンを起動', wait for the crash
adb logcat -d -b main,system,crash -v threadtime > chromium-crash.txt
adb shell dumpsys activity exit-info dev.velocity.browser.chromiumtest > chromium-exit-info.txt
```

Look for `AndroidRuntime`, `FATAL EXCEPTION`, `Fatal signal`,
`UnsatisfiedLinkError`, `libcef.so`, `NoSuchMethodError`,
`CefriumProvider`, `VelocityCefStartup`.
The failure may be native and no Java stack will exist.

## Android emulator

The x86_64 smoke CI executes the real Chromium activity, not merely
the safe diagnostic process, waits 12 seconds, records logcat, and checks
that the **main** process survives. Android emulator passing cannot
establish ARM64/Pixel 7 Pro success.

## Security / limitations

The diagnostic screen does not fix the underlying engine crash,
nor change Chromium binaries. Prototype is for analysis only.

## Java exception captured without ADB

The official Android exit-reason API reports `REASON_CRASH = 4` for an
uncaught Java exception, but does not generally provide its full Java stack.
A new app `ChromiumDiagnosticApplication.attachBaseContext()` handler is
installed in the primary process **before Cefrium's ContentProvider can run**.
It saves the most recent Java exception to the app-private file
`cefrium-last-java-crash.txt` and delegates to the original Android fatal
exception handler. The separate :diagnostic launcher shows the saved exception
and allows the user to copy it explicitly. No logs or personal URLs are
uploaded automatically. A user should review copied stack contents for
sensitive identifiers before sharing.

The handler cannot catch errors before Application attachment, native process
crashes, OOM kills, SIGKILL, or all failures in child processes; use adb logcat
when there is no Java stack. Also the diagnostic APK must be **updated** before
the next crash for this feature to work retroactively (previous Java crashes
are not recoverable from this file).

## Pixel 7 Pro crash signature confirmed 2026-10-13 13:36 JST

The user captured a Java exception from the primary process on
`Chrome_ProcessLauncherThread`:

```
Caused by: android.content.pm.PackageManager$NameNotFoundException:
ComponentInfo{dev.velocity.browser.chromiumtest/org.chromium.content.app.NativeOnlySandboxedProcessService0}
    at org.chromium.base.process_launcher.ChildConnectionAllocator.checkServiceExists
    at org.chromium.content.browser.ChildProcessLauncherHelperImpl.createAndStart
```

This is a **child process service lookup failure**, not a scrolling/GPU issue.
The app's `reason=4` now has an actual Java cause. Cefrium's docs promise
standard multi-process Service declarations via the AAR, but Android 37 /
Chromium 154 additionally attempts a specifically named **native-only**
sandboxed Service. It is distinct from `SandboxedProcessService0`.

The experimental manifest declares `NativeOnlySandboxedProcessService0` with
the same `:sandboxed_process0`, isolated/external/native service attributes
and the Chromium native-child entry-point properties. It deliberately DOES
NOT disable sandboxing, set single-process mode, or declare a reduced service
pool. This is a hypothesis until packaged Manifest audit, exported symbol
check, emulator, and Pixel 7 Pro runtime tests succeed. If libcef.so does not
export `NativeChildProcessService_onCreate`, the SDK itself must be updated;
the manifest alone is insufficient. CI uploads `Chromium-CEF-Native-Service-Audit`
with the final merged manifest and `readelf` output.

Upstream reference: https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/nonembedded/java/AndroidManifest.xml

**Important:** Native-only Android child service is an implementation detail.
Do not ship the patched APK for sensitive browsing until it has passed
multi-process isolation and renderer crash-recovery tests.

## Revised solution: use Chromium's existing Java isolated renderer services

The packaged `libcef.so` does **not** export
`NativeChildProcessService_onCreate`. Merely declaring the native-only
Service cannot launch an Android 17 native-only renderer and would expose a
broken Service to binding. The temporary native-only Service manifest entry
was removed.

Chromium's own `ChildProcessCreationParamsImpl.getSandboxedServicesName()`
selects `NativeOnlySandboxedProcessService` on Android 17 only when the
javaless renderer experiment is enabled. Chromium exposes an explicit Java
command-line switch: `--javaless-renderers=disabled`.

Before creating the Cefrium browser in the Activity on API 37+, Velocity now
calls `CommandLine.getInstance().appendSwitchWithValue("javaless-renderers",
"disabled")` and checks that
`ChildProcessCreationParamsImpl.getSandboxedServicesName()` returns
`org.chromium.content.app.SandboxedProcessService`, the 40 isolated Java
renderer services already present in the Cefrium AAR manifest. If this is
not achieved (e.g. engine initialization cached the feature too early),
Velocity aborts browser creation with a readable error and log rather than
continuing into the known native-only child process failure.

This does not disable sandboxing or multiprocessing. Whether the flag takes
effect in the published SDK must be validated on Pixel 7 Pro / Android 17.

Upstream implementation:
- https://github.com/chromium/chromium/blob/main/content/public/android/java/src/org/chromium/content/browser/ChildProcessCreationParamsImpl.java
- https://github.com/chromium/chromium/blob/main/content/public/android/java/src/org/chromium/content_public/browser/JavalessRenderersFeatureList.java
