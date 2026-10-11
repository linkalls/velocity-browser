# Phase 4 — Settings and home

Settings are flat searchable rows with dividers, switches and chevrons for navigation. Categories follow General, Appearance, Privacy, Tabs/Performance, Downloads/Permissions, Advanced. The 500ms runnable is removed. SharedPreferences listeners, theme notifications, row actions and BrowserHost.render refresh values; listeners unregister when dismissed.

Home is a native scrollable wordmark/search/explicit shortcut list. No feed, remote fetch, default third-party buttons or slogans. Manually entered HTTP(S) shortcuts (at most 12) are normal preferences; private tab URLs are not copied automatically. Existing preferences and tab repository schema are retained. Home URL validation retains the dialog on invalid input.

Validation: compile and device settings/home regression pending. Physical TalkBack, keyboard/insets and 200% font UNVERIFIED.
