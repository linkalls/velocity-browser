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
4–6% on MDN in these two executions. Network and CI variability remain material.
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
