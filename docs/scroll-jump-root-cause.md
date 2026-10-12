# Scroll jump investigation (0.5.1)

[User implementation specification](scroll-jump-spec.md).

## Scope and evidence

Base: latest fetched main `92b8c989eb48655889ce2409319a86b028ca8e25` (PR #6 merged; GeckoView Stable `157.0.20261005135250`). The new request supersedes the earlier UI overhaul: this change concerns scroll quality only. The cosmetic scheduling already on main is preserved; PR #5 was not merged again.

The supplied eight-frame Yahoo News montage shows the header reappearing near 5.02–5.08s. It has no touch/source telemetry. The referenced MP4 is not attached to this execution environment. This does **not** establish whether the movement is a user reversal, script/anchoring, viewport change or missing rendered frames. No Pixel 7 Pro is connected. The reported Yahoo jump's root cause and final physical acceptance remain **NOT_MEASURED**.

## Reproduced facts

Observation commit `d95f93a` retains main's direction arithmetic and arbitration, adding opt-in tracing/fixtures. Its real Gecko emulator `zoom-user.csv` records 114 compositor samples, zoom 1.0 → 2.1573036. At uptime 335328ms, CSS y moves 253.0 → 282.899 and zoom 1.0 → 1.1123595: old `(y * zoom) - previous(y * zoom)` produces 61.685394px, while CSS displacement times the new zoom is 33.2584px. Later pinch samples produce about 61.685px even with only 17.31px CSS displacement scaled to the new zoom. Zoom and anchored pinch movement must not become continuous pan intent. This proves contaminated direction input, **not** visible Yahoo reverse motion.

`anchor-user.csv` records one SOURCE_OTHER and 38 SOURCE_USER_INTERACTION samples: intentional fixture scrollIntoView executes and the following pan remains observable. Browser code never cancels or rewrites either content movement.

Two targeted core regressions fail with the old ToolbarMotion and pass with the fix:

* CONTENT show at t=300 reverses a compositor hide started at t=100 that is still at visible=0.6. The old 160ms window has expired while the animation is unfinished.
* An automatic hide received while engine-pinned is saved as a Boolean and replayed on unpin as a new UI command. A shown toolbar consequently hides after the original operation has ended.

These are reproducible chrome state defects. Their occurrence during the submitted Yahoo sequence is unconfirmed. Source changes and zoom changes have independent tracker tests, including intentionally huge valid flings; there is no arbitrary delta-size rejection.

A further real-Gecko run on `89d8d49` passes 30/31 but fails the first matrix footer tap (ALL_OFF, auto-hide OFF, bottom). Its trace reports max=0, clipping=56, no IME/address lock and normal USER movement back to y=0. Clearing focus alone did not fix it. Gecko 157 Display.onSurfaceChanged reapplies max-height and resets clipping, so a stale clipping cache was investigated. Reapplying clipping via first-composite in `e3c571b` **did not resolve the tap failure**.

The `e3c571b` run provides the decisive normal-localhost screenshots and bounds: painted footer is Y=480..535, UIAutomator/Gecko accessibility reports Rect(0,536–320,592), and clicking its center enters the native URL editor. The footer onclick never runs. This is a **56px mismatch between painted fixed-footer and accessibility/tap coordinates when using max=0 plus positive clipping**, not a blank-footer claim or proof of Yahoo scroll reversal. [Before tap](scroll-jump-evidence/before-lock-baseline-e3c571b/matrix-0-before-tap.png), [after tap](scroll-jump-evidence/before-lock-baseline-e3c571b/matrix-0-after-tap.png), [numeric observation](scroll-jump-evidence/footer-observation.json) retain the evidence. The previous dynamic-baseline footer fix remains valid.

The resulting correction keeps the toolbar's ICB reservation for **both** auto-hide ON and OFF. OFF locks the existing motion shown rather than switching max height to zero. This aligns fixed-element accessibility with the already-working dynamic baseline and avoids changing the viewport reservation on preference changes. The pure max=0/static API geometry formula remains tested but is no longer used to implement auto-hide OFF. The complete matrix still requires actual clicks; it does not compensate their coordinates to pass. In completed `0c41a0b` CI all 16 scenarios and all 31 device tests pass. ALL_OFF/OFF/bottom keeps max=56/clipping=0, painted footer Y=480..536, accessible bottom <=536, and an unadjusted click triggers the expected callback rather than URL editing. [After image](scroll-jump-evidence/after-0c41a0b/matrix-0-before-tap.png), [after-click trace](scroll-jump-evidence/after-0c41a0b/matrix-0-after-tap.csv). This confirms the emulator fixed-footer coordinate correction; optical user-scroll/Yahoo acceptance remains unmeasured.

## Minimal corrections

* ScrollIntentTracker stores CSS y, zoom, source class and epoch. Rebase on zoom/source/epoch/navigation/selection/foreground boundaries; only consecutive active USER samples at unchanged zoom yield direction hints. The 24dp threshold remains. Non-user moves continue in Gecko normally.
* Every selected foreground USER sample renews compositor authority, including slow pans below the direction threshold. An unfinished compositor-owned animation rejects conflicting CONTENT requests. The existing 160ms/80ms bounds are unchanged.
* Engine pin retains the actual target/current visible position. Only an explicit UI show can be pending; automatic signals are never promoted to UI. Animator and layout pin paths use a freeze operation rather than fabricating a UI request.
* Public onFirstComposite (UI thread, compositor start/restart) invalidates the cached clipping and reapplies current geometry for the actually displayed selected session. This covers Gecko's internal Surface reset without restarting toolbar motion or changing scroll coordinates.
* Selection pin is checked before changing Gecko's dynamic baseline. The pure static/dynamic API equations and required max-height reset/reapplication remain intact; auto-hide OFF now uses the same reserved ICB as ON and locks motion shown. Unchanged translations avoid redundant setters. Animation reversal starts from current visible position, never a target endpoint.
* Selected-session identity/epoch checks, main-thread Gecko calls, SurfaceView/parent translation, security and all page JavaScript remain in place. No page scroll writes, engine replacement or production page probes were introduced.

Lite is not established as the cause. Its 180ms quiet period, 4ms/12-element slices, bounded candidates, protected forms/media and dedicated-wrapper-only collapse remain unchanged. Bun tests additionally exercise the candidate cap and form-contained candidates. A synchronous querySelectorAll can still exceed the slice budget; no strict 4ms claim is made.

## Diagnostic contract

Debug APK only, disabled at process start and never persisted. Settings → opt-in scroll recording → explicit local CSV save through Android's document picker. The installable benchmark/distribution artifact has BuildConfig.DEBUG=false. There is no diagnostic network sender, automatic disk export or per-event logcat. Tests explicitly copy **localhost normal-tab** fixtures to CI artifacts.

The 768-event ring uses fixed numeric arrays (~77 KB payload); CSV strings are allocated only on export. Fields contain uptimeMillis, anonymous selection ordinal, numeric identity/epoch, CSS y/zoom/source/delta/intent, request direction/result, state/visible/target, heights/clipping/translations, lock flags and animation callback gaps. It never accepts URLs, search queries, HTML, cookies, form values or image data. Private/null scope clears the ring; scope is updated before session attachment and callbacks, covering attach-to-render transitions. Disabling discards the ring. Oldest events are overwritten, so a trace is a recent window, not a complete session.

CSV event codes in order: CONTEXT=0, SAMPLE=1, REQUEST=2, START=3, CANCEL=4, END=5, FRAME=6, LAYOUT=7, INSETS=8, PIN=9, LOCK=10, MAXIMUM=11, APPLY=12, RESET=13. SAMPLE source is Gecko USER=0/OTHER=1; REQUEST source is COMPOSITOR=0/CONTENT=1/UI=2. SAMPLE reasons: USER_DELTA=0, BASELINE=1, SOURCE_OTHER=2, SOURCE_CHANGED=3, ZOOM_CHANGED=4, EPOCH_CHANGED=5, INACTIVE=6, INVALID=7. REQUEST decisions: ACCEPTED=0, LOCKED=1, CONTENT_PRIORITY=2, PINNED=3, DUPLICATE=4, HYSTERESIS=5. State: SHOWN=0,HIDING=1,HIDDEN=2,SHOWING=3,PINNED=4. Initial CONTEXT geometry can reflect the preceding chrome refresh; use subsequent LAYOUT/APPLY/REQUEST geometry for current UI assertions. Flags: home=1,modal=2,IME=4,address focus=8,TalkBack touch exploration=16. NaN means unavailable/not applicable. frame_gap_ms is a **native ValueAnimator callback interval**, not Gecko content FPS or full Android FrameTimeline.

Interpret traces alongside a synchronized touch/video recording: continuous CSS coordinates with abrupt geometry changes suggest viewport behavior; OTHER displacement suggests script/anchoring/engine but does not prove the exact initiator; alternating targets suggest arbitration; missing animation callbacks alone cannot identify SurfaceView frame loss. SOURCE_USER_INTERACTION includes pinch/fling and does not mean a finger is currently down.

## Counterexamples and unresolved work

A large USER fling remains valid, programmatic anchors remain allowed, and focus/IME retains form text. Navigation/tab/background resets only chrome intent, never tab ID/identity/epoch or content coordinates. A zoom reset can defer a chrome direction until the next 24dp of continuous pan; it intentionally avoids interpreting pinch as pan.

No physical same-device 60/120Hz, 20-pan × three-set before/after optical analysis, Yahoo live reproduction, Gecko Profiler, content frame timing, thermal/CPU/PSS comparison or physical selection/caret/Passkey/upload acceptance was performed. Host or emulator metrics cannot substitute for those. See validation for precise results and remaining measurements.

Primary references: [Gecko ScrollPositionUpdate](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.ScrollPositionUpdate.html), [GeckoView](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoView.html), [Perfetto FrameTimeline](https://github.com/google/perfetto/blob/main/docs/data-sources/frametimeline.md). Pinned Gecko 157 source JAR was also inspected; CSS-to-screen conversion itself is correct, crossing zoom/source boundaries is the defect.
