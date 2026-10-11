# 0.2.2: タブ操作・拡張設定・外観

main 23d39efからfix.mdの修正要求を実装した。GeckoView Stable 157.0.20261005135250、ライブGeckoSession最大2、UNKNOWN/PINNEDと未保存入力の保護、private metadata非保存を維持する。

## 変更と症状

- Scheduler: 選択中タブのclose後はCOLDを含むMRUへ復帰。CloseSessionをCreateSessionより先に実行する。最後のタブは同じprivate区分の新しいabout:blankに置換。pending要求は対象ID付きでキャンセルし、古い画面が新しい要求を取り消さない。警告時点ではタブやpendingを削除しない。
- タブ一覧: タイトル1行・接続先1行、private識別、選択枠、独立背景/危険色の48dp×48dp削除ボタン。内容説明に対象タイトルを含める。削除前に一覧を閉じず、キャンセルでも一覧とフォームを残す。選択/追加が実際に成功した時だけ閉じる。保留時は理由とキャンセル操作を一覧内に表示する。
- AdGuard: 詳細設定は2枠の中で開く。枠不足画面はライブタブだけを列挙し、確認後に選んだ1枚を閉じてpendingを再開する。取消は要求だけを解除する。optionsの論理IDを再利用しfragment変更で重複を作らない。delegated loadは開いた実GeckoSessionだけを返す。失敗時はnullにしpending/仮タブを残さない。拡張未導入・無効・URLなし・不正origin・busyを区別する。
- ThemeController/BrowserPalette: SYSTEM初期値、LIGHT、DARKを保存。配色はcolors.xmlに集約し、ページは公開GeckoRuntimeSettings.setPreferredColorSchemeへ反映。Runtime/Session再作成、loadUri、reload、自動破棄は行わない。開いているネイティブ確認・入力・permission・拡張同意画面を更新する。回転時は古いActivityのダイアログを破棄し、新しいpending状態から操作画面を再構成する。
- UI: 常設の上部VELOCITY見出しと下部状態行を廃止。アドレス欄に48dpシールド、下部は戻る/進む/更新/タブ/設定。フルURLを表示し危険な省略を避ける。Compose/AppCompat/WebView等の依存追加なし。
- CI: 公開artifactはVelocity-Browser-APKだけ。12スモーク全件の集計を必須とし、外部fixture3件を実行済みに数えない。旧report取得workflowを削除。

## APIの判断

採用したStable sources.jarのGeckoRuntimeSettings公開APIにはCOLOR_SCHEME_SYSTEM/LIGHT/DARKとsetPreferredColorSchemeがある。既存Runtime.settingsへの変更でデバイス状態通知を行う。[公開API](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.html)。WebExtension.TabDelegate.onNewTabの返却値はロード先となる実sessionで、アプリの保留だけでは成功を返さない。SessionStateは未保存フォーム/upload/auth/mediaを全て安全に復元する証拠にはしない。UNKNOWNを解除する条件は拡大していない。

## 検証結果

- Java17 / SDK37.1のローカル全体ビルド成功（3分24秒）。core:test、app:testDebugUnitTest、lintDebug、debug/AndroidTest/release/AAB、minimal GeckoView release、benchmarkモジュール、app benchmarkを含む。最後のUI試験修正はAndroidTest APKを18秒で追加生成した。
- JVM43件: failure/error/skipすべて0。app:testDebugUnitTestはNO-SOURCEであり成功件数に加えない。Bun1.4.3は18件成功、TypeScript型検査成功。
- lintDebug: エラー0、警告22。文言等のresources化は残課題。
- unsigned Release arm64: 191,347,946 bytes（182.484MiB）。同条件の最小GeckoView: 191,118,123 bytes。差分229,823 bytes（224.437KiB）。Bun size CLIとファイル長の再計測が一致。Gecko native shared object 13個はbaselineと同一hash。[サイズJSON](benchmarks/fix-size.json)。インストール容量とAAB配信量は未測定。
- test-signed Release: 191,365,753 bytes。apksigner v2/v3、16KiB zipalign、zip整合性、arm64のみを検証。[署名・SHA-256](benchmarks/fix-release-artifact.json)。CIのbenchmark APKとは別の成果物。
- [Actions 38133338162](https://github.com/linkalls/velocity-browser/actions/runs/38133338162)、3e5f369: build / Bun / device-smokeの3ジョブ成功。API36 x86_64 emulatorの実Geckoスモーク12件、failure/error/skipすべて0。外部fixture3件は除外し未検証のまま。
- 新UIの4試験: 削除ボタン48dp、取消後のnative一覧表示とフォーム保持、最後のclose後の新blank、2保護タブの保留取消・回転後の再選択・確認後の自動表示と他タブの入力保持、LIGHT/DARK/SYSTEM・OS nightのWeb配色追従・保存/再作成・identity/epoch/入力保持、private警告と色contrast。
- Liteの既存loopback試験も成功: OFF時到達→ON時cancel、CSS、フォーム保全、例外、native判定通知。一般サイトの広告率とは区別する。
- Actions artifact APIでVelocity-Browser-APKだけを確認。テストXML/ソース/スクリーンショットartifactなし。

### 失敗の切り分け

初回のローカル全体ビルドではRelease/AAB生成後、benchmarkのR8処理中にGradle daemonが終了した。8GiB cgroupにoom_kill=1を確認。並列数1とidle daemon整理で全体を再実行し成功した。アプリのRAM性能とは別のビルド環境の結果。

CI 38132769314と38133062528のbuild/Bunは成功、device-smokeは12件中1件失敗。削除試験の一覧再表示・警告dismiss後のaccessibility待ちが不足していた。native Dialog.isShowingの保持を直接検査し、実際の削除ボタンが見えるまで待機するよう修正した。成功した11件を全件成功と呼ばない。最終38133338162で同じ削除試験を含む12件成功を確認した。

[変更ファイル一覧](fix-changed-files.txt)、[機械可読結果](benchmarks/fix-validation.json)。

## 制限と未検証

- ローカル接続端末とKVMがないためローカルinstrumentationは未実行。GitHub emulatorと物理arm64 Releaseの検証を区別する。
- 物理端末のUI目視、公式AdGuard XPIの新しい設定経路、Lite/AdGuardのHTTPS fixtureは未検証。条件付き試験と手順は[端末テスト](device-tests.md)。広告率・誤検知・更新全互換・Popup/任意外部URLタブは保証しない。
- ETP/Lite/AdGuardのRAM・CPU・ページ読み込み比較はNOT_MEASURED。APK追加サイズをRAMや速度へ読み替えない。
- 通常の動的Web2枚が保護中の場合は3枚目が保留される。表示するには確認付きcloseが必要。PINNED/UNKNOWNを外したりフォームを強制破棄したりしない。
- 通常フォームの完全なプロセス終了後復元、private永続化はしない。閲覧専用コピーの従来の制限も維持する。
- 提供Release APKはテスト鍵による署名。本番鍵ではなく、物理端末インストールは未検証。
