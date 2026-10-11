# 端末テスト

個人データのない専用テストprofile/端末で実行する。試験は作成したタブを閉じ、設定を変更して復帰する。arm64 Releaseの物理端末インストールと、CIのdebug x86_64エミュレーターは別の検証である。

## 通常スモーク12件

READMEの `connectedDebugAndroidTest` コマンドとActionsのmanual dispatchは、BrowserIntegrationTest 4件、ReadingIntegrationTest 2件、BlockIntegrationTestのprivate設定・loopback遮断2件に加えBrowserUiIntegrationTest 4件（削除キャンセル/48dp、保留/回転/入力保持、外観の保存/Web配色/OS連動、private警告/配色contrast）を実行する。`RequiresExternalFixture` annotationの3件だけを除外し、集計が12件でなければCIを失敗にする。

loopback試験はLite OFFで広告endpointへの到達を確認してから、Lite ONのwebRequest cancel / CSS / フォーム保全 / native遮断判定通知 / サイト例外を検証する。初期到達失敗は遮断成功にしない。試験serverはGeckoの空接続やread timeoutでもaccept loopを継続する。閲覧専用試験は20ページ、実ライブ上限2、本文再表示時の追加GETなしを検査する。

## 条件付き3件（通常CIでは除外・未検証）

必要な引数がない場合のJUnit Assumptionは、現在のAGPのXMLではfailureとして記録される場合がある。全件実行を成功と偽認定せず、対象methodと引数を明示して個別実行する。

Lite HTTPS: [Bun fixture手順](../tools/bench-runner/README.md)に従い、専用DNS・信頼済みTLS・両origin到達を準備する。

```bash
./gradlew :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=dev.velocity.browser.BlockIntegrationTest#liteHttpsBlockingAndExceptionPreserveTwoLiveLimit' \
  -Pandroid.testInstrumentationRunnerArguments.blockFixtureOrigin=https://site.velocity.test:8443
```

公式AdGuard XPI: 既存AdGuardがない専用profile、ネット接続、権限確認UIを使用。AMOの署名済み5.5.2.3を取得し、version / enabled / private除外 / mode切替、2枚保護時の設定保留・キャンセル・確認後の表示、fragment変更後の再利用、失敗したdelegated loadのnull応答と仮タブ解放を検査する。終了時に導入したXPIを削除する。本試験だけでは広告遮断を証明しない。

```bash
./gradlew :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=dev.velocity.browser.AdguardIntegrationTest#officialSignedXpiInstallModeSwitchAndPrivateExclusion' \
  -Pandroid.testInstrumentationRunnerArguments.adguardInstallConsent=true
```

AdGuard HTTPS: アプリの追加導入UIで公式XPIを導入し、AdGuard設定タブのユーザー規則に `||ads.velocity.test^` を保存する。Liteのユーザー規則に入れてもAdGuardには渡らない。上記HTTPS fixtureを用意する。OFF時到達→AdGuard時遮断→mode変更でフォーム入力保持を検査する。

```bash
./gradlew :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=dev.velocity.browser.AdguardIntegrationTest#configuredAdguardBlocksHttpsFixtureWithoutReloadingDraftOnModeChange' \
  -Pandroid.testInstrumentationRunnerArguments.adguardFixtureOrigin=https://site.velocity.test:8443
```

公式導入・HTTPSの実行結果は[fix-validation](fix-validation.md)に分けて記録する。RAM/CPU/PageLoadや一般サイトでの遮断率を、この機能試験から推測しない。

## 失敗レポート

ActionsはJUnit XMLからcase名・失敗理由・集計をログに出す。公開artifactはインストール可能なAPKだけ。テストレポートarchive、ソースZIP、スクリーンショットはuploadしない。失敗時は該当jobログを確認する。CIの部分実行を成功として扱わない。
