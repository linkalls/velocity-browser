# Phase 2 — Compact toolbar

One 56dp toolbar (72dp at large font scale) replaces the permanent six-button/title rows. Back, integrated site/address/reload, tab count and menu are native 48dp targets. Existing address_bottom preferences survive; an absent key now defaults to bottom. Forward, home, new/private tab, share, download, reader, blocker and settings are explicit menu routes.

No session recreation, detach or load is caused by toolbar construction, placement or menu changes. Home opens a separate tab so it does not overwrite the current draft.

Validation: compile/instrumentation pending. Pixel 7 Pro, gesture navigation and 200% font layout UNVERIFIED.
