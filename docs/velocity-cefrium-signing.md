# Velocity Cefrium Preview: signing and updatable APKs

The preview application ID is dev.velocity.browser.chromium013.
Do not change that ID after distributing this fixed-key build.

## Signing identity

- Dedicated PKCS12 / RSA-3072 private key generated on the authorized Ubuntu host.
- Local private directory: ~/.local/share/velocity-browser/signing/
- Keystore: velocity-cefrium-preview.p12; password file: signing-password.txt
- Alias: velocity-cefrium
- Repository Secrets: VELOCITY_CEF_KEYSTORE_BASE64, VELOCITY_CEF_STORE_PASSWORD
- Expected public signing certificate SHA-256:
  2E:08:2D:A9:C3:9F:1B:89:42:25:1C:F2:D6:AF:2E:0C:D7:0D:DA:2B:B4:96:62:00:B2:C6:37:6D:A9:0C:50:32

IMPORTANT: Back up the keystore and password to another encrypted location.
GitHub Secrets are write-only; they cannot be used to recover the original key.
Never commit key files to Git. Never expose them to untrusted pull-request CI.

## Workflow and updates

.github/workflows/chromium-013-signed-preview.yml builds the arm64 release
APK on trusted pushes to feat/cefrium-tabs-core. versionCode is assigned
as 200000 + the unique GitHub workflow run number. Reruns keep the same
version number, while newly triggered runs advance it.

Release Gradle builds use the same PKCS12 signing credentials. CI verifies
the signing certificate fingerprint, Android package ID, and versionCode
before publishing a directly downloadable non-ZIP APK artifact.

Publishing to a GitHub prerelease is opt-in via workflow_dispatch. GitHub
may require the workflow file to be on the default branch before displaying
that manual trigger. Ordinary pushes create download artifacts regardless.

Existing debug-signed APKs are NOT signature-compatible. The old debug APK
must be uninstalled once before installing a fixed-key Preview, which deletes
the experimental app's data. Future APKs signed by this key with larger
versionCodes can be installed as updates in place.

This private key is dedicated to the Preview and must never sign the
GeckoView production app.
