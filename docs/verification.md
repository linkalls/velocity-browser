# 初回0.2.0の検証報告 — 2026-10-11

現在の0.2.1の結果は[次段階の検証](next-validation.md)。以下は初回0.2.0の記録です。

対象: 更新版spec.md v0.2、M0 → M4の実装後にM6を追加。M5のツールは実装、実機測定は未実施。検証状態は実際に実行した範囲で区別する。

## 成功した検証

- Gradle Wrapper公式distribution SHA-256照合、Stable GeckoViewのPOM・sources.jarを取得、APIシグネチャ確認。
- Java17 / AGP9.4.1 / Gradle9.6.0 / Kotlin2.4.20 / SDK37.1 / BuildTools36.0.0構成でAndroidビルド。Debug APK / Release APK / Android test APK / AAB / minimal baseline / benchmark test APKの最終生成が成功（5m 44s、258 actionable tasks: 35 executed, 223 up-to-date）。結果は下のmachine-readable summaryを参照。
- `:core:test`: **26 tests、0 failures、0 errors**。Scheduler18、先読み2、privacy/URL2、Lite規則/版/サイズ/正規化4。
- Bun1.4.3 `bun test`: **15 pass、0 fail**。mock blocking/private/例外/atomic config failure、CSS限定/フォーム保護、量的上限、集計/4mode分離/PSS/CPU stat/parser/privacy scan。
- `bun run typecheck`: 成功。Synthetic JSON→JSON/CSV出力成功。SYNTHETIC_TEST_ONLYを保持。
- Android lint: **0 errors、11 warnings**。新しい依存版案内、API26でのprofileable無視、ChromeOS ABI、backup-rule案内、汎用icon、盾glyphのSetTextI18n等。警告を消去・成功へ偽装していない。
- 本家AdGuard公式AMO XPI 5.5.2.3を静的取得・manifest検査。ID/Android>=113/permissions/options/popupと署名ファイルの存在を確認。[静的検査結果](benchmarks/adguard-static-inspection.json)。これは暗号学的署名確認・実機互換性確認ではない。

`:app:testDebugUnitTest`はNO-SOURCE（JVMテストを`:core`に置く）。0件のappテストを追加成功件数として数えていない。Android instrumentation sourceは6試験、うちHTTPS専用fixtureを要する試験は引数なしでskipする設計。コンパイル成功と端末での成功は別。

## 未検証・未測定

`:app:assembleBenchmark`も生成成功。続けて`:app:connectedDebugAndroidTest`と`:benchmark:connectedBenchmarkAndroidTest`を`--continue`で実行し、どちらも **No connected devices!** で実行不能（全体exit 1、3分8秒）。これはテストassertionの失敗ではなく端末環境不足で、合格扱いしない。

`adb devices`は接続端末0、`/dev/kvm`なし。Android SDK/Javaを導入してビルドできても、実機・エミュレーターでのテストは実行できない。

| 項目 | 状態 |
|---|---|
| Android instrumentation（Intent、回転、フォーム、private metadata/settings、HTTPS遮断） | 未検証 |
| GeckoSession実同時数・120秒実時間・音声/マイク/permission・OS process kill | 未検証（状態機械はJVMで検証） |
| Lite ensureBuiltIn/port/webRequest blocking/CSS/private APIの実動作 | 未検証（APIコンパイル・mockのみ） |
| 更新時の回線断/容量不足/redirect/古い版/再起動の端末fault injection | 未検証 |
| AdGuard installのMozilla署名検証・webRequest・filter update | 未検証 |
| AdGuard Popup/設定タブ/追加タブ | 現hostは未対応 |
| GitHub Actionsの実サーバー実行 | 未実行（workflowを作成、同等build/Bunはローカル実行） |
| ALL_OFF/ETP_ONLY/LITE/ADGUARD RAM・CPU・load・転送量・広告遮断率 | NOT_MEASURED |
| Chrome/Firefox速度比較、5〜10倍高速化、RAM90%削減 | NOT_MEASURED |
| インストール後容量、AAB実download size、電力 | NOT_MEASURED |
| Release署名・store配布・実機セキュリティ受入 | 未完了 |

[4条件比較の未測定記録](benchmarks/adblock-comparison.json)。CPU/PSS CLIを実行したがADB接続失敗でexit 1。出力の測定JSONは作成せず、端末なしでは測定不能。失敗を0ms/0KiBへ変換しない。APKサイズだけは生成済みファイル長から実測。Release APK 191199710 bytes（182.34MiB）、minimal 191118123 bytes、追加81587 bytes（79.67KiB）で2MiB努力予算内。AABファイルは87.81MiB（download sizeではない）。標準Geckoのlibxulは既にstripped、APK内ではmmap用の非圧縮native binaryが大半を占める。

## 再現コマンド

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:assembleRelease :app:bundleRelease \
  :minimal-geckoview:assembleRelease :benchmark:assembleBenchmark
./gradlew :app:assembleBenchmark
cd tools/bench-runner
bun install --frozen-lockfile
bun test
bun run typecheck
bun src/cli.ts aggregate fixtures/synthetic.json ../../docs/benchmarks/synthetic-example
```

実機がある場合: `:app:connectedDebugAndroidTest`、`:benchmark:connectedBenchmarkAndroidTest`。HTTPS試験は `-Pandroid.testInstrumentationRunnerArguments.blockFixtureOrigin=https://site.velocity.test:8443`。セットアップ・信頼済みTLS・許可条件の到達証明・serverカウント確認は[ツール手順](../tools/bench-runner/README.md)参照。

Cloud環境はJavaの外部通信にsession HTTP proxyとJava CA trustStore設定が必要だった。これをソースやCIへ固定せず、通常環境はREADMEのコマンドで実行する。初期SDK36/旧AGP/Kotlin構成はStable157のAAR metadata/stdlib要件で失敗し、公式要求に適合するSDK37.1/AGP9.4.1/Kotlin2.4.20へ修正。最終構成以外の成功を最終artifact検証に流用しない。

## データ保全上の制約と次の優先作業

公式Stable APIだけでは、最新入力の保存完了・アップロード・決済の安全性を証明できない。WebタブはUNKNOWN保護し、自動破棄するのは未操作の空ページだけ。**2つのWebセッションが開いていれば3つ目は保留**。これは仕様の安全性優先に従う保守的実装であり、20ページを自由に往復できる最適化完成版ではない。SessionStateの通知をflush完了として扱わず、private状態をディスクへ書かない。

優先順: 実機の入力/メディア/permission/private/TLSゲート → Lite実API/更新fault試験 → AdGuard互換性 → 4条件/Chrome/Firefox公平測定 → 実測に基づく最適化と安全な破棄対象拡大。数値改善や完全互換性は保証しない。

## 主な作成ファイル

- Root Gradle/Wrapper/version catalog、4モジュール、GitHub Actions。
- core: AdaptiveTabScheduler、Clock/config、tab/protection、UrlPolicy、PreconnectPolicy、Metrics、LiteRules、26 JVM tests。
- app: native Activity/View、共有Runtime、GeckoSessionAdapter、Coordinator、TabRepository、Permission/Prompt controllers、BlockController、内蔵Manifest V2/JS/最小ルール、Android統合テスト。
- benchmark/minimal-geckoview、Bun集計/PSS/CPU/HTTPS fixtures、synthetic input、MIT/Mozilla notices、設計・安全性・計測・拡張判断資料。

全source一覧はsource-manifest.txt、結果は[validation-summary.json](benchmarks/validation-summary.json)、APKサイズは[size.json](benchmarks/size.json)。ソースZIPはbuild/cache/node_modules/local.properties/秘密鍵/AdGuard XPI/外部filterを含めない。
