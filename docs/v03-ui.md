# v0.3 step 2: native browser UI

Native GridView recycles two-column tab cards; list mode, local title/domain search, separate normal/private views, 48dp close targets, swipe-close and pin controls are included. Closing protected tabs retains the confirmation. Normal-only in-memory undo lasts ten seconds and reopens the URL; it does not resurrect JS state or the closed session. No page thumbnails are saved.

Settings use a full-height categorized native view. The toolbar includes home/new tab and puts refresh/stop beside the address. Private status and page title are visible. Address position and address-only swipe settings persist. Existing system SAF, authentication and WebAuthn bridges remain unchanged.

Metadata schema 2 reads schema-1 records, retains order/title/pin and excludes private/extension tabs. Snapshot coalescing debounces JSON serialization and disk commits on a single IO owner. load drains the latest queued state; background requests a flush. Abrupt process termination before a commit is not guaranteed durable. Restore opens only the selected tab.

Local core/debug/instrumentation compilation and lint passed before the final title/home adjustment. Final emulator regression, TalkBack, font 200%, predictive-back animation and physical-device 50-card smoothness remain UNVERIFIED; recycling is an implementation property, not a measured performance claim.
