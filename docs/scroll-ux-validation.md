# Scroll and settings follow-up

## Changes

- Lite's passive capture scroll listener records a monotonic timestamp, including nested scroll containers. DOM inspection and layout reads are deferred until 180 ms after the last reported scroll. Network blocking and injected CSS remain active. The existing queue/candidate/time limits remain; prolonged mutation overload can still stop optional layout monitoring.
- Browser title, URL, reload label, tab badge and shield descriptions are updated only when their values change, avoiding redundant EditText changes and invalidations during blocker status updates.
- Settings use separate title/detail rows, visible switches for boolean settings, consistent category grouping and local search. Live values reflect changes while the screen stays open; opening settings does not focus the search field or request the keyboard.
- A device case exercises settings filtering, toggling, empty search results and retention of the selected tab. Two deterministic Bun cases run the actual content script to check scroll deferral and background cancellation.

## Validation

Bun: 23 passed, 0 failed; typecheck passed. The first Release/instrumentation compilation passed but lint failed on two Typeface style literal constants; these were replaced with Typeface.NORMAL. Local 0.3.1 Release/instrumentation build passed; lint had 0 errors / 34 warnings. CI run 38153148844 passed the original 20 device cases but the new settings case failed at entry because the icon had no content description (only text). Navigation icons now have explicit descriptions, and obsolete hard-cap settings wording was removed; final rebuild and CI rerun are pending. A first scheduling-test harness used insufficient VM microtask synchronization; awaiting the script's refresh promise fixes the harness, and both tests pass.

Physical-device scrolling, frame-time/jank, TalkBack and 200% font rendering are UNVERIFIED. No smoothness, FPS, CPU or RAM improvement has been measured. AdGuard performance and compatibility are not established by these changes. GeckoView and tab data protections are unchanged.
