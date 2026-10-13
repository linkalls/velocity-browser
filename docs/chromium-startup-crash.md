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
