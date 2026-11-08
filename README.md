# Velocity Browser — Chromium / Cefrium 0.17

Velocityは **Chromium（Cefrium 0.17.0）を主エンジン** とするAndroidブラウザ。`main` のルート `:app` は `experiments/chromium-013/app` を参照し、GeckoViewコードは現行のビルド対象から除外している。

## 現在の実装

- 独立した通常／プライベートタブ、12件までの保存タブ、オフラインホーム、下部アドレスバー、タブ／メニューシート。
- Velocity Block Lite：ネットワークドメイン遮断と限定的な静的CSSルール。AdGuardやuBlockの完全互換ではない。
- Android 16の最適化された起動経路、アプリ起動時に保存済みサイトを自動ロードしない設計、無音ホームでの音声フォーカス問題修正。
- Android 16/17エミュレーターでのJava隔離renderer／DOM+JavaScriptスモーク試験。Native-only rendererは未達成。

## Chromiumアプリのビルド

**JDK 25、Android SDK Platform 37.1、Build Tools 36.0.0** が必要。Gradle 9.7.1、Android Gradle Plugin 9.4.1、Cefrium SDK/Plugin 0.17.0を固定している。

~~~bash
# リポジトリのルートで実行。:app は Chromium/Cefrium アプリ。
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug

# R8を適用したarm64リリースAPK（署名情報なしなら未署名）。
./gradlew --no-daemon --max-workers=2 :app:assembleRelease

# エミュレーター用x86_64 APKは明示的にABIを切り替える。
./gradlew --no-daemon --max-workers=2 -PchromiumAbi=x86_64 :app:assembleDebug

# Bunベンチマーク支援ツール
cd tools/bench-runner
bun install --frozen-lockfile
bun test
bun run typecheck
~~~

APKは `experiments/chromium-013/app/build/outputs/apk/` に生成される。アプリIDは現行の署名済みPreviewとの互換性を保つため **`dev.velocity.browser.chromium013`** のまま。古いGeckoアプリのデータを無検証で移行したり上書きしたりしない。

## CIとAPK

- [Android & scheduler](.github/workflows/android.yml)：`main` とPRでChromium root `:app` のテスト・ビルド、Manifest監査、Bun検証を実行。
- [Chromiumエミュレーター検証](.github/workflows/velocity-android-smoke.yml)：Android 16/17のrenderer／DOM・JSスモーク試験。
- [署名済みarm64 APK](.github/workflows/velocity-signed-apk.yml)：固定の署名鍵・単調増加versionCodeでビルド。公開リリース作成は手動かつ明示的なオプトインのみ。

## 残る検証項目

**`main` に入ったことと製品版として安全に公開できることは別。** Pixel 7 Pro実機でのmulti-Surface安定性、タッチ操作／アクセシビリティ、パスキー、添付・ダウンロード、隔離とsite isolation、非公開プロファイルの消去、フィルタの誤遮断、NetLogでのネットワークプライバシー、スクロール／消費電力は未完了。これらを未確認のまま「Chromeより速い」「プライバシー完全対応」とは主張しない。

詳細は[Chromium移行記録](docs/chromium-cefrium-013-migration.md)、[Cefriumリリース検証](experiments/chromium-013/README.md)、[ページ速度測定](docs/cefrium-page-speed-2026-10-28.md)、[静音ホーム修正](docs/cefrium-audio-focus-fix.md)を参照。

## 旧Gecko版

旧GeckoView実装は履歴調査のため `app/`、`core/`、`minimal-geckoview/` 等に残っているが、現行のGradle設定に含まれない。現在の開発・CI・配布の優先対象はChromium版。
