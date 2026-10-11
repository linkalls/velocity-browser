# v0.3 step 4: element picker

Lite's isolated content script lets users preview a small unique ID/class/data-ad selector, expand/restore the selection and apply or cancel. Structural regions, forms, dialogs, media, editable content and nearly viewport-sized elements are rejected. Temporary handlers/styles are removed on exit, pagehide and tab background. The page DOM and form values are not sent to native code; only a canonical origin and checked selector are sent through the extension's authenticated background port.

Native validation captures selected tab ID, session identity and navigation epoch. A delayed selection from another document cannot save a rule. Saved rules are domain-scoped and private rules remain memory-only. Site rules can be removed; undo removes the last newly introduced picker rule. The normal rule parser still bounds bytes/rules. Duplicate existing rules are not removed by undo.

A fixture now includes picker apply/undo and reversible layout checks. Final CI execution is pending. Full AdGuard cosmetic compatibility, screenshots/thumbnails, generic external cosmetic lists and remote scriptlets are outside this implementation.
