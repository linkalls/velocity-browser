# Velocity Browser 0.2.3

GeckoViewだけでWebページを表示する、標準View/KotlinのAndroidブラウザです。M0 → M1 → M2 → M3 → M4のソースと、JVM/Androidテスト、GitHub Actions、Macrobenchmark、Bun集計ツールとM6 Velocity Block Liteを含みます。最新0.2.3は [compatibility-validation](docs/compatibility-validation.md)、以前の0.2.2は [fix-validation](docs/fix-validation.md) を参照してください。

**安全性を判定できない通常Webタブは自動破棄しません。** 動的Webページ2枚の保護中は3枚目が保留となります。今回、公開URLの本文を保持して再表示する「閲覧専用タブ」を追加しました。フォームやスクリプトを含まないコピーをGeckoViewで表示し、最大2ライブを維持して往復できます。通常ページや入力を無断で変換・破棄しません。設定・ホーム・タブ一覧から選べます。画像・リンク操作・認証・JS・スクロール復元はこのコピー経路で未対応です。[次段階の設計・制約](docs/next-stage.md)。

設定 → 外観で「システムに合わせる・ライト・ダーク」を選べます。設定は保存され、既存Runtimeの公開配色APIとネイティブUIへ反映します。テーマ切替によるreloadは行いません。タブ一覧はタイトルと接続先、48dpの閉じるボタンを表示し、削除キャンセル後も一覧が残ります。最後のタブを閉じると同じプライベート区分で新しい空タブを作ります。

## 必要環境

- Java 17、Android SDK Platform 37.1、Build Tools 36.0.0、Platform Tools。
- Gradle 9.6.0（Wrapper、SHA-256固定）、AGP 9.4.1、Kotlin 2.4.20。
- GeckoView **Stable 157.0.20261005135250**。Nightly、WebView、Custom Tabs不使用。
- Bun 1.4.3。補助ツールの依存はbun.lockで固定。
- minSdk 26、targetSdk/compileSdk 37。Android 17の安定SDKとAGP 9.4.1の対応を採用。2026-10-11に公式リポジトリの実在・APIソースを確認。配布先ストアの最新要件は配布時にも確認が必要。

参照: [Gecko Quick Start](https://mozilla.github.io/geckoview/consumer/docs/geckoview-quick-start)、[Stable Maven metadata](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml)、[AGP 9.4.1](https://developer.android.com/build/releases/agp-9-4-0-release-notes)、[Android SDK](https://developer.android.com/about/versions/17/setup-sdk)、[Gradle 9.6.0](https://docs.gradle.org/9.6.0/release-notes.html)。採用版のsources.jarに基づくAPI判断は [decisions](docs/decisions.md) に記録。

## ビルド・インストール

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-37.1' 'build-tools;36.0.0' 'platform-tools'
# local.propertiesにsdk.dirを書いてもよい
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.velocity.browser/.MainActivity
./gradlew :app:assembleRelease :app:bundleRelease
```

Debugはarm64-v8a/x86_64。Releaseはarm64-v8a、R8とresource shrinkingを適用。Release APKは未署名で、配布には自分の署名設定が必要です。計測用benchmarkビルドはdebugキーで署名し、debuggable=false、profileableにします。秘密鍵をリポジトリに含めません。

今回のダウンロード用 `velocity-browser-0.2.3-release-test-signed.apk` はReleaseビルドをAndroid Debugキーでテスト用に署名したものです（arm64-v8a）。v2/v3署名検証は成功、実機インストールは未検証です。本番配布用の鍵ではありません。[ハッシュ・検証情報](docs/benchmarks/release-artifact.json)。

## 操作

URLまたは検索語を入力して「開く」。検索先は設定からDuckDuckGo/Googleを選べます。HTTP(S)を優先判定し、危険なschemeを検索に偽装して送信しません。戻る・進む・更新/中断・タブ一覧・タブ追加・プライベートタブ・閉じる・保持固定を提供します。ページ表示エラーは安全性を迂回せず停止します。

通常タブのID、タイトル、URL、並び順、選択IDのみアプリ内保存。プライベートタブ、フォーム、SessionStateはディスク保存しません。回転時はプロセス内CoordinatorとRuntimeを共有。プロセス終了後は保存済み通常URLを再読み込みし、未保存入力の完全復元は保証しません。

Adaptiveは最大2オープンGeckoSession、非表示120秒で候補判定、前景15秒スキャン。バックグラウンドはタイマー停止、復帰時に再評価。OFFでも2ライブ安全上限は維持します。

0.2.3は通常タブの単一・複数ファイル添付、保存先を選ぶダウンロード、進捗・取消、HTTP認証に対応。添付は最大16ファイル・一時コピー合計256MiBで、タブclose時に削除します。非公開タブ添付・folder/camera・転送再開・proxy認証は未対応。非公開タブのファイル保存は警告後の明示的exportです。設定 → ダウンロード、Passkey診断、データ保護と復元を確認できます。PasskeyはGecko標準のOS Activity経路を接続しましたが、実credential作成・ログインの物理端末検証は完了していません。

接続先読みの設定初期値はON。ただし明示的に許可した通常HTTPSルートで、同一オリジン・非従量制・データセーバーOFF・節電OFF・前景・低メモリでない条件のみ発行。パス/クエリ/フラグメント付き候補は拒否。HTML取得・3つ目の隠しタブは作りません。金融/認証サイトは許可しないでください。独自テレメトリー送信はなく、内部計測JSONは設定からアプリ内に保存できます。

## 広告ブロック（v0.2）

アドレス欄のシールドボタンからLite ON/OFF、サイト例外、ユーザー規則、フィルター更新、診断を操作できます。**ETP初期ON、Lite初期OFF**（性能・誤検知が未検証）。約3,500ドメインの軽量リストを同梱し、AdGuard全構文には非互換です。Peter Loweのドメインリストの固定HTTPS更新元から明示操作時のみ更新し、未対応規則は件数を記録してスキップ。private設定はメモリのみで最後のprivateタブ閉鎖時に消去します。ON/OFF・例外・規則変更後は手動でページを再読み込みして適用を確認してください。未保存入力を守るため自動reloadしません。

本家AdGuardの公式XPI追加は権限確認付きの実験機能。Liteと同時起動しません。インストール・遮断・RAM/CPU/load性能は実機未検証、Popupは未対応です。設定タブは2ライブ枠内で開き、保護中なら閉じるタブを選べます。保護中のタブは確認後だけ閉じ、設定ページへ自動復帰します。キャンセルでは表示要求を取り消し、元のタブを保持します。GPLの本体は同梱しません。LiteにはPeter Loweのドメイン規則と出典・creditを同梱しています。[設計・ライセンス・未検証](docs/adblock-decisions.md)。

## テスト

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
# 実機/エミュレーターが必要
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=dev.velocity.browser.RequiresExternalFixture
cd tools/bench-runner
bun install --frozen-lockfile
bun test
bun run typecheck
bun src/cli.ts aggregate fixtures/synthetic.json ../../docs/benchmarks/synthetic-example
```

通常の端末スモークは18件。外部HTTPS・AdGuardの3件は除外し、未検証として別管理します。実行方法は[端末テスト](docs/device-tests.md)。引数なしの全件実行では、現在のAGPがJUnitのAssumptionを失敗として記録する場合があります。

SchedulerをAndroid非依存の`:core`へ分離しています。上限、LRU、120秒境界、保護、保存未確認、世代/identity不一致、100タブ、復元失敗、先読み条件、ログ情報制限をJVMテストで検証。Syntheticファイルはテスト専用で実機結果ではありません。実機テストを実行できなかったものは「未検証」、性能は「NOT_MEASURED」と記録します。

## 計測

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
bun tools/bench-runner/src/cli.ts memory dev.velocity.browser docs/benchmarks/pss.json
bun tools/bench-runner/src/cli.ts cpu dev.velocity.browser 30000 docs/benchmarks/cpu.json
./gradlew :app:assembleRelease :minimal-geckoview:assembleRelease
bun tools/bench-runner/src/cli.ts size app/build/outputs/apk/release/app-release-unsigned.apk minimal-geckoview/build/outputs/apk/release/minimal-geckoview-release-unsigned.apk docs/benchmarks/size.json
```

[計測手順](docs/benchmark-methodology.md) はChrome/Firefox交互実行、Warm/Cold、先読みON/OFF、ALL_OFF/ETP_ONLY/LITE/ADGUARDの4条件、アプリ子プロセスを含むPSS、Perfetto、30回測定、APK/インストール容量を区別します。5〜10倍高速化・RAM90%削減は未測定の研究目標です。

## ライセンスと依存

独自ソースはMIT。GeckoViewはMozilla Public License 2.0および内包する第三者ライセンスに従います。配布時はAAR内ライセンス/NOTICE等を確認・同梱してください。Geckoをforkしていません。[依存の説明](docs/decisions.md)、[拡張と外部リストのライセンス](docs/adblock-decisions.md)。
