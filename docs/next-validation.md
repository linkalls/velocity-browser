# 0.2.1検証結果（履歴）

現在の変更・結果は[0.2.2検証](fix-validation.md)を参照。この文書は過去の成果物とCI設定の記録。

GeckoView Stable 157.0.20261005135250固定。checkout時のmain b487694を精査し、開発中に取得したmain ecd714fのAPK-only artifact変更もmergeして保持した。変更の説明は[next-stage](next-stage.md)、[変更ファイル一覧](changed-files.txt)、機械可読結果は[JSON](benchmarks/next-validation.json)。

## 実行済み

- ローカルJava17 / SDK37.1: debug、AndroidTest APK、Release APK/AAB、app benchmark、最小GeckoView release、benchmarkモジュールの生成成功。最終全体ビルド5分8秒。最後の変更はtests / CI / docsのみで、最終AndroidTestソースの追加コンパイルも33秒で成功。
- JVM: core 39件、失敗0、エラー0、skip0。app:testDebugUnitTestはNO-SOURCEであり、追加成功件数には含めない。
- Bun1.4.3: 18件成功、失敗0、TypeScript型検査成功。
- lintDebug: エラー0、警告23（文言のresources化等は残課題）。
- [Actions run 38123736914](https://github.com/linkalls/velocity-browser/actions/runs/38123736914)、code 0183560: build / Bun / device-smokeの3ジョブ成功。Android API36 x86_64 emulator、実Geckoの通常スモーク8件、失敗0、エラー0、skip0。外部fixture annotationの3件は実行対象外であり、成功件数に含めない。
- 端末スモーク: Lite OFF時到達→ON時遮断、CSS、フォーム付き要素保持、入力中の設定変更、native遮断判定通知、サイト例外、private設定非永続化、Intent/rotation、20項目でもフォーム入力保持、閲覧専用20ページのライブ上限2・同一本文の再表示・追加GETなし、private閲覧専用要求の通常metadata非生成。
- Release arm64署名: apksigner v2/v3と16KiB zipalign成功。APK zip整合性・arm64のみ・内蔵Lite0.2.0・XPI非同梱を確認。
- Bun size CLIとファイル長の再計測が一致。unsigned Release 191,328,682 bytes（182.465MiB）、同条件minimal GeckoView 191,118,123 bytes、差分210,559 bytes（205.624KiB）。Geckoのnative shared object 13個はbaselineと同一hash。[サイズJSON](benchmarks/size.json)。
- 配布用test-signed Releaseは191,345,217 bytes、SHA-256は[artifact JSON](benchmarks/release-artifact.json)。CIのAPK-only artifactは既存mainのbenchmark APK形式を保持し、ここで提供するtest-signed Releaseとは別。

## CIで見つけて修正した問題

- lintのAPI27属性をAPI26 themeから分離し、Typeface.NORMALへ修正。Gecko公開fetch flagsのORはsources JavaDocに従い、欠けたflag IntDefに対する限定suppressionを使用。
- 38122838578は11件中4 failure: 外部fixture/consent未指定のAssumption3件と、Lite OFF比較endpointへの初期到達1件。次の38123195661ではLite試験成功、Assumption3件のみfailure。試験HTTP serverは空接続/read timeoutでaccept loopを終了しないよう修正した。初期到達失敗の正確なネットワーク原因は確定していない。
- 現在のAGPはAssumptionをfailureとしてXMLへ記録する場合がある。通常CIではRequiresExternalFixture annotationの3件を明示除外し、未検証を別管理する。
- classの複数選択を使用した38123461027では4件しか実行されなかったため、全件成功として扱わない。annotation除外へ変更し、8件でなければCI失敗とするguardを追加した。最終38123736914では8件実行を確認。
- JUnit XML namespaceを処理し、case名・失敗理由と集計をCIログへ出す。保存済みレポートの診断workflow成功はアプリテスト成功と区別する。

## 未検証・未測定

- ローカルadb接続端末0、/dev/kvmなし。ローカルinstrumentationは実行不能。GitHub emulatorの実行結果とは区別する。
- 提供arm64 Releaseの物理端末インストール、UIの実機目視、端末固有互換性は未検証。test鍵であり本番配布用の鍵ではない。
- 公式AdGuard XPI導入・完全互換性・HTTPS遮断、Lite HTTPSの3条件付き試験は未検証。個別実行は[端末テスト](device-tests.md)。一般サイトの遮断率・誤検知も未検証。
- ETP単独 / Lite / AdGuardのRAM、CPU、PageLoad比較はNOT_MEASURED。APK容量差とnative cancel判断件数を性能や遮断率へ読み替えない。インストール容量とAAB配信量も未測定。
- 通常の動的Web2枚がUNKNOWN等で保護中なら3枚目は保留のまま。未保存フォーム・upload・認証・mediaを犠牲にする自動破棄はしない。
- 閲覧専用は匿名取得した本文だけ。画像・JS・入力・ログインなし、再表示は先頭から、redirect拒否、private/外部拡張存在時は非対応。再起動後の再取得は内容一致を保証しない。
