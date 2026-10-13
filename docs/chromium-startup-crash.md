# Chromium (Cefrium) startup crash investigation

The first Cefrium APK compiled, but the Pixel 7 Pro user reported a crash.

## Current status

- **No physical-device logcat obtained yet.** The exact Pixel crash mechanism is not established.
- Suspected app-side issue: `CefriumBrowser.createWithSurface(this)` attached a native Surface to the Activity; the prototype then removed the same Surface from its parent and reattached it beneath a UI container. Native WindowAndroid/Surface lifecycle may not tolerate that.
- Fixed experimental path now **avoids moving a previously attached Chromium Surface** and defers engine creation until after the Activity window has been attached.
- Java startup exceptions are surfaced in an in-app alert and logged with `VelocityCefStartup`; native SIGSEGV and process crashes require logcat/tombstones.
- CI builds x86_64 separately and boots an API 36 Android emulator. This tests basic process survival only, **not native ARM64 behavior on the Pixel 7 Pro**.
- Use GeckoView mainline for actual browsing until startup and security validated.

## Pixel 7 Pro evidence collection

Use Android SDK Platform Tools with USB debugging enabled:

```bash
adb logcat -c
adb shell am force-stop dev.velocity.browser.chromiumtest
adb shell am start -W -n dev.velocity.browser.chromiumtest/dev.velocity.chromium.ChromiumTestActivity
# Reproduce the crash, then:
adb logcat -d -b main,system,crash -v threadtime > velocity-cefrium-crash.txt
adb shell dumpsys activity exit-info dev.velocity.browser.chromiumtest > velocity-cefrium-exit-info.txt
```

Look for `FATAL EXCEPTION`, `AndroidRuntime`, `Fatal signal`, `linker`, `libcef.so`,
`NoClassDefFoundError`, `ClassNotFoundException`, or `VelocityCefStartup`.
When sharing logs, redact any personal URLs or account identifiers.

If the app itself survives but Chromium's renderer crashes, record renderer termination events separately. A successful build/manifest merge is not evidence that an ABI-specific native engine starts.

## Compare and release gate

Do not claim crash resolved until the Pixel arm64 build actually opens the Yahoo page, scrolls and survives repeated launch cycles. The x86_64 emulator is only a regression detector. This is not a release-ready browser.
