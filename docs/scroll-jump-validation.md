# Scroll jump validation — 0.5.1

## Environment and comparison boundaries

Source base main `92b8c989eb48655889ce2409319a86b028ca8e25`; observation-only diagnostics `d95f93a`; direction/arbitration fix `89d8d49`, lifecycle probe/fix `e3c571b`, locked-viewport correction `5919ddb`, final native-start wait `0c41a0b`. Java 17, Android compile SDK 37.1/build tools 36.0.0, Gecko 157.0.20261005135250, Bun 1.4.3 (frozen lockfile). Local container has no physical device or usable KVM. Real Gecko device tests run on GitHub Actions API 36 x86_64 emulator. That emulator is not Pixel 7 Pro and its refresh/thermal/power conditions do not establish physical 60/120Hz behavior.

| Evidence | Before | After / status |
|---|---|---|
| Core tests | main 46 pass | 57 pass, zero failures/errors/skips |
| Focused arbitration regressions against old code | 2 reproduced failures | both pass |
| Bun scheduling/blocker/safety/UI tests | 29 pass | 31 pass; typecheck pass |
| Android Lint | 0 errors, 38 warnings | 0 errors, 38 warnings; no increase |
| app:testDebugUnitTest | NO-SOURCE | NO-SOURCE; not counted as tests |
| Main device smoke run 38233099509 | 27 cases, one close-sheet TOCTOU failure | 31/31 pass in final code CI |
| Observation device run 38243901362 | 31 cases, three failures | 31/31 pass; see notes below |
| Reported Yahoo jump, physical Pixel 7 Pro 60/120Hz | NOT_MEASURED | NOT_MEASURED |
| 20 pans/flings × 3 alternating same-device sets | NOT_MEASURED | NOT_MEASURED |
| Gecko content FPS / optical reverse count | NOT_MEASURED | NOT_MEASURED |
| CPU/PSS/FrameTimeline/Gecko Profiler comparison | NOT_MEASURED | NOT_MEASURED; no improvement percentage claimed |
| Physical Passkey/media/upload/caret/selection quality | NOT_MEASURED | NOT_MEASURED; existing automated safeguards retained |

## Runs and failures retained

* [Latest main baseline](https://github.com/linkalls/velocity-browser/actions/runs/38233099509): build and Bun succeeded, device smoke failed. closeCancelRetainsSheetDraftAndAccessible48dpTarget used separate main-thread reads: between non-null check and dereference the tab can disappear. Read/assert within one main-thread block, wait for the actual accessible close control, preserve the existing close/cancel/retained-draft/live-count assertions. This is a test race, not claimed as the reported scroll defect.
* [Observation before fixes](https://github.com/linkalls/velocity-browser/actions/runs/38243901362): build/Bun succeeded; 28/31 device cases passed. Matrix first footer tap, nested scroll, and existing layout-interruption show failed; not a pass. Matrix trace export originally occurred after the footer assertion, so **there is no before matrix trace**. Anchor trace's first context event has cached flags=12, but its following events are flags=0 (60 events) or home=1 (six events); this does not prove the footer failure was caused by inherited focus. Geometry/flags on an initial CONTEXT event can precede the next chrome refresh. Fixture setup explicitly clears address focus; subsequent static failure remains a separate unresolved observation until the lifecycle run. Nested swipe was within an ~18px text node rather than the 200px scroll container: use a 110px gesture within that container. The layout test raced remaining real inertia against a UI show assertion; it now separately checks real swipe endpoints and a paused animator's intermediate layout position, then uses a UI modal pin for the final shown state. Footer tap remains a real click and retains its assertion. These changes correct test setup and isolate the assertion; the original failed run remains linked.
* [Direction/arbitration fix CI](https://github.com/linkalls/velocity-browser/actions/runs/38246093505): build/Bun pass, device 30/31, one real footer-tap failure in first ALL_OFF/static/bottom scenario. Matrix trace flags=0 after load, max=0 and clipping cache=56; no before matrix result or pass is manufactured. Anchor, nested, real pinch, retained form and isolated intermediate-layout tests pass.
* [First-composite lifecycle run](https://github.com/linkalls/velocity-browser/actions/runs/38247528988): build/Bun pass; device 28/31. First-composite clipping reapply alone does **not** correct footer accessibility. Screenshots prove painted Y=480..536 vs accessible Y=536..592, and the tap enters URL editing. Also failed existing second-download offer (old accessibility link can survive page navigation) and new form lookup (no wait for rebuilt input node after focus/IME/menu). Retry test now requires a newer navigation epoch and a settled page; form test waits for its actual input and closes IME only if present. No assertion is dropped and no failed run is counted as pass.
* [Completed code CI](https://github.com/linkalls/velocity-browser/actions/runs/38249023979), source `0c41a0b`: build, Bun and device-smoke **all pass**. XML confirms 31 cases, zero failures/errors/skips; the complete 16-condition matrix finishes. 52 normal-localhost trace CSVs and 32 fixture screenshots plus ten native major-screen screenshots are in the [validation artifact](https://github.com/linkalls/velocity-browser/actions/runs/38249023979/artifacts/11747842553). [Totals and retained-file hashes](scroll-jump-evidence/ci-results.json). The canceled intermediate `5919ddb` runs are not passes; `0c41a0b` adds the required wait beyond navigate()'s eager metadata invalidation to the actual Gecko page start.

All smoke cases not annotated RequiresExternalFixture are required, with exactly 31 executed and no skipped/failed cases. External-fixture cases were already excluded by the repository's standard command; their live HTTPS/manual requirements are not counted as passes.

## Fixture coverage and trace limits

`app/src/androidTest/assets/scroll-jump.html` (also served by Bun at `/jump`) is a local long 100-row page with fixed header/footer, dedicated ad wrapper insertion, delayed image, a nested container, actual editable form, explicit scrollIntoView and focus actions. The new matrix runs ALL_OFF, ETP_ONLY, LITE/collapse OFF, LITE/collapse ON × bottom/top × auto-hide OFF/ON = 16 scenarios. Each includes slow upward pan, reverse faster swipe, real fixed-footer click, dynamic insertion and subsequent scroll; checks attached session object, tab identity and epoch. The trace exports before footer click and after insertion. This is a functional regression matrix, **not** the required physical 20×3 optical benchmark.

Separate new tests record intentional anchor OTHER source followed by USER pan, nested scrolling, focus/IME/menu and retained form text, injected real Android two-finger pinch followed by pan, bounded/default-off/private-excluded tracing. Existing V04 and CosmeticLayout integration cover clipping, density/layout, animations disabled/enabled, native UI pins, TalkBack flag, rotation, form protection and private transitions. Automated UI flags are not certification of physical TalkBack/selection drag/caret alignment. Before and after recorded pinch paths are emulator actions, not matched physical performance measurements.

CSV artifacts contain only numeric events from normal localhost fixtures. A 768-event recent ring can truncate early events; counts and gaps apply only to the retained window. `frame_gap_ms` observes native toolbar animator callbacks while it runs, not page composite or host FrameTimeline. No real-site trace, user screenshot, URL or form text is included.

## Physical follow-up procedure (not executed here)

1. Use the same Pixel 7 Pro, scale, page/fixture, blocker settings and binary type for both versions. Preserve app data; never uninstall to resolve a signature mismatch. Check signing compatibility before replacing the installed APK, or use a separate test device/profile.
2. For each 60/120Hz condition and top/bottom × hide OFF/ON × blocker ALL_OFF/ETP_ONLY/LITE collapse OFF/ON, alternate before/after runs under comparable thermal/power conditions; collect at least 20 slow pan/fling/reversal actions in each of three sets. Record touch timing and intentional ANCHOR/FOCUS/INSERT operations.
3. In the Debug diagnostics build, explicitly enable recording, reproduce briefly, explicitly export locally before the 768-event window overwrites the event. Disable afterward. Private tabs are excluded. Diagnostics builds and non-debug APK performance must be compared within the same binary type, not mixed.
4. Synchronize optical DOM-feature movement and fixed/header/footer/toolbar movement with trace timestamps. Separate A content reversal, B viewport movement, C intentional script/anchoring movement and D dropped displayed frames. OTHER alone does not distinguish script vs anchoring vs engine.
5. Use host FrameTimeline alongside Gecko content/profiler/optical evidence, not as a claim of page FPS. Inspect genuine taps/caret/IME/selection drag, rotation/back/tab/home, and protected form/auth/media/upload/private behavior. Keep any real-site recordings locally and review before sharing.
6. Accept the reported issue only after matched reproducible before failures and after absence of unintended reverse/jump. If reproduction is intermittent, report observation rather than declaring resolved.

## Installable APK contract

Version 0.5.1/code 9, application ID dev.velocity.browser. The APK-only distribution artifact contains one arm64 nondebuggable/profileable benchmark APK, R8 optimized and signed using the repository's debug **test** signing configuration; it is not a production-key release. Release APK/AAB also build, but Release APK is unsigned. The separate diagnostics artifact contains one Debug arm64/x86_64 APK with opt-in tracing. Runtime recording/export is inaccessible in distribution builds; ZIP/Dex inspection checks the diagnostic CSV header is removed. Debug/test signing keys can differ across CI runs or from an installed app: do not uninstall existing user data to obtain an upgrade.

## Completed evidence / download

* [APK-only distribution](https://github.com/linkalls/velocity-browser/actions/runs/38249023979/artifacts/11747056741): one Velocity-Browser-arm64.apk. Downloaded independently; ZIP integrity, 16KiB alignment and v2 signature verified. Manifest confirms version 0.5.1/code 9, min 26/target 37, arm64 only. Distribution Dex contains no diagnostic CSV header. [CI APK hash/provenance](scroll-jump-evidence/ci-apk.json), [independent local build](scroll-jump-evidence/local-apk.json).
* [APK-only diagnostics](https://github.com/linkalls/velocity-browser/actions/runs/38249023979/artifacts/11746996808): one Debug arm64/x86_64 APK. Recording remains explicit and process-local; actual private fixture gestures leave zero events.
* ALL_OFF/auto-hide OFF/bottom now keeps max=56 and clipping=0 after geometry refresh. The footer remains painted at Y=480..536; its accessibility bottom satisfies the visible-viewport <=536 assertion and its unadjusted UiObject2.click fires the footer callback. [After image](scroll-jump-evidence/after-0c41a0b/matrix-0-before-tap.png), [after-click image](scroll-jump-evidence/after-0c41a0b/matrix-0-after-tap.png), [trace](scroll-jump-evidence/after-0c41a0b/matrix-0-after-tap.csv). Thus the legacy 56px extra blank is absent and the incorrect tap is corrected in this measured emulator fixture.
* After pinch trace has 114 SAMPLEs: 103 continuous USER_DELTA, one baseline, ten ZOOM_CHANGED baselines. The ten changed-zoom samples no longer accumulate pan intent. Before trace uses the contaminated arithmetic; this is direction-input evidence, not a physical content-FPS or Yahoo-resolution result.

These records validate the exact code source `0c41a0b`; subsequent documentation-only commits do not change application/test sources. The PR links the final documentation-head CI as well. No physical acceptance or improvement rate is inferred.
