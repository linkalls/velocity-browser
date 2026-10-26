# Velocity Chromium · Cefrium 0.13.0

Independent Java-renderer experiment; applicationId `dev.velocity.browser.chromium013`.

See [migration, build instructions and verification](../../docs/chromium-cefrium-013.md).
The 0.9.4 baseline is in `../chromium/`; this root uses only 0.13.0 artifacts.

The tabbed/private Cefrium migration is tracked in
[the migration tracker](../../docs/chromium-cefrium-013-migration.md).
This is not the default Velocity browser until device verification.

Network-only Velocity Block Lite uses its original domain-rule subset, the
snapshot from 2026-10-10, and the retained attribution in assets/licenses.
This is not cosmetic blocking or Gecko XPI compatibility. The separate
Cefrium built-in EasyList behavior is not controlled by this switch.

The Preview browser now has a compact bottom address bar and native tab
management. See the [UX plan](../../docs/velocity-cefrium-preview-ux.md)
and [signed APK update guide](../../docs/velocity-cefrium-signing.md).
The fixed-key APK is not upgrade-compatible with older debug-signed builds.
