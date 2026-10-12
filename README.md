# Velocity Browser 0.5.0

GeckoView Stable **157.0.20261005135250**を唯一のエンジンとする、Kotlin・Android標準Viewのブラウザです。

ライブセッションの2枚固定上限を撤廃しました。既定は**6枚のソフト目標**、非表示120秒から安全に休眠できるタブを評価します。フォーム・認証・メディア・ファイル操作・UNKNOWNのページは保持し、必要なら目標を超えます。RAM使用量の削減や無制限に開ける保証ではありません。生成失敗時は元の画面を保持し、再試行・整理・キャンセルを選べます。

通常閲覧は戻る・アドレス・タブ数・メニューの統合ツールバーです。新規ユーザーは下配置、既存の設定は維持。公式Geckoの通知でスクロール連動し、入力・IME・メニュー・TalkBackでは表示を維持します。タイトルや常設6ボタン行はありません。

タブ一覧は再利用型のコンパクト縦一覧／2列グリッド、ローカル検索、通常／非公開切替、固定、スワイプ終了、通常タブの短期Undoに対応。UndoはURLの再読み込みで、閉じたページの入力やJS状態は復元しません。設定は検索・スイッチ・項目名と説明を分けたカテゴリー別の専用画面です。アドレスバー位置、バー内スワイプ、システム／ライト／ダークを選べます。

Velocity Block Liteは新規プロファイルで既定ON（既存の選択設定は維持）。ネットワーク遮断、限定されたドメイン別CSS・例外、広告専用空枠の可逆な折りたたみ、要素選択・プレビュー・サイト別規則削除／Undoを提供します。本文・フォーム・メディアを保護します。AdGuard全構文には対応せず、遮断率は未測定です。

スクロール用Clippingを静的／動的モード別に計算し、レイアウト通知がバーのアニメーション中間位置を飛ばさないようにしました。IMEやメニューではGeckoの動的高さ基準を保持します。これらの修正だけで実機のガタつき解消を断定しません。[原因調査](docs/scroll-jank-investigation.md)。

Velocity Minimalのライト／ダーク配色を全画面で共有します。ホームはネイティブ検索と最大12件のショートカットグリッド（追加・削除・並べ替え）、URLバーはホスト名表示と完全URL編集、メニューは4つのクイック操作付き下部シートです。タブには通常／非公開の件数・空状態・軽量な代替アイコンを追加し、設定はカテゴリー別カードと検索に統一しました。[0.5検証・画像・APK](docs/overhaul/validation.md)。

ホームは軽量なネイティブ検索・手動ショートカットのみです。設定の500msポーリングは廃止し、イベント通知で更新します。実機のフレーム時間・カクつき改善は未測定です。[段階別変更と検証](docs/v04/validation.md)。

## ビルド

Java 17、Android SDK Platform 37.1、Build Tools 36.0.0、Bun 1.4.3が必要です。Gradle・AGP・Kotlin・GeckoViewはリポジトリで固定しています。

```bash
./gradlew --max-workers=1 :core:test :app:testDebugUnitTest :app:lintDebug
./gradlew --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew --max-workers=1 :app:assembleRelease :app:bundleRelease
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=dev.velocity.browser.RequiresExternalFixture
cd tools/bench-runner
bun install --frozen-lockfile
bun test
bun run typecheck
```

Debugはarm64/x86_64、Releaseはarm64・R8最適化です。通常Release出力は未署名。ダウンロード用Release APKはテスト用Android Debugキーによる署名で、正式配布鍵ではありません。Actionsの`Velocity-Browser-APK`はAPK単独で、非debuggable・計測用profileableのbenchmark variantです。

## データと互換性

通常タブのURL・タイトル・順序・選択・固定をschema 2で保存し、旧版の記録を移行します。保存はまとめてI/Oスレッドで実行し、復元は選択タブだけ先に開きます。プライベートのタブ／規則／例外はメタデータに保存しません。フォーム・JS heap・進行中認証のプロセス終了後の完全復元は保証しません。

通常タブの単一／複数ファイル添付、保存先を選ぶダウンロード、HTTP認証、Gecko標準のWebAuthn OS Activity経路を維持しています。添付は16ファイル・一時コピー合計256MiBまで。非公開添付、転送再開、proxy認証は未対応。物理端末でのPasskey作成・ログインは未検証です。

AdGuard公式署名XPIの追加は実験機能です。GPL本体は同梱しません。Liteと同時起動せず、切替時はLiteのCSS解除を確認します。インストール、実際の広告遮断、設定・更新の物理端末互換性は未検証です。Popupは未対応です。

## 検証

最新の実行結果・APKサイズ・未検証項目は[0.4検証](docs/v04/validation.md)、以前の0.3.1は[検証記録](docs/scroll-ux-validation.md)、0.3.0は[v0.3検証](docs/v03-validation.md)、実装は[タブ管理](docs/v03-tabs.md)・[UI](docs/v03-ui.md)・[Cosmetic](docs/v03-cosmetics.md)・[要素選択器](docs/v03-picker.md)を参照してください。以前の結果は[0.2.3](docs/compatibility-validation.md)・[0.2.2](docs/fix-validation.md)として保存しています。

API36エミュレーターの[CPU/PSS・Activity起動時間の実測比較](docs/v04/emulator-comparison.md)を20回採取しました。新版ではCPU中央値が増加しており、高速化成功とは評価していません。旧版のFrameTimeline不足によりjank改善は判定不能です。Pixel 7 Pro、通常のcold/warm起動、Speedometer、Chrome/Firefox比較、実機スクロール性能は**NOT_MEASURED**です。エミュレーターfixtureを物理端末の結果と扱いません。[性能測定手順](docs/benchmark-methodology.md)・[既知制限](docs/known-limitations.md)・[広告ブロックのライセンス](docs/adblock-decisions.md)。
