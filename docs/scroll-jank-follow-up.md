# Scroll frame-budget follow-up (2026-10-12)

## Report and hypothesis
A physical Pixel device still shows occasional scroll stutter after the 180 ms scroll-quiet deferral shipped in 0.3.1. The original cosmetic observer permits up to 32 ms of synchronous candidate scanning and style reads once that quiet period ends; 32 ms spans several 120 Hz frames. Repeated native chrome renders can also mutate `WindowManager.FLAG_SECURE` and enabled button states unnecessarily. These are plausible causes, **not established root causes**.

## Change
- Keep network blocking and stylesheet-based cosmetic hiding enabled during scrolling.
- Continue deferring DOM/layout inspection for 180 ms after the last scroll event (including nested scrolling).
- Drain a retained, bounded queue of roots/candidates after scroll using batches of at most **4 ms / 12 elements**, scheduled at least 16 ms apart. The 100-root/candidate safety ceiling and protected-element checks remain in place. A single native `querySelectorAll` call is not preemptible; this is a best-effort cap, not a real-time guarantee.
- Recheck previous hidden/collapsed elements incrementally while preserving cleanup on visibility changes and policy updates. Data/form safety and rule semantics are intentionally unchanged.
- Avoid re-setting `FLAG_SECURE` and navigation-enabled states when already correct.
- Add Bun regressions verifying phased inspection and renewed scroll quiet periods.

## Required physical verification
1. Build/install the PR artifact on Pixel 7 Pro. Verify default Lite + cosmetic collapse functionality and that existing 250px gap/form/iframe protections remain intact. Upgrade without clearing any tab data.
2. On identical article/feed pages (one static, one dynamic/lazy loading), capture **FrameTimeline janky frame count, 50th/95th/99th percentile frame duration and long tasks** with Perfetto/Android Studio, and Gecko process CPU. Use device refresh rate, temperature and power state as grouping variables; do not compare incomparable runs.
3. A/B with the previous release and this PR, alternating the order and running at least 20 gestures per case. Measure blocker modes ETP_ONLY and LITE with cosmetic collapse ON/OFF separately to identify whether the remaining jank is page-engine or ad-hiding related.
4. Repeat under fling, nested-scroller and repeated loading/reflows; test fast tab switching, private tabs, large forms, back navigation and the page picker. Ensure no invisible UI/draft loss, no leak after pagehide and no observer overflow beyond bounded protections.
5. If frame time remains high with ETP_ONLY, investigate Gecko compositor/gpu/refresh synchronization and the specific site's JavaScript before attempting riskier runtime flag hacks. Do not disable web security, tracking protection, sandboxing or form safety to improve benchmarks.

**Evidence boundary:** This commit contains scheduling/guard improvements but no physical measurements. Do not claim it makes Velocity faster than Chrome/Firefox without controlled device traces.
