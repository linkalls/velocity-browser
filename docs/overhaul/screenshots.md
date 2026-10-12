# Main / overhaul screen comparison

Actual API36 x86_64 emulator captures, native Android Views and localhost Gecko
fixture. No mockups, resampling or image edits. Main is `16be2395`; final source
and successful Actions run are recorded in validation.md. Pixel 7 Pro: NOT_CAPTURED.

| Screen | Main Light / Dark | Overhaul Light / Dark |
|---|---|---|
| home | [Light](screenshots/before/light-home.png) · [Dark](screenshots/before/dark-home.png) | [Light](screenshots/after/light-home.png) · [Dark](screenshots/after/dark-home.png) |
| browser | [Light](screenshots/before/light-browser.png) · [Dark](screenshots/before/dark-browser.png) | [Light](screenshots/after/light-browser.png) · [Dark](screenshots/after/dark-browser.png) |
| tabs | [Light](screenshots/before/light-tabs.png) · [Dark](screenshots/before/dark-tabs.png) | [Light](screenshots/after/light-tabs.png) · [Dark](screenshots/after/dark-tabs.png) |
| menu | [Light](screenshots/before/light-menu.png) · [Dark](screenshots/before/dark-menu.png) | [Light](screenshots/after/light-menu.png) · [Dark](screenshots/after/dark-menu.png) |
| settings | [Light](screenshots/before/light-settings.png) · [Dark](screenshots/before/dark-settings.png) | [Light](screenshots/after/light-settings.png) · [Dark](screenshots/after/dark-settings.png) |

Validated source: `88578f7`, [Actions](https://github.com/linkalls/velocity-browser/actions/runs/38228147415).
The baseline Light browser image has a white Gecko surface at this capture point,
although the fixture's title/accessibility tree was ready. It is not evidence of
page paint failure or a performance difference. The Dark baseline shows the
fixture with a 56px fixed-footer gap; final browser images remove that gap.
