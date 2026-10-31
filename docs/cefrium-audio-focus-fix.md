# Quiet home must not duck other apps — 2026-10-28

Symptom: simply opening Velocity's native offline home lowers the volume of
another app playing background audio. No audio/video site is required.

## Cause and change

The host called CefriumBrowser.onResume() when activating a tab and when the
Activity resumed. In SDK 0.16.0 this convenience method unmutes the page, makes it
visible, and unconditionally calls resumeMedia(), which sends a UI Resume to
Chromium's MediaSession. That is a transport/play action, not just view lifecycle.

Chromium 156 initializes a new MediaSession with kGainTransientMayDuck; UI Resume
requests that audio focus even without a playing page. The Android audio-service
log reproduced Velocity requesting focus type 3 on the otherwise quiet home,
followed by the other player's AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK (-3).

Velocity now restores only page visibility and its own mute state on foreground.
It does not send Resume from Activity/tab lifecycle. Media paused when the app
backgrounds stays paused until the user plays it in the page. The SDK's
requires-user-gesture setting is also explicitly enabled before navigation.
Normal user-initiated page audio still uses Chromium's usual focus handling.
No device-wide volume setting is changed.

References: [Cefrium 0.16.0 published sources](https://repo.maven.apache.org/maven2/com/cefrium/cefrium-sdk/0.16.0/cefrium-sdk-0.16.0-sources.jar),
[Chromium 156 MediaSession implementation](https://github.com/chromium/chromium/blob/156.0.8078.17/content/browser/media/session/media_session_impl.cc).

## Android regression evidence

[Passing run 40688193036](https://github.com/linkalls/velocity-browser/actions/runs/40688193036)
uses an Android 16 emulator and a separate-UID app playing a looping AudioTrack
with granted music focus. It opens Velocity's quiet home, sends it to background,
and returns to it while the other player remains alive.

| Browser | Player loss when opening | Losses including return | Separate MAY_DUCK positive control |
|---|---|---|---|
| Before fix (e27c1bc) | -3 | -3 | detected |
| Fixed (304eedd) | none | none | detected |

The test also requires the browser's actual native Surface to initialize.
It saves player logs, Android audio-service focus state and AudioFlinger state.
The positive control uses unique focus client IDs: identically forked fixture
apps otherwise produced identical identity hashes and accidentally shared an ID.
The first attempt was marked invalid until this fixture issue was corrected.

Local tooling: 51 benchmark tests and 12 manifest/release tests passed.
[Renderer run 40686325710](https://github.com/linkalls/velocity-browser/actions/runs/40686325710)
passed mandatory Android 16/17 DOM+JS smoke. The fixed permanent-key arm64 R8 APK
passed signature/package/version checks in
[run 40686315454](https://github.com/linkalls/velocity-browser/actions/runs/40686315454):
[Preview 200053](https://github.com/linkalls/velocity-browser/actions/runs/40686315454/artifacts/12814689070).
The later fixture-only commit does not change the browser application code.
These are emulator audio-focus checks, not a physical speaker/Bluetooth amplitude measurement.

The test apps are included only with -PaudioFocusProbe=true. They are not modules
in normal Preview builds. The harness refuses physical devices and only removes
test packages on a disposable emulator.
