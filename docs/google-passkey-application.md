# Google Password Manager privileged-caller approval — application checklist

Official request: https://docs.google.com/forms/d/e/1FAIpQLScRuk9tTg0QPfSWl9SbpbIorX8xx2FCXlmoUYftCX2MxG4qyg/viewform
Official technical requirements: https://developer.android.com/identity/sign-in/privileged-apps
Public Google allowlist: https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json

## Verified technical fields

- (1) Official app name: **Velocity Browser**
- (2) Public downloadable signed APK: **PENDING**. Add the publicly downloadable APK release asset URL; GitHub Actions artifacts may require authentication and are not suitable as the sole public download location.
- (3) Package name: **`dev.velocity.browser.chromium013`**. This is the existing stable package ID even though the user-visible name is now Velocity Browser. Changing it would break installed-app upgrades and require a new approval.
- (4) SHA-256 signing certificate and build type: **`release`**, **`2E:08:2D:A9:C3:9F:1B:89:42:25:1C:F2:D6:AF:2E:0C:D7:0D:DA:2B:B4:96:62:00:B2:C6:37:6D:A9:0C:50:32`**. This is the fixed fingerprint asserted by the signed APK workflow and must also be verified against the actual published APK with `apksigner verify --print-certs` before submission.
- (5) Organization: Suggested **Velocity Browser (independent open-source project)**. Confirm the legal/formal entity representation with the maintainer before submitting.
- (6) Organization contact email: **REQUIRES MAINTAINER INPUT**.
- (7) Organization phone: optional.
- (8) Policy/Terms: https://github.com/linkalls/velocity-browser/blob/main/PRIVACY.md and https://github.com/linkalls/velocity-browser/blob/main/TERMS.md (publicly accessible only after the PR containing these files is merged).
- (9) Applicant full legal name: **REQUIRES MAINTAINER INPUT**.
- (10) Applicant role: **Independent developer / maintainer** (confirm before submission).
- (11) Applicant contact email: **REQUIRES MAINTAINER INPUT**.
- (12) Applicant phone: optional.

## Suggested English responses

**(13) What type of app is this?**

Velocity Browser is an independent, open-source Android browser using the Cefrium 0.17.0 Chromium engine. End users browse arbitrary HTTPS websites in normal and private tabs, with local tab grouping, tab previews and navigation history, and optional content blocking. The browser does not require a developer-hosted account and is currently in active compatibility and security testing.

**(14) Why is privileged FIDO passkey access needed?**

As a general-purpose browser, Velocity Browser needs to support WebAuthn passkey registration and authentication for unrelated third-party relying parties using their correct HTTPS website origins. Its Chromium-based Cefrium integration invokes Android Credential Manager with the site's origin. We request Google Password Manager privileged-caller approval for our Android application ID and fixed release-signing certificate so users can choose their existing OS-level Google Password Manager passkeys. We do not request access to credential secrets, and we do not intend to implement a parallel passkey store or bypass relying-party origin checks.

**(15) Data privacy practices**

The browser saves ordinary tab URLs, titles, tab groups, optional reduced-size thumbnails, browsing state and user settings locally in app-private storage. Private tabs are excluded from this saved tab data. The application does not operate a developer-controlled browsing-history collection or account synchronization backend. Web requests go to the user's selected websites and services contacted by those sites and the embedded browser engine. Credential selection and signing are handled through Android Credential Manager and the selected credential provider. Full details are provided in the linked Privacy Policy.

**(16) Data security practices**

Velocity Browser uses Android application sandboxing, app-private local data storage, disabled Android app backup, HTTPS as the default navigation and cleartext-traffic restrictions, and sandboxed Chromium renderer processes. The app uses a consistent Android release-signing key verified in CI and keeps the browser/website-origin boundary enforced through WebAuthn. Private tabs and previews are excluded from persistent saved-tab data. We run automated Android build tests, renderer isolation audits and Android 16/17 emulator smoke tests. Additional physical-device security testing is ongoing; the build is not yet claimed production-ready.

## Approval remains pending

Do **not** mark the application as submitted until the maintainer's legal name and contact information are supplied, the policy and terms are published, the public downloadable APK exists, and the actual release APK certificate is verified. Do not invent applicant identity or claim Google approval merely from the Android Manifest permission.
