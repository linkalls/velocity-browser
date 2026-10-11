# v0.3 step 3: reversible cosmetics

Kotlin and JavaScript share a selector acceptance corpus: bounded IDs/classes, data-ad attributes, restricted id/class equality and at most four compounds. No pseudo selectors, wildcard, selector lists, escaped CSS, scriptlets or executable filter bodies are accepted. Domain ## and #@# are separate from network exceptions; matching cosmetic exceptions override hiding across domain suffixes.

Small original generic advertising markers supply CSS hiding. CSS protects structural main regions, forms/controls, dialogs, editable regions and media/canvas/live regions. Only explicit ad-wrapper/ad-container/data-ad-container/data-ad-wrapper nodes can collapse, at most three ancestors, with visible content/links/iframes/controls protected. Nodes are retained and reversible. Generic banner/header selectors are not built in.

A page-local observer batches child-list changes at 150ms, bounds queues and candidates to 100, and stops above its time budget. Background/pagehide disconnects it; foreground/pageshow re-evaluates. Statistics are bounded observed-element counts across normal/private tabs, not total ads removed or page-wide coverage. No page text, form data, URLs or screenshots are sent as statistics.

Mode changes request disabled policy and wait for content cleanup acknowledgement before disabling Lite. A missing cleanup acknowledgement prevents switching rather than leaving Lite CSS behind AdGuard. Installation uses the same cleanup gate. Engine enable failures retain previous preferences but actual extension state is reported UNKNOWN.

The real-Gecko fixture checks 250px wrapper height, following body position, reversible collapse/exception/mode-disable, intact form values and non-ad iframe layout, plus 100 dynamic insertion cycles. Compiled locally; emulator execution is pending at this step. These cycles are not a CPU or memory measurement. Physical device, SPA pushState-specific changes, layout shifts/jank, long idle memory and AdGuard signed-XPI behavior remain UNVERIFIED / NOT_MEASURED.
