# 変更履歴

## 0.5.0 — 2026-10-12

- Geckoの静的・動的ツールバーのClipping基準を分離。アニメーションの現在位置をレイアウト時に保持し、重複・競合通知、選択ピン、IME・メニューの固定状態を整理。
- Liteの180msスクロール待機を維持し、広告DOM調査を4ms／12要素の小分け処理へ変更。
- Velocity Minimalのライト／ダーク配色、共通のネイティブ操作部品、URLの表示／編集、ホームグリッド、タブシート、クイックメニュー、カテゴリー設定を実装。
- 既存の設定キー・タブ保護・セッション所有者を維持。APKは既存アプリID・署名設定のままversionCode 8へ。
- Clipping・割り込み・Lite ON/OFF・ショートカット整理・操作経路・コントラストの回帰テスト、前後の画像、エミュレータ性能記録を追加。

実機スクロールのガタつき解消や性能向上は未確定。Pixel 7 Pro、実機Passkey／AdGuard／TalkBackの検証範囲は[検証記録](docs/overhaul/validation.md)を参照。

以前の結果は[0.4](docs/v04/validation.md)、[0.3](docs/v03-validation.md)に保存。
