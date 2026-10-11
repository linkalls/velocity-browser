# v0.3 step 1: soft session targets

Balanced defaults to six live sessions and 120 seconds hidden before safe reclamation. Memory and Fast profiles use 3/30 seconds and 12/600 seconds. These are targets, not admission limits. UNKNOWN and every existing protection remain intact. Adaptive OFF disables automatic reclamation, including pressure-triggered reclamation.

At target, only a certified hidden LRU session can be closed. The currently displayed session is retained for allocation rollback. Without a safe victim, the target is exceeded and TARGET_EXCESS is recorded. Allocation failure retains logical metadata, restores the previous live view and offers explicit retry, organization or cancellation. It cannot guarantee survival of an OS process kill or a fatal native-engine OOM.

Stable 157 sources confirm setActive(false) signals invisibility and triggers a state flush. restoreState documents history, scroll, zoom and saved form data; it does not guarantee JS heap or ongoing operations. No new restoration evidence is inferred from that callback.

Executed: core unit tests and debug/instrumentation compilation locally. Physical-device memory, media/camera/microphone behavior and performance remain UNVERIFIED / NOT_MEASURED. The updated protected-20-session emulator regression is compiled but not yet run at this step.
