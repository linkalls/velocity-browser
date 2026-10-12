# Velocity Browser — スクロールのガタつき修正 + UI/UX全面刷新 実装仕様書

> **Codexへの依頼:** このリポジトリに対して、ここに書かれた改修を**実際に実装・テスト・記録**してほしい。提案文やサンプルコードだけを返して終わらないこと。変更は既存の機能とデータを維持する形で行い、各フェーズの完了条件を満たしてから次へ進むこと。
>
> **対象:** `https://github.com/linkalls/velocity-browser`（Android / Kotlin / Android Views / GeckoView）  
> **仕様作成日:** 2026-10-12  
> **中心課題:** Webページがスクロール中に上下にガタガタする／現行UIの色・デザイン・操作性に一貫性がない。  
> **目標:** 「軽量・速い・片手で自然に使える・視認性の高い」ブラウザ。Firefoxの実用性とVia Browserの簡潔さを参照しつつ、コピーではない独自UIを作る。

## 0. 最初に実行すること

1. 作業時点の`main`のHEAD、未コミット変更、既存のIssue/PRを確認する。仕様作成時点から変更があれば**最新ソースを優先して差分を吸収**する。
2. `README.md`、`spec.md`、`docs/v04/validation.md`、`docs/v04/emulator-comparison.md`、`docs/scroll-ux-validation.md`、`docs/known-limitations.md`、`docs/architecture.md`、既存のAndroid UIテストを読む。
3. 該当コードを確認する：
   - `app/src/main/java/dev/velocity/browser/ui/BrowserChromeView.kt`
   - `app/src/main/java/dev/velocity/browser/ui/BrowserPalette.kt`
   - `app/src/main/java/dev/velocity/browser/ui/DesignTokens.kt`
   - `app/src/main/java/dev/velocity/browser/ui/BrowserIconButton.kt`
   - `app/src/main/java/dev/velocity/browser/ui/ThemeController.kt`
   - `app/src/main/java/dev/velocity/browser/ui/TabSwitcherView.kt`
   - `app/src/main/java/dev/velocity/browser/ui/SettingsView.kt`
   - `app/src/main/java/dev/velocity/browser/ui/HomeInputState.kt`
   - `app/src/main/java/dev/velocity/browser/engine/GeckoSessionAdapter.kt`
   - `app/src/main/java/dev/velocity/browser/tabs/AdaptiveTabCoordinator.kt`
   - `app/src/main/java/dev/velocity/browser/MainActivity.kt`
   - `app/src/main/java/dev/velocity/browser/block/BlockController.kt`
   - `app/src/main/res/values/colors.xml`
   - `.github/workflows/android.yml`
4. 既存テスト・ビルドを先に実行し、失敗があれば自分の変更によるものと区別して記録する。
5. ブランチは`codex/scroll-ui-overhaul`など独立した名前を使う。可能ならフェーズごとに分かるコミットを作る。
6. 外部依存を安易に増やさない。現行の**Android Views + GeckoView**を基本とする。Jetpack Compose全面移行、WebViewエンジン変更、大量のUIライブラリ追加は禁止。必要な依存を導入する場合は定量的な正当化が必要。
7. 実装途中でもアプリが起動・閲覧できる状態を保つ。既存ユーザーの設定やタブデータを勝手に初期化しない。

## 1. 非交渉の必須条件

- **Webページのスクロール品質を最優先。** 見た目だけ変えて原因不明のガタつきを残さない。
- タブ選択、セッション、URL、戻る/進む、リロード、プライベートモード、ダウンロード、複数ファイル選択、WebAuthn/Passkey、権限、HTTP認証、広告ブロック、要素選択、リーダーモード、検索、ホーム、タブ固定、設定永続化などの既存動作を保つ。
- 特に**編集中フォーム・認証・アップロード・動画/音声・メディア・UNKNOWN/保護タブ**を、UIリビルドやパフォーマンス調整の都合で破棄しない。
- `GeckoSession`や`GeckoView`をUI画面の開閉・ツールバー表示切替・テーマ切替だけで作り直さない。タブID、identity、epochを保つ。
- プライベートタブ、検索入力、フォーム内容、非公開データをログや永続ストレージに漏らさない。
- すべてのタップ対象は原則**48dp以上**。TalkBack、システムジェスチャー、キーボード、2倍フォント、横画面、ライト/ダーク、画面回転を考慮。
- 新しいアニメーションやサムネイルを大量生成することでRAM・CPUを悪化させない。単一GeckoViewと既存の安全なタブ管理を維持。
- **測れていない改善を「直った」「120FPSになった」と断言しない。** 実機未実施は`NOT_MEASURED`と明記する。
- 「一部だけ実装」「TODOだけ追加」「提案だけ」「モックだけ」で完了扱いしない。機能、テスト、ドキュメントをセットで更新する。

---

# PART A — スクロールの上下ガタつき修正（P0）

## 2. 既知の観測と仮説

ユーザーの報告：**Webページ自体が上下にガタガタする。** Webの8秒間自動スクロールテストではChromeが約60 FPS・Long frames 0/477、Velocityが約119 FPS・Long frames 4/949、Jank ratio 0.42%。これは異なるリフレッシュレート等が混在した可能性がある**参考値**。平均FPSが高いことは、指でのパン操作、慣性スクロール、ブラウザバーの移動が滑らかな証拠ではない。

現行ソースで重点的に調べる点：

1. `BrowserChromeView.applyOffset()` が `geckoView.setVerticalClipping(visible.toInt())` を使用している。動的ツールバーと静的ツールバーで**クリッピングの基準・符号・単位**が一致しているか要検証。
2. `GeckoSessionAdapter` に、`CompositorScrollDelegate.onScrollChanged` のスクロール方向判定と `ContentDelegate.onShowDynamicToolbar/onHideDynamicToolbar` の**2経路**がある。連続通知、逆方向通知、再入、アニメーションキャンセルで振動しないか。
3. `BrowserChromeView.showToolbar()` は`ValueAnimator`を使い、`applyOffset()`がツールバー `translationY`、上部配置時の`geckoView.translationY`、`setVerticalClipping()`を連動更新している。**Geckoコンポジタとのタイミング不一致**の可能性。
4. `addOnLayoutChangeListener` から二値の`hidden`状態に基づく`applyOffset`が再実行される。**途中アニメーションの位置が飛ぶ**ケースがないか。
5. `syncDynamic()` が`setDynamicToolbarMaxHeight(0/height)`を切り替える。切替時のClipping再設定、コンテンツ位置、キーボード・TalkBack・設定変更のレイアウトに矛盾がないか。
6. ホーム/アドレス入力/広告ブロックのUI更新がスクロールに無関係な`requestLayout`・繰り返し描画・メインスレッド処理を生まないか。
7. 広告のCSS非表示・空白折りたたみ・ページ内MutationObserverによる**レイアウトシフト**が、静的ページでも発生するのか。

これらは**原因候補**であり、原因確定ではない。最初に比較実験をすること。

## 3. 再現・切り分け用テスト（実装前）

テスト行列を作り、最低限次の組み合わせを確認する。

| 軸 | 条件 |
|---|---|
| ツールバー | 自動非表示ON / OFF |
| 位置 | 下 / 上 |
| サイト | DOMが変わらない長文 / 動的DOM / `position:fixed`要素 / 実サイト |
| ブロック | Lite ON / OFF、可能ならAdGuardモード |
| ジェスチャー | 遅い指スクロール / 速いフリック / 指を離した慣性 / 逆方向への切替 |
| 表示 | 60Hz / 120Hz（端末が対応する場合）、縦 / 横 |
| 状態 | 通常 / 非公開、アドレス編集中、IME表示、ダイアログ、TalkBack、テキスト選択 |

- **まず「ツールバーを隠す」OFFで同じガタつきが発生するか**を再現。OFFでも問題が続くなら原因をバーに決め打ちしない。
- `localhost` fixtureをAndroid instrumentationから配信して実際のGeckoで検証する。読み込み完了後DOMが変わらない長い文書、固定フッター、固定ヘッダー、入力フォーム、長いインライン画像、動的DOM追加、広告枠模擬などを用意。
- スクロールを指で操作したときの**Webコンテンツ領域の見かけの位置ジャンプ**と、単なるフレーム落ちを区別する。両者を混同しない。
- 可能ならスクリーン録画、高速撮影、スクロール位置・ツールバー表示高さ・clipping・イベント時刻・Choreographer frame時刻のdebugログを比較。ログはデバッグビルド限定・レート制限・URL/入力情報なし。

## 4. Clippingの修正方針（盲目的な値の反転は禁止）

### 必ず照合する一次情報

- GeckoView `GeckoDisplay.setVerticalClipping`：https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoDisplay.html
- GeckoView `GeckoView`ソース：https://github.com/mozilla/gecko-dev/blob/master/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/GeckoView.java
- Firefox Android `EngineViewClippingBehavior`：https://github.com/mozilla-mobile/firefox-android/blob/main/android-components/components/ui/widgets/src/main/java/mozilla/components/ui/widgets/behavior/EngineViewClippingBehavior.kt
- Firefox Android上下バー対応版：https://github.com/mozilla-mobile/firefox-android/blob/main/fenix/app/src/main/java/org/mozilla/fenix/components/toolbar/navbar/EngineViewClippingBehavior.kt
- GeckoView `ContentDelegate`：https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.ContentDelegate.html

`setVerticalClipping` は「Geckoの画面下部で隠れる/クリップされる領域」を伝えるAPIで、`position:fixed`等の表示位置に関わる。Firefox Androidはツールバーの`translationY`に対して負符号を使う経路がある。ただし、**Velocityのレイアウト構造・動的高さ設定と同じとは限らない**。

実装要件：

1. ツールバーの幾何を状態から定義する。例：`toolbarHeightPx`、`visibleToolbarHeightPx`、`dynamicToolbarEnabled`、`toolbarPlacement`、`keyboardVisible`、`isPinned`。`dp`と`px`を混同しない。
2. 符号の導出根拠をコードコメント・テストで示す。`toolbarHeightPx`が56dpに相当するときだけでなく、端末density=2/3、72dp拡大、上下配置でも成立させる。
3. `setDynamicToolbarMaxHeight`を変更するとGecko側の現在Clipping値がリセットされ得る。切替直後に**正しい値を再通知**し、古い`lastClipping`キャッシュと一致したからといって省略しない。
4. `setVerticalClipping`は**メインスレッド**から呼ぶ。同一px値の冗長通知は抑える。丸めは負値を含め一貫させる。
5. 動的ON/OFF、上/下、完全表示/完全非表示/中間高さの**期待値表**をテストで用意する。単に`visible - height`へ置換して全ケースに適用しない。
6. 上部バーはFirefox Androidの例にならい親コンテナのオフセット制御も比較し、`GeckoView`/`SurfaceView`の直接移動によるちらつきを検証する。
7. GeckoView 157でサポートされるpublic APIのみを使い、Android private API・リフレクションハック・不要な`TextureView`切替を避ける。
8. `setDynamicToolbarMaxHeight`やclippingの適用中にWebセッションをdetach/reloadしない。
9. 端末のテキスト選択時や`shouldPinOnScreen()`が真のときは、GeckoViewの移動を避け、状態回復時に未処理の逆方向要求を安全に扱う。

## 5. ツールバーの状態管理を一本化

`BrowserChromeView`がすべてのイベントに即座にアニメーションを起動する設計をやめ、**単一の状態マシン**にまとめる。

入力：
- コンポジタからの上/下スクロールイベント。
- Gecko `onShowDynamicToolbar` / `onHideDynamicToolbar`。
- IME、URL編集、ダイアログ、TalkBack、フォント倍率、画面回転、アプリ非表示、サイト変更。
- `shouldPinOnScreen`、設定の自動非表示ON/OFF。

状態：
- `Shown`、`Hiding`、`Hidden`、`Showing`、`Pinned`（実際の型・名前は適切に設計してよい）。
- UI上の**現在の可視高さ**と**目標高さ**を分けて管理。`hidden: Boolean`だけから現在位置を決めない。

挙動：
- 同一方向の重複通知は無視。
- 小刻みな方向反転で振動しないよう、距離と時間のヒステリシスを入れる。ただし**実測前に恣意的な遅延を大きくしない**。
- ソース間競合がある場合は順序・有効性を整理し、単にどちらかを削除して戻る/進むスワイプを壊さない。
- タブ切替、URL編集開始、IME表示時は安全にアニメーションを終了させ、表示バーを復元する。
- layout callbackは**中間位置を保持**し、アニメーション中に一気に0/最大値へ飛ばさない。
- 画面回転・Insets・IME変更のたびにGecko全体を余計に再レイアウトしない。
- `ValueAnimator`を使う場合はフレーム更新の責務を一か所に限定し、毎フレームの`requestLayout`や表示内容の再構築を避ける。
- System Animator Scale=0、OSのReduce Motion相当、TalkBack有効時は移動アニメーションを抑制または無効化。
- デフォルトのアニメーションは短く自然に。ページのスクロールを犠牲にするなら**アニメーションを無効化する方を優先**。

## 6. 広告ブロックとDOMの切り分け

- Lite OFFで滑らかになり、ONでガタつく場合はコンテンツスクリプトとCSS要素の遅延処理を調べる。
- 現行の「スクロール終了後180msでDOM調査を再開」という方針を維持し、タッチ移動中の同期DOM読み取り・全要素走査・レイアウト強制を避ける。
- 広告の空白折りたたみでページ本文の高さがスクロール中に変化しないか検証。フォーム・本文・動画・固定ヘッダーは保護。
- 既存のサイト別例外、手動要素非表示、Undoを残し、勝手に広告ブロック機能を丸ごと削除しない。

## 7. スクロール修正の完了条件

- ツールバー自動非表示ON/OFF、下/上で、同じfixtureをスワイプ・フリックしても**目に見えるページ位置ジャンプが起きない**。
- 固定フッター・固定ボタン・入力欄・選択キャレットがバーに重なったり、空白が出たり、タップ位置がずれたりしない。
- 同一のスクロールイベントを何回受けてもバーは不要に往復しない。表示/非表示の途中で反転しても位置が飛ばない。
- UIだけの操作では`GeckoSession`が置き換わらず、タブidentity/epoch・入力フォームが保持される。
- 性能測定には**中央値だけでなくp95/p99、jank、長いフレーム、メインスレッド作業、CPU/PSS**を含める。エミュレータ測定とPixel 7 Pro実測を明確に分ける。
- Perfetto FrameTimelineはGeckoの`SurfaceView`を直接完全に測れないため、**host-windowのframe数値をWebコンテンツのFPSと呼ばない**。必要に応じGecko profilerと実機映像・独立したスクロールテストを併用する。
- 改修前後で同じ端末・画面Hz・同じサイト・同じジェスチャー・同じ広告モード・同じAPK variantを比較。改善しなければ原因と残課題を隠さず報告する。

---

# PART B — UI/UX完全刷新（P1）

## 8. UXの方向性

**スタイル名（仮）：Velocity Minimal 1.0**

コンセプト：
- 2026年にふさわしい、落ち着いた・清潔・密度を最適化したUI。
- 目立つ装飾より**情報の優先順位**。毎日何百回も触るURLバーとタブ切替を最優先。
- 「AI生成っぽいカラフルなグラデーション」「大きすぎるカード」「意味のないバッジ」「過剰なガラス風表現」を使わない。
- Firefoxの安定した操作設計と、Via Browserの軽さ・簡潔さを参考にする。ただし外観を丸ごとコピーしない。
- **UIがシンプルでも機能が隠れすぎない。** 重要な操作は1〜2タップ以内に配置する。
- ライト/ダークは本物の個別配色として設計。単純反転しない。
- デフォルトのアドレスバーは下配置。既存の上/下設定は維持。
- スクロール時のツールバー自動非表示は設定可能。問題が残るなら安全側にデフォルトを見直す。

### 禁止するデザイン

- 標準`Button`の角や色をばらばらに残すこと。
- アクセント青を画面中のすべてのテキスト・ボタンに使うこと。
- 全設定項目を同じ強さ・同じ文字量で羅列すること。
- タブを開く・閉じる・戻るのような基本操作を深いサブメニューに移すこと。
- 通常モードとプライベートモードを見分けられないこと。
- 見た目改善のために頻繁に重いBlur、シャドウ、サムネイル生成を行うこと。

## 9. デザインシステム（すべての画面で共有）

`BrowserPalette.kt`と`DesignTokens.kt`を拡張し、単一のトークン定義からすべてのコンポーネントが色・余白・形を参照する。

### 9.1 色指定（初期案）

| 用途 | Dark | Light |
|---|---|---|
| Background | `#0F1216` | `#F6F8F6` |
| Surface | `#191F24` | `#FFFFFF` |
| Surface Subtle | `#232B30` | `#E8EEEA` |
| Primary Text | `#F4F6F7` | `#19211D` |
| Secondary Text | `#A6B2B8` | `#5D6D65` |
| Accent | `#86D9B9` | `#216A50` |
| On Accent | `#10241B` | `#FFFFFF` |
| Border | `#303840` | `#D9E2DB` |
| Selected/Pressed | `#233E36` | `#D7EAE0` |
| Danger | `#FF8C91` | `#B3261E` |
| Private Indicator | `#C6ACFF` | `#6344A4` |

**注意:** これは目標配色。Contrast Ratioを実装側で検証し、WCAG AA相当を満たさない組み合わせは修正する。アクセントを広範囲に乱用せず、選択状態、ONのトグル、主要CTAのみで使う。

追加要件：
- テーマ選択は**System / Light / Dark**。既存設定キーと移行を維持。
- 色モードを切り替えたとき、ツールバー、ホーム、タブ、設定、メニュー、ダイアログ、システムバー、文字カーソル、選択色を一度に反映する。
- Androidの壁紙からの自動アクセント抽出は後回し。実装する場合もユーザーが固定配色を選べること。
- デバッグ用にテーマ全色を一覧確認できる画面またはスクリーンショットfixtureを作る。

### 9.2 レイアウト・タイポグラフィ

- Spacing scale：4 / 8 / 12 / 16 / 20 / 24 / 32dp。無意味な多重マージンは禁止。
- 標準タップ領域48dp以上、主要操作の間隔に余裕を持たせる。
- URLバー角丸は約16〜20dp。ボタン/入力/カードの半径を4種類程度に統一。
- 通常テキスト16sp、補足13〜14sp、セクション見出し13〜14sp、画面タイトル20〜24sp。文字倍率2倍でも操作可能。
- Androidのフォント設定を尊重。文言を1行に押し込みすぎず、重要でない文言から省略。
- アイコン線幅・サイズを統一（通常20〜24dp）。アイコンの見た目のサイズと実際のタップ領域は分離。
- 選択色やアニメーションはGPU負荷を最小化。`elevation`/Blurを濫用しない。
- モーション：画面遷移/メニュー150〜220msを目安にするが、端末と実測に合わせる。スクロールと同期できないアニメーションは削除。
- Androidのシステムステータス/ナビゲーションバー、ジェスチャーインセット、カットアウト、IMEの安全領域を正しく使う。

### 9.3 共通コンポーネント

標準Android Viewsをベースに、最低限次を共有部品化：
- `VelocityIconButton`（現在の`BrowserIconButton`の発展形）
- `VelocityAddressBar`（表示と編集の2状態）
- `VelocitySectionHeader`
- `VelocitySettingsRow`（title / optional subtitle / trailing chevron / switch）
- `VelocitySearchField`
- `VelocityTabRow` / `VelocityTabCard`
- `VelocityActionRow`（メニューのよく使う操作）
- `VelocitySheet`（下からのメニュー/タブ画面用。Android Dialogの適切なwindow/insetsで実装可）
- `VelocityEmptyState`

同等の構造になれば名前は変更可。**すべての画面で同じ角丸・余白・リップル・アイコン・文字色が適用されること**が重要。

## 10. 閲覧画面のリデザイン

### 10.1 通常表示

下部バーの推奨レイアウト：

    ┌─────────────────────────────────┐
    │                                 │
    │           Web content           │
    │                                 │
    ├─────────────────────────────────┤
    │  ‹  │  🔒  example.com      │  5  │ ⋮ │
    └─────────────────────────────────┘

操作：

- 左：**戻る**（利用不可なら明確に無効表示、48dp）。
- 中央：**アドレス/検索バー**。通常時はドメインを優先表示し、URLの編集/コピー/貼り付け/検索ができる。サイト情報は錠前/盾アイコンをタップしてアクセス。幅が狭い場合はURL入力を最優先。
- 右：**タブ数ボタン**、**メニュー**。各48dp。
- リロード/停止はURLバー内アクションか明確なクイックメニューに配置。現在のリロード動作とロード中の停止表示を維持する。
- 進む、ホーム、新しいタブ、共有、ダウンロード、リーダー、広告ブロック、設定はメニューから1〜2タップで届く。
- URL編集中は戻る/タブ/メニュー等の競合ボタンを隠すか、入力に最適化して検索フィールドを十分広くする。IMEの検索/移動キーが動作し、Backで編集状態を閉じる。
- ローディング表示は必要最小限（細い進捗表示など）。停止と再読み込みの状態が分かる。
- プライベート閲覧ではバーの一部を控えめな紫トーンにし、**非公開モードであることを確実に識別**できる。
- ツールバー固定/自動非表示の設定は残す。**下部ナビゲーションバーと干渉せず、Webコンテンツの描画を跳ねさせない**。
- タブ数が99超の場合の表示、0タブ、アクセスできないページ、読み込みエラー、ダイアログ表示中なども破綻させない。

### 10.2 スワイプジェスチャー

- アドレスバーの左右スワイプ：タブ切替（既存の設定を維持）。水平移動閾値と垂直移動の許容値を調整し、アドレステキスト編集/選択と衝突させない。
- 長押し：URLコピー、サイト情報等へのショートカット。
- ジェスチャーをオフにできる。Androidのシステム戻る操作と競合しない。
- スクロールバー表示/非表示中のタップ対象の位置が変化して誤タップしない。

## 11. タブ画面の完全リデザイン

タブ数が多くても簡単に扱える**ボトムシート型のタブマネージャー**を新設/改修。

期待構成：

    ┌─────────────────────────────────┐
    │ タブ 24              ＋      完了 │
    │ [通常 24]  [非公開 2]       ⌕   │
    │                                 │
    │  ●  Example Page                × │
    │     example.com                   │
    │  ●  日本史の資料                 × │
    │     example.org                   │
    │  ●  GitHub                       × │
    │     github.com                    │
    │                                 │
    │  最近閉じたタブを戻す（可能時） │
    └─────────────────────────────────┘

機能：
- **標準はコンパクト縦リスト**。必要な人だけ2列グリッドへ切替。設定は保持。
- タブタイトル + ホスト名を視認性の高い順序で。現在のタブは薄い選択背景で識別。
- サイトアイコンが存在しない場合は軽い代替アイコン。**ページサムネイルを毎回キャプチャしない。**
- 各行の閉じる`×`は最低48dp。閉じた直後、可能な範囲でUndoを提示。
- 左右スワイプで閉じる場合はスクロール・開く操作との誤判定を抑止。
- 長押し/行メニュー：固定/解除、閉じる、整理。必要なら複数選択と一括整理を設計。ただし複雑化しない。
- タブ検索（タイトル/ホスト名）、通常/非公開の完全な分離、追加ボタンとプライベート新規タブの動線を設ける。
- 休眠中や保護状態は**補足情報としてだけ**表示。通常の閲覧中は「休眠中」を必要以上に強調しない。
- 100タブ以上でも再利用表示でスムーズ。検索文字入力ごとに全画面を再生成しない。
- 保護されたフォームや認証中のタブは既存の閉じる確認を守る。
- Undoは「URL再読み込み」でありフォームやJS状態は保証されない。ユーザーを誤認させない。プライベートの履歴は永続化禁止。
- シート高さ、画面回転、2倍フォント、キーボード、Accessibilityを検証。TalkBackではスワイプ削除以外の操作手段も必須。

## 12. ホーム/新しいタブ画面

現状の「Velocity」の大きな文字と縦並びボタン中心の画面をやめ、**軽くて実用的なホーム**へ変更。

    ┌─────────────────────────────────┐
    │                                 │
    │            velocity             │
    │                                 │
    │   ┌─────────────────────────┐   │
    │   │ ⌕  検索またはURL       │   │
    │   └─────────────────────────┘   │
    │                                 │
    │  [GitHub] [Wikipedia] [学習]    │
    │  [追加]                         │
    │                                 │
    └─────────────────────────────────┘

- ロゴ/ワードマークは目立ちすぎない（必要なら非表示設定）。検索バーが主役。
- ショートカットは**横方向のコンパクトなグリッド**に変更。最大12件程度、タイトル・サイトアイコン/代替記号・削除・並べ替え・追加に対応。
- デフォルトの不要なニュース、勝手なDiscover複製、広告、遠隔サービスの読み込みは追加しない。
- URL/検索入力はキーボードから直接実行できる。検索エンジン設定と連動。
- 通常/非公開タブのホーム入力を混ぜない。既存の `HomeInputState` とプロセス内のプライバシー保護を尊重。
- 不必要な毎フレーム処理やWebViewでのホーム描画は禁止。ネイティブViewで軽く実装。
- ホームからリンクを開く時に、入力途中の別タブ下書きを失わない。

## 13. メニューの完全リデザイン

**現行の設定画面を使い回した長いメニュー**をやめ、片手操作向けのコンパクトな下部アクションシートにする。

上部に4つ程度の「よく使う操作」を1タップで置く：

- 進む
- 更新/停止
- 共有
- 新しいタブ

下部は明確にグルーピング：

- **タブ**：新しいタブ、プライベートタブ、タブを表示。
- **ページ**：ホーム、リーダー、ダウンロード、サイト情報、広告/要素非表示。
- **ブラウザ**：設定、必要な補助機能。

要件：
- シートは画面高の必要分だけ表示し、全画面Dialogの強制使用をやめる。項目が増えたらシート内スクロール。
- 背景・角丸・アイコン・テキスト・区切り・状態表示を統一。
- 無効な機能は見た目だけでなく操作も無効にする。
- メニューから別画面へ移るときの二重DialogやIMEの重なりをなくす。
- Back操作・画面外タップ・スワイプで閉じる挙動を整える。閉じた後、閲覧中ページへ焦点を戻す。

## 14. 設定画面の完全リデザイン

現行`SettingsView`の検索機能・永続化・イベント駆動更新は残し、画面構造を整理する。

カテゴリ例：
1. **一般**：検索エンジン、ホーム、アドレスバー位置。
2. **外観と操作**：ライト/ダーク/自動、自動非表示、タブ表示、アドレスバーのスワイプ、フォント/アクセシビリティ。
3. **プライバシーとブロック**：Lite/AdGuard、サイト別例外、要素非表示、ルール。
4. **タブと性能**：AdaptiveのON/OFF、ライブ目標、休眠開始時間、優先動作。
5. **ダウンロードと権限**：保存先、サイト権限、ファイル。
6. **詳細・診断**：アプリバージョン、ライセンス、既知の制限、性能診断。

- 1項目 = タイトル + 必要時だけ説明 + 右端トグル/矢印/値。1画面に不要な長文を並べない。
- ON/OFFは実際の状態と同期し、設定を開いたまま変更しても表示が即反映される。
- 検索は設定名と説明の両方で動き、結果ゼロ時は明確な空状態。検索欄を開いた瞬間にIMEを勝手に出さない。
- 破壊的な操作は区別し、確認ダイアログを設ける。
- 階層は2段程度に抑え、広告ブロック設定への入口を探しやすくする。
- 下部操作領域やキーボードに項目が隠れない。TalkBackの読み上げ順・focusを確認。
- 旧設定キー（`address_bottom`、`address_swipe`、`toolbar_scroll`、`tabs_list`、appearance modeなど）を移行なしで壊さない。新キーは後方互換のデフォルトを設計。

## 15. システム全体の見た目を統一する

次を忘れず改修：
- 通常の`AlertDialog`、確認画面、権限説明、読み込み/エラー表示、ファイル関連、ブロック設定、容量超過など、**主要な全ダイアログ**。
- プライベートモードの視覚・アクセス性・機密保護。
- 横画面、狭い画面、折りたたみ端末のウィンドウサイズ変更（最低限レイアウト破綻を防ぐ）。
- システムバーのアイコン色、エッジ・ツー・エッジ、IME、ジェスチャーナビゲーション領域。
- ボタン押下のフィードバック、セマンティックなcontentDescription、フォーカス表示、TalkBack。
- RTLの最低限のレイアウト方向対応。表示言語を無理に固定しない。
- タブや設定、メニューを表示/閉じても背後のページ・フォームの状態が変化しないこと。

---

# PART C — 実装アーキテクチャ / テスト / 成果物

## 16. 推奨実装フェーズと順番

### Phase 0 — 現状分析・再現テスト
- 既存ビルド/テストを動かす。
- スクロールON/OFF・バー上/下・Lite ON/OFFの比較ケースを用意。
- `docs/scroll-jank-investigation.md`にイベントフロー・原因仮説・測定制約を記録。
- **完了条件:** 実測結果（または実測不能の明示）と再現手順がある。

### Phase 1 — P0 スクロール修正
- Clippingと動的高さの基準を修正。状態遷移を一元化。
- 重複するスクロール通知・アニメーションキャンセル・中間位置の飛びを修正。
- `shouldPinOnScreen`、上下バー、IME/TalkBack、画面回転を検証。
- Liteによるレイアウトシフトを切り分け。
- **完了条件:** fixtureと回帰テストでジャンプが起きない。実機未検証は別記。

### Phase 2 — デザイントークン統一
- パレット、タイポグラフィ、余白、角丸、アイコン、スイッチ/入力/ボタンを共有化。
- ライト/ダーク・システムバーと既存ダイアログを修正。
- **完了条件:** 共通部品に配色が集約され、新旧コンポーネントの見た目が不一致にならない。

### Phase 3 — 閲覧画面・ホーム
- `BrowserChromeView`のURLバー、操作ボタン、状態表示を再構築。
- 新しいネイティブホームとショートカットグリッドを実装。
- **完了条件:** 戻る・URL・更新・タブ・メニュー・ホーム・プライベート表示が動作し、フォームを保持する。

### Phase 4 — タブマネージャー
- リスト/グリッド、通常/非公開、タブ検索、閉じる、Undo、固定、空状態、アクセシビリティを改修。
- **完了条件:** 100タブ級で落ちず、保護タブが誤破棄されない。

### Phase 5 — メニュー・設定・残りのDialog
- メニューをコンパクトアクションシート化。
- 設定情報設計、サイトブロック設定、各DialogのUIを統一。
- **完了条件:** 既存の公開機能へ1〜2タップで到達可能、全設定が保存される。

### Phase 6 — 実機QA・性能比較・仕上げ
- 画面スナップショット、回帰テスト、アクセシビリティ、エラー系、性能比較。
- パフォーマンスが下がる装飾や不要な表示再構築を削除。
- README、変更履歴、制限事項、検証レポートを更新。
- **完了条件:** 後述の受入基準が満たされ、APKがActionsから取得できる。

## 17. 測定・テストの具体的な要求

### 17.1 実機優先

対象端末：**Pixel 7 Pro**（実機アクセスできなければ明記）。可能ならAndroid 17・120Hz設定で測定。

テスト画面：
- ホーム、新しいタブ、通常閲覧、プライベート閲覧、URL編集、メニュー、設定、タブ一覧、広告ブロック設定。
- 縦/横、ライト/ダーク、システムテーマ変更、フォント倍率1.0/2.0、60/120Hz、IME表示、TalkBack。
- 大量タブ、重いページ、固定ヘッダー/フッター、フォーム編集中、認証中、ファイル選択中。

スクロール計測：
- 画面上の実際のスクロールの位置飛び、指への追従、フリック後の慣性、上下反転。
- フレーム時間分布、イベントから描画までの遅延、host UIとGecko内容領域の区別。
- 同一条件で少なくとも各3回の比較。起動直後・ウォーム状態も区別。

### 17.2 自動テスト

必要な追加テスト：
- Clippingの計算を**純粋関数としてテスト**（上下、静的/動的、0/中間/最大、density違い、IME、回転、負値丸め）。
- バー状態マシンのテスト（連続同一イベント、交互逆転、割り込み、キャンセル、Pinned解除、タブ切替）。
- 実Gecko instrumentationによるスクロールとタブ/フォーム保持のテスト。
- タブ閉じる・Undo・検索・通常/非公開分離・保護タブのテスト。
- メニュー各経路、設定トグル、テーマ反映、ショートカット追加/削除、既存設定移行のテスト。
- 大きなフォント時に要素がクリップされず、重要なボタンへアクセスできるテスト。
- スクロール中に設定の全項目再描画、Gecko再接続、余分なIOが生じないことを確認するテスト。

既存の`V04UiIntegrationTest`などのシナリオを保持しつつ、新UIに応じてセレクターを更新する。**テスト件数を固定値で検査する既存CI**（`.github/workflows/android.yml`の24件前提）があれば、新ケースを追加した際は期待件数も正しく更新し、途中終了やskipを合格にしない。

### 17.3 ビルドと静的検証

リポジトリのREADMEに沿って、利用可能な環境で下記を実行する。`Java 17`、Android SDK Platform 37.1 / Build Tools 36.0.0、固定されたGradle/GeckoViewを前提とする。環境未導入の部分は`NOT_RUN`とし、実行したふりをしない。

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

**Node.js/npm/pnpmへの切替は禁止。JavaScript/TypeScript側はBunを使う。**

### 17.4 GitHub ActionsとAPK

- 既存のActionsビルドを壊さず、最終的に**APK単独artifact**を残す。
- ビルド成果物にソースコード一式を混ぜない。
- デバッグ署名と正式リリース署名の違い、アプリID、アップグレード互換、versionCodeを明記する。
- UI変更のみで署名やアプリデータの互換性を不必要に変えない。
- Androidテスト、Lint、Bun、ビルドのステータスをPR概要に表形式で記録する。

## 18. 受入基準（Definition of Done）

### スクロール
- [ ] ツールバーON/OFF・上/下配置でWebページの上下ガタつきが再現しない、または未解決の場合は再現条件と原因候補を明示。
- [ ] 位置ずれ・余白・`position:fixed`要素・キャレット・IMEの回帰確認。
- [ ] Clipping/動的Toolbarの状態遷移にテストあり。
- [ ] メインスレッド・Geckoのセッション状態を壊していない。
- [ ] 計測条件、測定できた範囲、未測定事項を明示。

### UI/UX
- [ ] カラーパレットは全画面で統一されている。
- [ ] 新ホーム、新URLバー、新タブ画面、新メニュー、新設定画面が**実動作する**。
- [ ] 主要操作へ1〜2タップ、片手でも操作しやすい。
- [ ] 通常/非公開が視覚と音声で区別できる。
- [ ] 48dpの操作領域、ライト/ダーク、2倍フォント、縦横、TalkBackを考慮。
- [ ] 主要画面のダイアログ・空状態・エラー表示まで統一。
- [ ] 不要なアニメーション、blur、連続サムネイル取得を追加していない。

### 互換性
- [ ] 編集中フォーム、ファイル操作、Passkey、権限、広告ブロック、既存タブ保護の回帰を防いだ。
- [ ] 設定/通常タブメタデータを保持し、非公開情報を保存しない。
- [ ] 既存のGeckoViewとBunの構成を守る。
- [ ] READMEと新しい検証ドキュメントが最新。
- [ ] CIが成功し、配布用APK artifactが存在する。未実施のテストは成功扱いにしない。

## 19. Codexに最終報告してほしいフォーマット

1. **原因分析** — スクロールの原因として確認できた事実、未確認仮説、比較検証結果。
2. **実装変更** — ファイルごとの変更理由。P0/P1の対応表。
3. **UIのビフォー/アフター** — 実機またはエミュレーターの主要5画面のスクリーンショット（可能ならライト/ダーク両方）。実行できなければ`NOT_CAPTURED`。
4. **テスト結果** — 実行コマンド、PASS/FAIL、ケース数、失敗原因、環境。
5. **性能比較** — 指標・条件・測定回数・制限・未測定事項。未検証の優位性を主張しない。
6. **互換性とリスク** — Passkey/Gecko/AdGuard/タブ保護で検証できた範囲。
7. **成果物** — コミットSHA、PR URL（作成できた場合）、APK artifactのActions URL。
8. **残課題** — Pixel 7 Pro等での手動確認が必要な事項。

## 20. 参考資料（一次ソース優先）

- Velocity Browser: https://github.com/linkalls/velocity-browser
- 現行検証記録: https://github.com/linkalls/velocity-browser/blob/main/docs/v04/validation.md
- 現行スクロール記録: https://github.com/linkalls/velocity-browser/blob/main/docs/scroll-ux-validation.md
- エミュレーター計測の制約: https://github.com/linkalls/velocity-browser/blob/main/docs/v04/emulator-comparison.md
- GeckoDisplay（ツールバー最大高さとClipping）: https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoDisplay.html
- GeckoSession ContentDelegate（Toolbar show/hide）: https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.ContentDelegate.html
- GeckoView実装: https://github.com/mozilla/gecko-dev/blob/master/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/GeckoView.java
- Firefox Android EngineViewClippingBehavior: https://github.com/mozilla-mobile/firefox-android/blob/main/android-components/components/ui/widgets/src/main/java/mozilla/components/ui/widgets/behavior/EngineViewClippingBehavior.kt
- Firefox Android Fenixの複合ツールバーClipping: https://github.com/mozilla-mobile/firefox-android/blob/main/fenix/app/src/main/java/org/mozilla/fenix/components/toolbar/navbar/EngineViewClippingBehavior.kt
- Android公式48dpターゲット: https://developer.android.com/guide/topics/ui/accessibility/views/apps-views
- Android UI Jank検出: https://developer.android.com/studio/profile/jank-detection
- Perfetto FrameTimeline（SurfaceViewの制約を含む）: https://github.com/google/perfetto/blob/main/docs/data-sources/frametimeline.md

---

## 最終指示

**「スクロール修正」と「UI全面改修」はどちらも必須。** 先に再現・修正・回帰テストを済ませ、そのうえでUI全体を統一する。単なる色変更、サンプルUI、調査だけで終えず、既存機能を残した**実装済みの完成形**へ持っていくこと。性能や実機互換性を保証できない場合は、その不足を明確に示したうえで、実行可能な検証と改善を最大限完了させること。
