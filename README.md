# Velocity Browser 0.2

GeckoViewだけでWebページを表示する、標準View/KotlinのAndroidブラウザです。M0 → M1 → M2 → M3 → M4のソースと、JVM/Androidテスト、GitHub Actions、Macrobenchmark、Bun集計ツールとM6 Velocity Block Liteを含みます。実行結果は [docs/verification.md](docs/verification.md) を参照してください。

**安全性を判定できないWebタブは自動破棄しません。** Stable APIの状態通知だけでは、最新入力の保存、アップロード、決済などの完了を確証できません。そのためこの実装ではWebページをUNKNOWN保護し、2つのWebセッションが開いていると3つ目の表示は保留します。タブ一覧は作成でき、不要タブを確認して閉じ、保留タブを再選択できます。未操作の空タブだけは安全に回収可能です。20タブを自由に往復できる完成版として配布する段階には達していません。[既知の制約](docs/known-limitations.md) とリリースゲートをご確認ください。

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

Debugはarm64-v8a/x86_64。Releaseはarm64-v8a、R8とresource shrinkingを適用。Release APKは未署名で、配布には自分の署名設定が必要です。benchmarkビルドだけはdebugキーで署名し、debuggable=false、profileableにします。秘密鍵をリポジトリに含めません。

今回のダウンロード用 `velocity-browser-0.2.0-release-test-signed.apk` はReleaseビルドをAndroid Debugキーでテスト用に署名したものです（arm64-v8a）。v2/v3署名検証は成功、実機インストールは未検証です。本番配布用の鍵ではありません。[ハッシュ・検証情報](docs/benchmarks/release-artifact.json)。

## 操作

URLまたは検索語を入力して「開く」。検索先は設定からDuckDuckGo/Googleを選べます。HTTP(S)を優先判定し、危険なschemeを検索に偽装して送信しません。戻る・進む・更新/中断・タブ一覧・タブ追加・プライベートタブ・閉じる・保持固定を提供します。ページ表示エラーは安全性を迂回せず停止します。

通常タブのID、タイトル、URL、選択IDのみアプリ内保存。プライベートタブ、フォーム、SessionStateはディスク保存しません。回転時はプロセス内CoordinatorとRuntimeを共有。プロセス終了後は保存済み通常URLを再読み込みし、未保存入力の完全復元は保証しません。

Adaptiveは最大2オープンGeckoSession、非表示120秒で候補判定、前景15秒スキャン。バックグラウンドはタイマー停止、復帰時に再評価。OFFでも2ライブ安全上限は維持します。

接続先読みの設定初期値はON。ただし明示的に許可した通常HTTPSルートで、同一オリジン・非従量制・データセーバーOFF・節電OFF・前景・低メモリでない条件のみ発行。パス/クエリ/フラグメント付き候補は拒否。HTML取得・3つ目の隠しタブは作りません。金融/認証サイトは許可しないでください。独自テレメトリー送信はなく、内部計測JSONは設定からアプリ内に保存できます。

## 広告ブロック（v0.2）

アドレスバーの盾からLite ON/OFF、サイト例外、ユーザー規則、フィルター更新、診断を操作できます。**ETP初期ON、Lite初期OFF**（性能・誤検知が未検証）。同梱リストは最小限、AdGuard全構文には非互換です。Peter Loweのドメインリストの固定HTTPS更新元から明示操作時のみ更新し、未対応規則は件数を記録してスキップ。private設定はメモリのみで最後のprivateタブ閉鎖時に消去します。ON/OFF・例外・規則変更後は手動でページを再読み込みして適用を確認してください。未保存入力を守るため自動reloadしません。

本家AdGuardの公式XPI追加は権限確認付きの実験機能。Liteと同時起動しません。インストール・遮断・RAM/CPU/load性能は実機未検証、Popup/設定タブは未対応です。APKにGPLの本体や外部フィルターは同梱しません。[設計・ライセンス・未検証](docs/adblock-decisions.md)。

## テスト

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
# 実機/エミュレーターが必要
./gradlew :app:connectedDebugAndroidTest
cd tools/bench-runner
bun install --frozen-lockfile
bun test
bun run typecheck
bun src/cli.ts aggregate fixtures/synthetic.json ../../docs/benchmarks/synthetic-example
```

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
