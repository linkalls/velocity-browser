# Scroll / UI overhaul investigation

Baseline: main `16be2395f1ea863144bd695012c56b60af1ab125`, clean clone on 2026-10-12.
Open PR #5 addresses cosmetic idle batches independently; its cosmetic script and
Bun scheduling tests are reused here, without merging its other changes.

## Confirmed source defects versus hypotheses

- Layout callback reapplied the target end position (`hidden`) during animation.
  This can jump from an intermediate position to the endpoint. Current fraction
  is now owned by ToolbarMotion and survives layout callbacks.
- GeckoView Display.setDynamicToolbarMaxHeight resets clipping to zero. Cache
  invalidation and immediate reapplication are necessary even when the previous
  application clipping equals the new clipping.
- Dynamic baseline already reserves H for the static initial containing block.
  Firefox's bottom-toolbar behavior sends `-translationY`, top behavior sends
  `translationY` and moves the parent. Velocity previously sent positive visible
  height in both modes. New matrix (shown/half/hidden): dynamic 0/-H/2/-H;
  static H/H/2/0, for either placement. All values are screen pixels.
- URL edit / IME / modal locks previously switched maximum height to 0. They now
  hold the bar shown without changing the dynamic baseline. Setting auto-hide
  OFF still changes max to 0, followed by positive occlusion notification.
- Content requests and compositor pan notifications could start competing motion.
  Compositor direction takes precedence for 160ms; reverse transitions use 80ms
  hysteresis plus the existing 24dp accumulated pan threshold. These are small
  initial bounds, not measured device latency or a smoothness claim.
- GeckoView is not translated directly: its parent moves for top bars. Selection
  pin cancels motion without moving the display and defers the latest request
  until a subsequent event/layout signals unpin. No selection polling added.
- Cosmetic optional inspection preserves the 180ms scroll quiet period and now
  yields after 4ms / 12 elements. A native selector query is not preemptible.

Primary references consulted: GeckoDisplay javadoc, GeckoView.java (Display's
reset), ContentDelegate javadoc, Android Components EngineViewClippingBehavior,
Fenix combined toolbar EngineViewClippingBehavior. The pinned 157 artifact is
also used for build and real-engine CI tests; mozilla-central sources are not a
substitute for the pinned binary's runtime validation.

## Reproduction / acceptance matrix

Use the instrumentation localhost fixture (static and dynamic paragraphs, fixed
footer and editable input), also tools/bench-runner/fixtures/scroll-*.html. Compare
bar OFF then ON, bottom then top, Lite then ETP_ONLY; slow pan, fast flick,
coasting and reversal. Separate a visible content jump from a long host frame.
Record fixed footer, header, caret, tap alignment and form/session identity.
Compare identical APK variant, orientation and refresh rate at least 3 times.

Local worker has no /dev/kvm, attached phone or adb initially. Pixel 7 Pro,
60/120Hz, TalkBack, video/high-speed capture, Gecko profiler, web-content
p95/p99, CPU/PSS pre/post: NOT_MEASURED. Emulator tests assert geometry,
input/session retention and real pan signals; they do not establish physical
smoothness. Earlier v0.4 host-window metrics remain historical, not a result of
this change. Transient maximum-height and layout-target faults are verified in
source; their share of the user's observed stutter remains unconfirmed.

Validation commands and final CI links are recorded in docs/overhaul/validation.md.

## Actual final evidence

Final [Actions 38228147415](https://github.com/linkalls/velocity-browser/actions/runs/38228147415)
passed all 27 real-Gecko cases with 0 failures/errors/skips, including clipping
across placement, auto-hide, Lite and Animator Scale 0/1. Native search's resolved
hint/background contrast is tested, not just token pairs. The dark static
fixture screenshot shows the original extra 56px beneath its fixed footer and
no such gap in the final capture; see [PNG comparison](overhaul/screenshots.md).
No physical pan/flick position-jump guarantee follows from a static image.
[Actual emulator performance](overhaul/emulator-comparison.md) is mixed: dynamic
CPU/p99 increased, and it does not establish a performance win. Final-binary
physical performance remains NOT_MEASURED.
