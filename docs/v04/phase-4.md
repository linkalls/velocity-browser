# Phase 4 — Settings and home

Settings are flat searchable rows with dividers, switches and chevrons for navigation. Categories follow General, Appearance, Privacy, Tabs/Performance, Downloads/Permissions, Advanced. The 500ms runnable is removed. SharedPreferences listeners, theme notifications, row actions and BrowserHost.render refresh values; listeners unregister when dismissed.

Home is a native scrollable wordmark/search/explicit shortcut list. No feed, remote fetch, default third-party buttons or slogans. Manually entered HTTP(S) shortcuts (at most 12) are normal preferences; private tab URLs are not copied automatically. Existing preferences and tab repository schema are retained. Native home input drafts (4096-character UI limit) are stored only in process RAM by logical tab ID, survive activity rotation and are removed when the tab closes. Closing a tab with a native draft requires confirmation and rejects a changed draft. They are not persisted, even for normal tabs. Home URL validation retains the dialog on invalid input.

Validation: compilation, API36 settings/home and 200% landscape/private-draft rotation tests passed. Physical TalkBack, OEM keyboard/insets and full-device 200% QA remain UNVERIFIED.

## Changes and final validation

- `app/src/main/java/dev/velocity/browser/ui/SettingsView.kt`
- `app/src/main/java/dev/velocity/browser/ui/HomeInputState.kt`
- `app/src/main/java/dev/velocity/browser/VelocityApp.kt`

The final per-phase results and outstanding verification are recorded in [validation](validation.md). Executed final-source checks and physical-device limitations are distinguished there.
