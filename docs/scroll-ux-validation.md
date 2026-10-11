# Scroll and settings follow-up

## Changes

- Lite's passive capture scroll listener records a monotonic timestamp, including nested scroll containers. DOM inspection and layout reads are deferred until 180 ms after the last reported scroll. Network blocking and injected CSS remain active. The existing queue/candidate/time limits remain; prolonged mutation overload can still stop optional layout monitoring.
- Browser title, URL, reload label, tab badge and shield descriptions are updated only when their values change, avoiding redundant EditText changes and invalidations during blocker status updates.
- Settings use separate title/detail rows, visible switches for boolean settings, consistent category grouping and local search. Live values reflect changes while the screen stays open; opening settings does not focus the search field or request the keyboard.
- A device case exercises settings filtering, toggling, empty search results and retention of the selected tab. Two deterministic Bun cases run the actual content script to check scroll deferral and background cancellation.

## Validation

Bun: 23 passed, 0 failed; typecheck passed. The first Release/instrumentation compilation passed but lint failed on two Typeface style literal constants; these were replaced with Typeface.NORMAL. Local 0.3.1 Release/instrumentation build passed; lint had 0 errors / 34 warnings. CI run 38153148844 passed the original 20 device cases but the new settings case failed at entry because the icon had no content description (only text). Navigation icons now have explicit descriptions, and obsolete hard-cap settings wording was removed; Final local rebuild on `8183e96` passed Release/instrumentation compilation and lint (0 errors / 34 warnings). App and built-in Lite are both version 0.3.1. A first scheduling-test harness used insufficient VM microtask synchronization; awaiting the script's refresh promise fixes the harness, and both tests pass.

Physical-device scrolling, frame-time/jank, TalkBack and 200% font rendering are UNVERIFIED. No smoothness, FPS, CPU or RAM improvement has been measured. AdGuard performance and compatibility are not established by these changes. GeckoView and tab data protections are unchanged.

## CI history

- Run [38153148844](https://github.com/linkalls/velocity-browser/actions/runs/38153148844): build/Bun passed; 20 device cases passed, new settings entry case failed (missing icon description), fixed in `4ca7fa3`.
- Run [38153533339](https://github.com/linkalls/velocity-browser/actions/runs/38153533339) on `4ca7fa3`: build/Bun passed; all 21 API36 x86_64 device cases passed, 0 failures/errors/skips, including settings search/switch/selected-tab preservation.
- Run [38153593511](https://github.com/linkalls/velocity-browser/actions/runs/38153593511) on final source `8183e961016f2f42e205475562fe0289342b2e2a`: build/Bun passed. Attempt 1 terminated during WebAuthn after 13 reported cases (12 passed, 1 failed, 8 unexecuted); the log does not establish the cause. This partial execution is not a pass. Attempt 2 again ended after 13 cases (11 passed, 2 failed, 8 unexecuted), including a stale-close assertion executed on the UI thread. This is not counted as success. The original cause of the WebAuthn termination is not established by the logs. Test assertions now propagate to the instrumentation thread without killing the UI thread, and stale close confirmation is re-requested through the actual UI. Production tab guards are unchanged.

- Run [38154437491](https://github.com/linkalls/velocity-browser/actions/runs/38154437491) on `fc843db`: build/Bun passed and all **21 device cases passed** (0 failures/errors/skips), but the emulator step then failed because diagnostic commands were supplied as separate action input lines. The action executes each line in a separate shell. Commit `1f46028` moves diagnostics and exit-status handling into `tools/device-smoke.sh`; Bash syntax and stubbed Gradle exit 0/7 propagation checks passed. The final rerun passed all jobs. Application sources and the delivered APK remain identical to `8183e96`.

- Final run [38154856383](https://github.com/linkalls/velocity-browser/actions/runs/38154856383) on `1f46028ad297cde7b64ac89577c3e6c17ee96d09`: **build, Bun and device-smoke all passed**. Device totals: **21 tests, 0 failures/errors/skips**; Bun: **23 passed**, typecheck passed. APK-only artifact verified. This includes the actual settings search/switch case and both protected-tab/draft suites.

## APK

[Artifact/size record](benchmarks/scroll-ux-artifact.json). Release arm64 build, versionCode 6, local test-key signature (not production). ZIP integrity, v2/v3 signature and 16KiB alignment passed. APK installation and real-device upgrade are UNVERIFIED; physical scroll smoothness is NOT_MEASURED.
