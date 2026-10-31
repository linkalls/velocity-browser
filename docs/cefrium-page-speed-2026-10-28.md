# Same-page performance work — 2026-10-28

The earlier cross-browser Activity comparison landed competitors on onboarding
screens. It cannot establish which browser loads actual pages faster. This work
instead compares Velocity configurations on the same emulator with the same
local fixture and two actual HTTPS pages.

## Confirmed changes for experimental Preview

- Release Preview now uses R8/resource shrinking with the SDK's consumer rules.
- On Android 16 only, a new installation/default preference uses attach startup.
  A saved user choice still wins; the menu and separate diagnostic process can
  turn it off. Other Android versions retain posted startup unless the user opts in.
- SDK prefetch stays enabled. Profile paths, signing identity, private-tab
  separation, renderer validation and sandbox settings are unchanged.
- Early Application initialization is **not adopted**: it regressed Wikipedia in
  one paired run. Background process wakes retain the SDK's lazy behavior.

## Measurements behind the choice

APK source: `4c27fb715b8ba5d8166b0a95c9fcf69b39bd2bb5`.
Each configuration has four trials in a 12-trial symmetric order, on Android 16
x86_64. Rooted emulator kernel caches are reset before each measured start.
Public-site trials also clear the disposable test profile before every launch.
No personal/physical-device profile is used. The same signed APKs are reused for
[run 40659228318](https://github.com/linkalls/velocity-browser/actions/runs/40659228318).

Baseline is non-minified + post. Candidate is R8 + attach. Times below are host
launch-to-paint/JS acknowledgement medians, including adb/observation overhead.
The local page also executes its own DOM button handler. Public pages use a
numeric Paint Timing/DOM timing probe after loading; this is not a trusted touch
latency or exact app-launch-to-FCP measurement.

| Workload | Run 40657013265 baseline → candidate (ms) | Repeated run 40659228318 (ms) |
|---|---:|---:|
| Local fixture | 1823.0 → 1614.1 | 1396.5 → 1268.0 |
| Wikipedia front page | 3503.7 → 2900.9 | 3330.9 → 3110.2 |
| MDN JavaScript article | 4190.1 → 3958.7 | 3045.1 → 2937.6 |

Candidate is 9–11% quicker on the local fixture, 7–17% on Wikipedia, and
4–6% on MDN in these two executions. A third execution below shows the MDN
total-time difference can be negligible. Network and CI variability remain material.
Navigation-relative FCP did not consistently improve: for example, the first
local run was 730 ms baseline versus 990 ms candidate, even though the overall
launch-to-ack interval fell. Do not claim uniformly faster rendering or engine
throughput from these results.

An earlier local paired run [40655098512](https://github.com/linkalls/velocity-browser/actions/runs/40655098512)
showed 1260.2 → 1089.2 ms and package PSS 174.7 → 132.3 MiB. All eight Android 16
trials produced painted content, a JS acknowledgement and screenshots. Package
PSS is not guaranteed whole-process-tree memory. Screenshots of the actual
Wikipedia and MDN pages were also inspected in run 40657013265.

## Missing evidence and next hypothesis

Android 17 measurements have been invalidated by shell-service failures,
non-cold launches, and emulator screenshot readback asserting
`hasReadColorBufferDma`. These are not zero-time samples. The harness now checks
cold launches, verifies process termination, records adb transport retries and
uses file/pull screenshots. The workflow is testing current SwiftShader GLES
without Vulkan to resolve the emulator readback failure. Attach is not made the
Android 17 default on this evidence.

The next benchmark-only hypothesis initializes inside Activity.onCreate and
creates the Surface at pre-draw, after layout. Unlike Application initialization,
it does not load Chromium for a background-only process wake. Its full cost is
included in the host clock. It remains off in real Preview until measured.

Physical-device startup, actual touch response, scrolling/jank, battery, real
site tail latency and Speedometer are still unmeasured. These results establish
modest measured improvements, not an "instant" browser or a win over Brave/Chrome.

## Third experiment and shipping validation

[Run 40660689022](https://github.com/linkalls/velocity-browser/actions/runs/40660689022)
built source `31d5c7f` and tested the Activity-init + pre-draw hypothesis. Android
16 completed all 36 local/Wikipedia/MDN trials with screenshots.

| Workload | Baseline median (ms) | R8 + attach (ms) | Activity-init + pre-draw (ms) |
|---|---:|---:|---:|
| Local fixture | 1680.9 | 1539.5 | 1593.3 |
| Wikipedia | 3245.1 | 3054.4 | 4261.7 |
| MDN | 3940.2 | 3933.8 | 3768.0 |

Pre-draw is slower than the accepted candidate on the local fixture and
Wikipedia, so it is **not adopted**. It stays a disposable-only experimental
flag. Across the three executions, attach/R8's launch-to-ack advantage is roughly
8–11% locally, 6–17% on Wikipedia and 0–6% on MDN; network and VM effects remain.
This is not evidence of a uniformly faster rendering engine.

The application changes through `e27c1bc` passed packaged APK/renderer CI
[40662533601](https://github.com/linkalls/velocity-browser/actions/runs/40662533601),
including mandatory Android 16/17 DOM+JS smoke, and permanent-key arm64 R8 Preview
build/signature/package checks
[40662524578](https://github.com/linkalls/velocity-browser/actions/runs/40662524578).
The attach path also handles an already attached view during Activity recreation.

### Android 17 infrastructure diagnosis

Failure artifacts from
[40661934492](https://github.com/linkalls/velocity-browser/actions/runs/40661934492)
show the system server aborting in `TaskSnapshotConvertUtil.copyToSwBitmapDirect`
via `android.media.ImageReader`/graphics-buffer readback. Package/activity
services then disappear or restart. This explains failures beyond the app's own
screenshot command. Host-console frame capture can work, but the OS's own task
snapshot path still invalidates paired APK-swap trials. No Android 17 speed
comparison is accepted, and attach remains opt-in there. Resolving this image/host
emulator incompatibility or moving the paired test to a real device is required.

The result is a measured, smaller-memory Preview improvement, not a demonstrated
"blazing fast" result. Further useful optimization needs physical-device
measurements of the user's actual slow pages rather than more unproven launch
switches.
