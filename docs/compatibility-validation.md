# 0.2.3 Compatibility Fix

main `310ab2d` の次段階として、日常の基本操作を改善した。GeckoView Stable **157.0.20261005135250**、最大2ライブセッション、UNKNOWN/PINNED、未保存入力・認証・メディアの保護を維持する。0.3の履歴DB・メモリモード、0.4の性能最適化、正式署名は今回の完成範囲に含めない。

## 変更

- `FileUploadController` / `PromptController`: Android SAFで単一・複数ファイルを選択し、読み取り可能な通常タブ用一時ファイルとしてGeckoの公開 `FilePrompt.confirm()` へ返す。MIME/image指定に対応。最大16ファイル、一時コピー合計256MiB。コピーはI/Oスレッドで行う。タブidentity・document epoch・選択状態を検証し、取消や古い応答を別ページに渡さない。選択済みファイルはタブを明示的に閉じるまで保持し、通常ナビゲーションやダウンロードのpage-start通知で削除しない。起動時に前プロセスの残骸を除去する。
- `DownloadController` / `GeckoSessionAdapter`: Geckoの `onExternalResponse()` の元ストリームを一度だけ保存する。cookie・認証付き応答を別HTTPクライアントで取得し直さない。保存先選択、進捗、取消、失敗、実EOF/既知の長さとの一致を扱う。最大2転送、32KiBバッファ、転送ごと8GiB上限。完了前の転送を成功表示しない。保存ファイルを外部アプリへ自動起動しない。
- HTTP認証: 実際のchallenge originと現在のページoriginをネイティブ画面に表示し、非暗号化接続では警告する。通常のユーザー名/password・password-only要求を公開APIへ返す。資格情報のログ、永続保存、autofill、画面キャプチャを抑止する。取消・画面離脱・古いepochは認証成功にしない。
- Passkey: `GeckoRuntime.ActivityDelegate` を接続し、Geckoから渡されたPendingIntentの起動とAndroid結果の返却を行う。GeckoがRP/origin/challengeとcredential形式を管理する。関連origin要求は公開Prompt APIで明示確認し、不整合を拒否する。独自JS/WebAuthn偽装やネイティブアプリ向けCredential Managerへの置換はしない。診断は要求数・OS結果数・取消数と端末機能のみに限定する。OS結果は認証成功を意味しない。
- `ExternalActivityBroker`: アプリ所有の一度だけ完了する操作としてSAF/FIDO結果を管理し、回転を跨いで返す。要求コードを再利用せず、終了済み要求の遅延結果を受理しない。閉鎖・ナビゲーション・選択タブ変更では保留要求を取消する。
- 2ライブで表示保留となった際に「タブ一覧を開く」を追加した。確認付きclose、取消、他タブの入力保持は従来通り。UNKNOWNを解除して通常動的ページを自動破棄する変更はしていない。
- 常設の `profileable` をReleaseから除き、debug/benchmarkに限定した。SAF fixtureとAndroid結果fixtureもdebug限定である。

## 公開APIとPasskeyの限界

採用版sources.jarの `FilePrompt.confirm(Context, Uri[])` はcontent URIを一般的にコピーしない。`_data` や特定のexternal storage pathに頼るため、クラウドDocumentsProviderを含む任意のSAF URIにそのまま対応できるとは保証しない。一時コピーはこの不足を補う。非公開タブはブラウザデータをディスクへ保存しない条件を優先し、現在は添付を明示的に未対応とする。folder/camera captureも未対応。

採用版 `WebAuthnTokenManager.java` / `WebAuthnCredentialManager.java` はAndroid 14+のCredential Manager経路を持ち、Origin権限不足などでGMS FIDO2へfallbackする。Mozilla公式AARのprivileged FIDO clientは、第三者アプリ・署名鍵についてallowlistが必要とソースに記載されている。Manifestへ `CREDENTIAL_MANAGER_SET_ORIGIN` を宣言しても、権限付与・provider互換性・GMS allowlistを保証しない。正式署名での登録やprovider設定は配布者の端末検証課題である。

参照: [Stable sources.jar](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/157.0.20261005135250/geckoview-157.0.20261005135250-sources.jar)、[GeckoRuntime.ActivityDelegate](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntime.ActivityDelegate.html)、[Android GetCredentialRequest.Builder.setOrigin](https://developer.android.com/reference/android/credentials/GetCredentialRequest.Builder#setOrigin(java.lang.String))。JavaDocがmainの最新版を示す場合も、実装判断は固定版ソースに基づく。

## 検証

- Java17 / SDK37.1で全ビルド成功（3分51秒、単一ワーカー/in-process compiler）。core:test、app:testDebugUnitTest、lintDebug、debug/AndroidTest、Release APK/AAB、minimal GeckoView、benchmarkモジュール、app benchmarkを含む。最終の添付試験修正後にAndroidTest APKを8秒で追加生成した。
- JVM48件: failure/error/skipすべて0。app:testDebugUnitTestはNO-SOURCEで成功件数に含めない。Bun18件とTypeScript型検査成功。lintDebugはエラー0、警告27。
- 未署名Release arm64: 191,353,994 bytes（182.489MiB）。同条件minimal GeckoView: 191,118,123 bytes。差分235,871 bytes（230.343KiB）。Bun size CLIとファイル長の計測一致。Gecko native shared object13個は同一hash。[サイズJSON](benchmarks/compat-size.json)。インストール容量・AAB配信量は未測定。
- テスト署名Release: 191,369,849 bytes。v2/v3署名、16KiB zipalign、ZIP整合性、arm64のみ、version0.2.3/code4を検証。署名certは従来と同じ `fde17539cd18040be42f73e708ee3491088c65177802d5e25bca2d00d1c8198e`。Releaseはnon-debuggable/profileableなし、fixture Activity/Providerはdebugだけに存在する。[署名・SHA-256](benchmarks/compat-release-artifact.json)。正式鍵ではなく物理端末インストールは未検証。
- Androidスモークは従来12件に実転送・認証・復元キューの6件を追加して18件。最終 [Actions 38142027301](https://github.com/linkalls/velocity-browser/actions/runs/38142027301)、code `45121ab`: build/Bun/device-smokeの3ジョブ成功。API36 x86_64の実Geckoで18件、failure/error/skipすべて0。[端末結果JSON](benchmarks/compatibility-device-results.json)。外部fixture3件と物理端末は未検証。スキップが1件でもあれば集計ジョブを失敗にし、未検証を成功扱いしない。公開artifactはVelocity-Browser-APKだけで、ソース/テストレポート/画像archiveは含めない。[端末試験](device-tests.md)。

[変更ファイル一覧](compatibility-changed-files.txt)、[機械可読結果](benchmarks/compatibility-validation.json)。

## 制限・未検証

- 物理arm64端末でのインストール、ファイルprovider差異、大容量/低空き容量/通信切断の実転送は未検証。
- Passkeyの作成・ログイン・OSでの取消後再試行は物理端末で未検証。Geckoのabort/不正RP拒否と、合成Android Activityの結果橋渡しは実credential認証の成功証明ではない。
- 通常ページ2枚がUNKNOWN保護中なら3枚目は保留となる。最大2ライブの維持と任意のJS/フォームの完全な破棄復元を両立できる公開API証拠は得られていない。ユーザーの確認なしに回収しない。Balanced/Manualモードは追加していない。
- 保存は通常タブのURL・順序・選択状態などのmetadataに限定。逐次I/Oキューは最新スナップショットの順序を保つが、プロセス終了前の未完了書き込み、フォーム、JS、認証、メディアの完全復元は保証しない。private metadataは除外する。
- 通常添付の一時コピーはタブcloseまで残る。cache eviction、providerの停止、空き容量不足では後続アップロードが失敗しうる。非公開タブ添付・folder/cameraは未対応。非公開タブのダウンロードは、警告を読んでユーザーが保存先を選ぶ明示的exportであり、そのファイルは端末に残る。
- ダウンロードのバックグラウンドservice・再開・プロセス終了後の履歴はない。取消/失敗時にはSAF文書を削除するが、providerの削除拒否やプロセス終了で未完了ファイルが残る場合がある。blob/data URL、HTTP proxy認証は未対応。
- 公式AdGuard導入・外部HTTPS fixtureの3条件付き試験は未検証。Liteの既存loopback試験を一般サイトの遮断率へ読み替えない。ETP/Lite/AdGuardのRAM・CPU・ページ速度、Chrome/Firefox比較は **NOT_MEASURED**。
- 提供APKは同一テスト用署名を継続する。正式な配布鍵・allowlist登録・自動更新・ロールバックは未完成。

## 検証中に見つかった失敗

初回Actions [38140174160](https://github.com/linkalls/velocity-browser/actions/runs/38140174160) はbuild/Bun成功、端末18件中6件失敗（12件成功）。SAF試験がGoogle版DocumentsUIのpackage名に固定され、AOSP版の検出と後片付けが失敗した。HTTP認証再試行も失敗したため、取消後の描画終了を待ち、no-storeの別URLで改めてchallengeを発行する試験へ変更した。最終実行で同じ試験の成功を確認した。

ローカルの初回全ビルドは成功したが、更新後のbenchmark R8で8GiB cgroupのOOM killが発生した。idle Kotlin compilerを停止し、単一ワーカー/in-process compilerで再実行し成功した。端末のRAM性能とは異なるビルド環境の結果である。

2回目 [38140758092](https://github.com/linkalls/velocity-browser/actions/runs/38140758092) はbuild/Bun成功、端末18件中3件失敗（15件成功）。回転/FIDO結果の取消・再試行・古い結果拒否、通常/privateのWebAuthn拒否、既存スモークは成功。SAF保存action検出、実multipart転送、HTTP認証の最終受理で失敗したため、DocumentsUIの大文字action/別resource ID、HTTP chunked本文とheader空白の処理、失敗時fixture診断を追加した。

3回目 [38141415133](https://github.com/linkalls/velocity-browser/actions/runs/38141415133) は17件成功、添付1件失敗。実multipart到達・一時ファイル生成は確認できたが、本文のfield数とaccessibility表示の確認で失敗した。multipart headerの空白を許容し、ファイル内容の一致とJSが読み取った実入力値をGeckoの公開title通知で確認する方式に変更した。最終38142027301では単一/複数のalpha/beta実本文・入力保持・選択取消・close時コピー削除も成功した。
