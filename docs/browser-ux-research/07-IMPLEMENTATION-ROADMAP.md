# Evidence-Driven Engineering Roadmap — Corrected

**Baseline:** `main` at `8c9c3d1` (2026-11-09). **Do not schedule an architectural rewrite from a static claim that has already been contradicted by source code.** See [04-VELOCITY-AUDIT.md](04-VELOCITY-AUDIT.md) and [09-REVALIDATION.md](09-REVALIDATION.md).

## Feasibility and dependencies

| Workstream | Prerequisite | Engine-level changes? | Decision gate |
|---|---|---|---|
| Insets / IME safe areas | Device layout reproduction on SDK 35 target | No known requirement | Fix verified clipping with regression capture |
| Tab search | `TabRegistry` title/URL queries and proper privacy filtering | No | Measure latency at 20/100/500 tabs |
| 500-tab virtualization | Baseline frames, memory and decoded bitmap cost | Probably not | **Keep current Supplier/viewport binding until measured bottleneck** |
| One-row vs two-row toolbar | Responsive UX prototype at 320dp & 393dp | No | Navigation actions & readable URL meet touch-target bounds |
| Tab grouping gestures | TabRegistry reordering/group model; Undo | No | Test accessibility and conflict with swipe-dismiss |
| Screenshot decode optimization | Profile CDP decode and RAM-cache read path | Maybe | No stale/private thumbnails, memory bounded |
| Private mode security | Verify isolated Cefrium request contexts and Android screenshots | May require Cefrium work | No storage leak; documented privacy behavior |
| Bookmarks/history/downloads | Real durable backend, not decorative buttons | Potential Cefrium integration | Functional acceptance tests, no placeholder actions |

## Priorities

### P0: Validate correctness, layout and safety

**P0.1 — WindowInsets & IME device audit.** Reproduce Android 15/16/17 gesture-navigation, three-button-navigation, keyboard, rotation and split-screen. Test controls against system gesture zones. Inspect current `fitsSystemWindows` behavior; add explicit insets where needed. **Acceptance:** no occluded interactive controls; no keyboard covering address/search actions; before/after screenshots and UI hierarchy attached.

**P0.2 — Large tab-set baseline, no blind RecyclerView rewrite.** Generate deterministic synthetic 20/100/500-tab sets, separately include tab groups, measure jank (frame time p50/p95/p99), peak PSS, allocation, time-to-interactive, screenshot-decode overhead and GC. Compare current `Supplier<View>` viewport strategy to a prototype `RecyclerView` only if measurements warrant it. **Acceptance:** performance dataset and reproducible scripts; no functionality regressions.

**P0.3 — Private browsing isolation and screenshot policy.** Instrument tab close/switch/relaunch, verify profile/cookie/storage isolation, thumbnails, screen capture policy, and logs. **Acceptance:** privacy-focused tests with explicit pass/fail; do not assume that a private UI tab implies a separate engine profile.

**P0.4 — Touch targets and accessibility.** Inspect physical hit bounds (not just icon artwork) for the address row, tab close, switcher actions and Speed Dial. Use at least 48×48dp targets where reasonable, content descriptions and TalkBack ordering. **Acceptance:** XML bounds and accessibility screenshots; no clipped controls.

### P1: Interaction and browsing efficiency

**P1.1 — Search open tabs.** Search title and URL with privacy-mode scoping. Live filter without reloading WebContents; keyboard and back navigation remain predictable. **Acceptance:** 500-tab search <200ms on test device *as a target to measure, not an established result*; zero cross-mode results.

**P1.2 — Toolbar consolidation experiment.** Build two candidates: compact single-line and adaptive two-line. Compare 320dp/393dp with URL legibility, 48dp hit targets, handedness and IME. Do not lock 56dp until proven usable. **Acceptance:** side-by-side emulator/Pixel 7 Pro screenshots and quantified reachable actions.

**P1.3 — Group drag/drop, reorder and swipe-close.** Use native gesture handling with strong cancellation/Undo rules. Existing group rename/ungroup and 2×2 collage are retained. **Acceptance:** tests for reorder persistence, drag/drop and no accidental close while scrolling.

**P1.4 — Modular UI and resources.** Extract browser chrome, settings, tab overview, preview controller, and state reducers gradually from the ~3,417-line Activity. Move user-visible strings to resources; add Japanese/English resources after functionality is stable. **Acceptance:** screen and CI parity, no performance regression, extraction-by-feature PRs.

### P2: Customization, consistency and advanced UX

**P2.1 — Visual design system.** Semantic colors, typography, icons, light/dark themes and responsive tokens. This is a design proposal, not a reverse-engineered copy of Chrome/Vivaldi.

**P2.2 — Inactive tab management.** Optional time-based archive after user-configurable period, explicit restore, no surprise destruction; distinguish Chrome documented policy from Velocity's proposed behavior.

**P2.3 — Optional private-tab biometric lock.** Design screen state and authentication callback; audit compatibility, security and process recreation first. Separate from true storage isolation.

**P2.4 — Real bookmarks/history/downloads.** Only add visible controls with persistence and working navigation; avoid faux feature parity.

## Recommended incremental PR sequence

| PR | Scope | Checks |
|---|---|---|
| A | Audit fixtures, touch bounds, data-backed insets fixes | 320/393dp, gesture + 3-button, Android 16/17 |
| B | 20/100/500-tab reproducible benchmark harness | Frame timing, PSS, thumbnail cache, no user data |
| C | Search and filters for existing tab overview | Regular/private isolation, 500-tab search |
| D | Toolbar layout experiment | Two variants, readable URL, stable WebContents |
| E | Drag/drop group + swipe close | Scroll/gesture conflicts, Undo, persistence |
| F | Modularization + localization | Screen-by-screen diffs, regression CI |

**Conditional PR:** Replace the current holder-based grid with RecyclerView **only after PR B supplies evidence**. Avoid blanket claims of out-of-memory failure without test results.

**Merge gate:** Android build/unit tests + emulator screenshot audits must pass; new UI actions require working handlers; performance regressions and private data leaks are release blockers. Pixel 7 Pro QA remains an explicit manual gate.
