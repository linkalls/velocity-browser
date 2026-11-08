# Velocity Browser — Privacy Policy

Effective date: November 9, 2026
Maintainer: Velocity Browser, an independent open-source project
Source: https://github.com/linkalls/velocity-browser

## Overview
Velocity Browser is an Android web browser powered by the Cefrium/Chromium engine. It is developed as an open-source project and currently remains under active security and compatibility testing. This policy describes the application's own behavior; websites, Google Password Manager, Android, and the embedded engine have their own data processing and privacy practices.

## Data stored on your device

- Ordinary-tab URLs, page titles, tab identifiers, named tab groups and selected-tab metadata are saved in app-private storage to restore your tab list.
- The browser can store reduced-size screenshots of ordinary tabs in app-private files to show previews. Users can disable future thumbnail capture in Settings. Disabling this option does not currently delete earlier saved previews.
- When supported by the engine, the browser stores the ordinary tab's navigation history locally to resume navigation after the browser process exits. It does not guarantee recovery of JavaScript state, login sessions or unfinished forms.
- Web engine data, such as cookies and cached resources, may be stored on the device by Cefrium/Chromium to support browsing.
- Browser settings and user-defined blocking exceptions are stored locally.
- Private-browsing tabs and their thumbnail data are not intentionally saved to the app's persistent tab database or preview store. Websites and system components may still observe network activity; private mode does not provide anonymity.

## Web browsing and external services

Visiting a website creates network requests to that site and potentially to third parties chosen by the site or the embedded browser engine. The application provides optional domain and cosmetic filtering, but it does not guarantee complete blocking or anonymity. The application does not operate a centralized browser history collection or account synchronization server. Android, the embedded engine and third-party services can have their own diagnostics or network behavior; independent network-privacy testing is still in progress.

## Google Password Manager and passkeys

When a supported website requests a passkey, Cefrium integrates with Android Credential Manager, which may present a system-managed credential provider such as Google Password Manager. The browser should pass the requesting website's origin for phishing protection. Google must separately approve the browser's package and signing certificate before Google Password Manager accepts privileged third-party website credential requests. The browser does not provide a separate passkey vault or sync service.

## Sharing, security and retention

Velocity Browser does not intentionally upload its local tab metadata or screenshot files to a developer-controlled service. Data can be shared with websites by browsing and through explicit Android share actions. Local app data is deleted when the user clears the app's storage or uninstalls it, subject to Android's behavior and third-party services. Application backups are disabled in the Android manifest, but app-private storage is not equivalent to guaranteed end-to-end encryption. Security testing remains in progress; avoid sensitive browsing on development builds.

## Contact and changes

Report privacy questions or problems via the public project's issue tracker: https://github.com/linkalls/velocity-browser/issues. An email contact for regulatory and Google approval requests will be published once confirmed by the maintainer. Changes to this policy will be recorded in Git history.

---

# プライバシーポリシー（日本語要約）

Velocity Browserは、通常タブのURL・タイトル・グループ情報・縮小プレビュー画像・履歴・設定を端末内に保存する。プライベートタブの情報は意図的には永続保存しない。閲覧サイトやAndroid、Cefrium、Google Password Manager等はそれぞれ別の処理を行う場合がある。開発者が運営する閲覧履歴収集・同期サーバーはない。GoogleパスキーはOSのCredential Managerを利用し、ブラウザとしての承認が別途必要。開発版のセキュリティ検証は継続中。問い合わせは上記GitHub Issuesへ。
