# Velocity Browser v0.3 — UX / Adaptive Tabs / Cosmetic Filtering 改修仕様

> 対象リポジトリ: https://github.com/linkalls/velocity-browser  
> 対象ブランチ: `main`  
> 調査時 HEAD: `94d93c7ed075e5feb3e05c4b3161637b669a3f5f`（2026-10-11 時点）  
> ファイルの用途: Codex Cloud へそのまま渡す開発仕様。既存コードを読み、段階的に実装・検証する。  
> 優先度: タブ2枚制限の撤廃 > タブ/設定UI > 広告跡の空白解消 > 実機検証・最適化

## 0. 背景と最終目標

Velocity Browser は Android の Kotlin + 標準 View + GeckoView による軽量ブラウザ。現在の `0.2.3` は GeckoView Stable 固定、単一表示 `GeckoView`、共有 `GeckoRuntime`、Pure Kotlin scheduler、通常タブメタデータの保存などの基盤を備える。一方でユーザーは以下に不満がある。

1. ライブタブ上限が2枚で、3枚目やAdGuard設定を開くと既存タブを閉じるよう要求される。**これは廃止**。通常のWebブラウザと同様に多数のタブを開き、自由に切り替えたい。
2. タブ一覧がダイアログ式・テキスト主体で、閉じる/追加/切替が直感的でない。UIを明確かつ高速にしたい。
3. 設定・広告ブロック・外観等の操作が多数のダイアログに散らばる。専用画面と整理されたメニューが必要。
4. 広告の通信は遮断できても、広告枠の余白や空コンテナが残る。**AdGuardのように広告要素を非表示にし、残った広告専用の空白も安全に詰めたい**。
5. 既存のアップロード、ダウンロード、パスキー実験経路、ライト/ダーク、プライベート、AdGuard XPI経路、GitHub Actions のAPK公開は壊さない。

### プロダクト要件

- 「2枚しか同時に開けない」制限は禁止。論理タブ数と実際のライブGeckoSession数を独立して管理する。
- **デフォルトはバランス: ライブ目標6枚、非表示2分後から省メモリ候補を評価。** ただし6は**ソフト目標**であり、保持が必要なセッションを守るための一時超過は許可する。6枚しか開けないという意味にしない。
- 低RAM端末では目標を自動調整できる。フォーム入力、ログイン・決済フロー、再生、ファイル操作、権限、WebAuthnを保護する。
- ネットワーク広告ブロックと cosmetic filtering（要素非表示＋広告専用空白の縮小）を区別して実装する。
- 実装していない互換性や未計測の速度改善を「達成済み」と書かない。実機結果と単体テスト結果を区別する。

## 1. 現行実装に基づく問題箇所

必ず現在の `main` を再取得し、以下の各ファイルを確認してから変更する。HEADが変化していれば差分を再評価する。

| ファイル | 確認済み状態と対応 |
|---|---|
| `core/src/main/kotlin/dev/velocity/browser/tabs/AdaptiveConfig.kt` | `maxLiveTabs=2`、`require(maxLiveTabs in 1..2)`。固定ハード上限を撤廃する。 |
| `core/src/main/kotlin/dev/velocity/browser/tabs/AdaptiveTabScheduler.kt` | `SelectTab` が枠不足・安全なeviction不可の場合 `pendingId` + `ShowEvictionBlockedNotice` を発行。安全を崩さず選択可能なよう再設計。 |
| `app/src/main/java/dev/velocity/browser/tabs/AdaptiveTabCoordinator.kt` | `AdaptiveConfig()` 固定生成。 `adapters.size <= config.maxLiveTabs` を検査。可変設定・一時超過・メモリ圧の取り扱いを統合。 |
| `app/src/main/java/dev/velocity/browser/ui/CapacityDialog.kt` | 「ライブタブは最大2枚」→既存タブを閉じさせる導線。通常経路から除去し、メモリ確保失敗時だけ**非破壊**の案内を用意。 |
| `app/src/main/java/dev/velocity/browser/ui/TabSwitcherView.kt` | Dialog + LinearLayout再生成 + ScrollView。グリッド/一覧表示、検索、スワイプ、閉じる取り消し対応の UI に改修。 |
| `app/src/main/java/dev/velocity/browser/ui/BrowserChromeView.kt` | 上部URL入力 + 下部5アクション。`title`/`privacy` の可視表示を再検討。ホームの `閲覧専用タブ` は高度機能へ移動。 |
| `app/src/main/java/dev/velocity/browser/MainActivity.kt` | 設定がAlertDialog項目一覧。カテゴリ別画面に分離。 |
| `app/src/main/assets/velocity-block/engine.js` | cosmetic selector が少数の `safeSelectors` に固定。|
| `app/src/main/assets/velocity-block/cosmetic.js` | ルール更新時にstyleを差し替えるだけ。広告専用の空コンテナを折りたたむ機能なし。 |
| `core/src/main/kotlin/dev/velocity/browser/block/LiteRules.kt` | CSS規則が固定少数セレクタしか認められない。検証付きの限定的拡張が必要。 |
| `app/src/main/assets/velocity-block/background.js` | content scriptに限定的CSSを送付。ホスト/非公開/例外の判定は既存の分離を維持する。 |
| `app/src/main/java/dev/velocity/browser/block/BlockController.kt` | `LITE` と `ADGUARD` を排他選択、同梱Peter Lowe主体。規則配信/バージョン/誤検知対策を拡張する。 |
| `app/src/main/java/dev/velocity/browser/tabs/TabRepository.kt` | URL/タイトル/選択IDのみ保存。大量タブ時の書き込み・並び順・Undo・プライベート除外に注意。 |

現行 `engine.js` の `display:none!important` は**対象要素をレイアウトから外す**が、親の固定高さ・空ラッパーまで必ず消すわけではない。Peter Loweドメインリストは主にネットワークブロック用であり、それだけではページ広告の余白問題は解決できない。

## 2. タブ管理の新しい設計（P0）

### 2.1 論理タブとライブセッションを分離

- **論理タブ**: タイトル、URL、固定状態、最終アクセス、通常/非公開、読書用フラグ、休眠表示、必要ならサムネイルキー。数十〜数百枚を想定し、ライブ数に連動した厳しい上限は設けない。
- **ライブタブ**: 実際に open されている GeckoSession。表示中1つ、非表示は `setActive(false)` / `setFocused(false)` にする。GeckoViewは1個を維持する。
- **休眠タブ**: GeckoSessionを安全にclose済みで、URLや必要なメタデータを保持する。戻す際は再表示/再取得を行い、完全な状態復元と誤表示しない。
- タブ数が非常に多い場合（例: 500枚以上）は任意の整理提案を出してよいが、勝手に閉じない。既存読み込み1000件の制限はUI/APIの仕様と整合させ、理由のない切り捨てを防ぐ。

### 2.2 初期プリセット（要実測調整）

| プリセット | ライブのソフト目標 | 非表示タブ休眠判定 | 対象 |
|---|---:|---|---|
| 省メモリ | 3 | 30秒以降 | RAMの少ない端末 |
| **バランス（既定）** | **6** | **120秒以降** | 一般用途 |
| 高速重視 | 12 | 600秒以降 | 十分なRAM |
| カスタム | 2〜32（目標値） | 30〜1800秒 | 詳細設定から変更 |

- 「ライブ目標」は**ハード上限ではない**。保護されたページが多いときは一時超過する。
- `Adaptive` OFF時も **2枚ハード上限を復活させない**。OFFは自動休眠をやめるモードであり、OSの致命的メモリ圧や生成エラーは別途取り扱う。
- 利用可能なRAMを確認し、端末初回は `ActivityManager.MemoryInfo` 等から推奨プリセットを示す。ただし RAM 容量だけで安全性を判断しない。
- 低メモリシグナルでは**安全性を確認済みの非選択タブから**LRUで解放する。未知/保護中のセッションを黙って破棄しない。
- タブ追加/切替時、既存の保護セッションが多い場合も新規セッションの作成を試みる。上限超過はメトリクスに記録する。
- OSでセッション生成できない、OOMに近い等で開けない場合だけ、元画面・入力を保持し「再試行」「タブを整理」「キャンセル」を表示。**閉じる操作を強制しない**。メモリ使用量と安全性の保証不能を明示する。

### 2.3 タブ選択の安全な状態遷移

`SelectTab` は次の順で処理する。

1. 選択タブが既にライブなら、そのままアタッチして ACTIVE にする。
2. ライブでない場合、**安全に休眠できる**非選択タブを探す。適切なLRU候補があればDetach/inactive/close後に新規セッションを作る。
3. 候補がなくライブ目標数に達していても、セッションの一時超過を許し選択タブを表示する。従来の `pendingId` による3枚目ブロックは使わない。
4. 非選択ライブセッションは原則 `setActive(false)` / `setFocused(false)`。ただし音声/通話の挙動は実機検証し、勝手に終了しない。
5. 自動休眠前には現行の `Protection` と `safelySaved` を尊重する。`UNKNOWN` は証拠がない限り消さない。`flushSessionState()` のコールバックを「最新データ完全保存の証明」にしない。
6. セッションの世代（`identity`）とURL/navigation epoch、修正revisionの突き合わせを維持し、遅延callbackで別タブを誤操作しない。
7. 選択タブの切替失敗時はそれまでのタブを見られる状態に戻し、タブ一覧上の論理データを消さない。

**禁止事項:** `require(maxLiveTabs in 1..2)` を単に `1..32` に変えるだけの「修正」、保護セッションを強制 close、通常ページを無断で閲覧専用モードへ変換、コールドタブの全件先読み。状態機械・整合性テストごと変更する。

### 2.4 保存と休眠

- 通常タブ: ID/URL/タイトル/並び順/選択/固定等の最小メタデータを保存。現行通りプライベート履歴・フォーム本文・Cookie・認証文字列をメタデータに保存しない。
- `SharedPreferences` を維持する場合は変更時の保存をdebounce/まとめ書き（アプリ終了など重要時のflushも検討）し、UIスレッドの書き込みや毎コールバック全件シリアライズを避ける。既存 `TabRepository` の I/O queue・プロセス停止時の保証範囲に注意。
- 復元時に100タブを全て同時openしない。**選択中の1枚だけ優先ロード**、残りは論理タブとして表示。
- 休眠復帰のURL再読み込みは履歴/フォーム/JS状態の完全復元ではない。必要に応じてタブカードに「再読み込みされる」と明示する。
- 保存仕様のschema versionを更新し、0.2.xで保存したタブを消さず移行。読めない未知フィールドは安全に扱う。
- 最後のタブを閉じたときの空タブ生成、非公開タブの分離、全削除の確認、Undo（通常タブのみの短期メモリ内情報）をテストする。

### 2.5 ユーザー操作

- アドレスバー左右スワイプ: 前/次のタブ切替。設定でOFF可。横スクロールをWebコンテンツから奪わないよう、ジェスチャーの開始位置は**アドレスバー領域だけ**に限定。
- 下部タブカウンター: タブ一覧を開く。
- `＋`: 新しい通常タブを即作成。長押しまたはタブ一覧からプライベート作成。
- タブカードの `×` / 横スワイプで閉じる。通常タブは Snackbar 風の Undo。保護中の内容が閉じられる際は確認が必要。Undoが **閉じたGeckoSessionの完全復活ではない**ことも適切に扱う。
- ピン留め、全て閉じる、他のタブを閉じる、重複タブ整理は誤操作防止の確認付き。

## 3. UI全体の改修（P0〜P1）

### 3.1 デザイン原則

- **軽量 / 見つけやすい / 片手で操作しやすい**。Androidの標準View + Kotlinベースは維持し、全面Compose移行など大きい依存導入は原則しない。
- UIのコントラストと動作状態が明確。ライト/ダーク/システム追従を全画面へ適用。
- タッチ領域48dp以上、TalkBackの適切なcontentDescription、フォント拡大、Androidジェスチャーナビゲーション領域/IME/画面回転に対応。
- OSダイアログを繰り返すより一画面で設定が完結する構成へ。
- ページの読み込み中・ネットワークエラー・ブロックのON/OFF・プライベート・休眠状態を過剰な専門用語なしに伝える。

### 3.2 通常閲覧画面

推奨構成：

```text
┌────────────────────────────────┐
│ [🛡]  検索またはURL          [↻] │ ← アドレスバー（上下位置の変更可）
├────────────────────────────────┤
│                                │
│         GeckoView              │
│        ウェブページ            │
│                                │
├────────────────────────────────┤
│ [戻る] [進む] [ホーム] [12] [+] [⋮] │ ← 下部ツールバー
└────────────────────────────────┘
```

- `🛡` はシールドの状態をON/OFF/エラーで表示。HTTPSの安全性を虚偽に表示しない。
- URL入力時は全文を編集可能。非編集中はドメインを強調し、URLが長くても本物の接続先を誤認させない。
- 更新と停止は読み込み状態で切替。ホームは `about:blank` もしくは軽量なホームページを選択。
- 長押しメニュー: URLコピー/共有/このサイトの設定等。クリップボードの勝手な読み取りはしない。
- 画面の上下切替時もアドレスバー/コンテンツ領域のレイアウトが飛ばないこと。
- 既存 `BrowserChromeView.title` / `privacy` の表示がレイアウト上で機能しているか確認し、必要なら統合する。

### 3.3 タブ一覧画面（重要）

- 既定は2列のグリッド。表示切替でコンパクトな縦一覧。**タブが50枚でもスクロール軽快**に動く `RecyclerView` 等の再利用型リストを採用（依存コストを計測・判断）。`LinearLayout` に毎回全タブを積む実装を卒業する。
- 上部: `タブ N枚`、タブ検索、グリッド/一覧切替、`＋`、通常/プライベート切替。
- 各カード: タイトル、ドメイン、現在選択状態、休眠状態、閉じる `×`、必要に応じ小さなサムネイル。
- スクリーンショット/サムネイルは**機密情報やプライベート内容を永続保存しない**。まずはfavicon/プレースホルダーで実装し、サムネイルは後続P2の任意機能にする。
- カードタップで該当タブ選択、開いているページを強制閉鎖させる画面を出さない。
- `×` で閉じた場合のリストが消える/再生成でスクロール位置を失うバグを防止。閉じる確認をキャンセルした場合もタブ一覧は残す。
- 状態は「使用中」「休眠中」「再読み込み待ち」程度のユーザー向け表現へ。タブの休眠は独立した専用メニューに追いやらず、必要に応じてカードの小さな状態表示に留める。
- タブ検索はタイトル/ドメインをローカル検索。ページ内容やプライベートURLを外部サービスに送らない。

### 3.4 設定ページ

`MainActivity.settings()` にある `AlertDialog.setItems()` の長いメニューはカテゴリー別の設定画面へ移す。デフォルト値と説明を常に表示する。

```text
設定
├─ 一般
│  ├─ 検索エンジン
│  ├─ ホームページ
│  └─ 新しいタブの動作
├─ 外観
│  ├─ システム / ライト / ダーク
│  ├─ アドレスバー位置
│  └─ タブ一覧: グリッド / リスト
├─ タブとメモリ
│  ├─ 自動 / 省メモリ / バランス / 高速 / カスタム
│  ├─ ライブタブ目標数
│  ├─ 非表示タブ休眠までの時間
│  └─ 保護中のタブを勝手に閉じない（原則常時有効）
├─ プライバシーと広告ブロック
│  ├─ 追跡防止
│  ├─ 広告ブロックエンジン
│  ├─ 広告要素を隠す
│  ├─ 広告跡の余白を詰める
│  ├─ サイト別例外 / 自作ルール
│  ├─ フィルターデータ更新
│  └─ AdGuard（実験機能）
├─ ダウンロードと権限
└─ 詳細情報 / 診断 / ライセンス
```

- サイト別広告ブロックは「このサイトで有効/無効」が最前面。
- `Lite` `ETP` `AdGuard` の排他選択を平易に表現。ETPと独自Liteを同じものと誤認させない。
- 現状のAdGuard XPI実験機能は正式対応とは書かない。設定ページでインストール状態・実験的制限を説明。
- ブラウザ再起動が必要な設定のみ、明確に案内する。
- 画面遷移・OS戻る・Predictive Back・回転・プロセス再生成を検証。

## 4. 広告跡をきれいに消す Cosmetic Filtering v2（P0〜P1）

### 4.1 何が不足しているのか

現行のLiteは、`||domain^` のネットワーク遮断が中心。`domain##selector` のCSSも提供するが、許可selectorは少数（`.ad-slot`、`.ad-banner`、`.advertisement`、`.velocity-ad`、`[data-velocity-ad]`、`[id^="bnc_ad_"]`）。`cosmetic.js` は `<style>` を挿すのみ。

結果として、広告をブロックしても以下が起きる。

- 広告iframeや画像だけ消え、親の `height: 250px` の空枠が残る。
- ページが独自の広告枠クラスを使っているためCSSに引っ掛からない。
- SPAや遅延挿入の広告枠に対する空ラッパー折りたたみがない。
- Peter Loweのネットワーク遮断リスト自体には多数のページ要素セレクタが含まれていない。

**実装目標は「要素を隠す」だけでなく「広告専用と確実に判断できる空枠を詰めて、本文が自然に上へ来ること」。** ただし全サイトで完全な広告排除・AdGuardとの完全互換性は約束しない。

### 4.2 三層の仕組み

**レイヤーA: ネットワーク遮断（現行を維持）**

- HTTP(S)サブリソースの対象ドメイン遮断は維持する。main_frame、認証、決済、アップロード等を無断で壊さない。
- 既存のETP、Lite、AdGuard排他管理を維持。広告CSS機能追加で二重エンジンを起動しない。
- ネットワークルールの評価はsuffix索引などの効率的手法を維持。

**レイヤーB: 要素非表示（CSS `display:none`）**

- 安全なビルトインの広告専用selector一覧を拡充する。大量の汎用 `.banner` や `header` を機械的に消さない。
- `domain##selector` の**ドメイン指定ルール**を限定的に受け入れる。単純な `#id` / `.class` / `[data-ad-*]` / 安全な属性一致・簡単な子/子孫セレクタ等を静的に解析して正規化。ページ上で実行可能なJS/CSS本文は拒否。
- すべての新規構文を無条件に受け入れない。`scriptlet`、`eval`、任意CSS、複雑/高コストな拡張擬似クラス、未知フィルター記法は安全にスキップし件数を表示。
- `domain#@#selector` の**例外**を導入し、特定ドメインでは対応する非表示ルールを無効化できるようにする。`##` と `#@#` は別体系として処理し、ネットワーク許可規則 `@@||...^` と混同しない。
- 対象が `form` / `input` / `textarea` / `select` / `button`、本文主領域、ナビゲーション、認証ダイアログを含むときは自動非表示を回避またはサイトルール単位で停止できるようにする。単にCSSの `:not()` だけを信頼せず、レイアウト折りたたみ側でも保護する。
- CSSは `document_start` で早期適用し、ページスタイル変化や後から出る広告にも適用する。サイト別例外・モード変更ではstyleの差し替え/削除が正しく行われること。

**レイヤーC: 余白/広告専用コンテナの安全な折りたたみ**

- CSSで非表示になった広告要素や空iframeに対応する親要素について、**広告専用と確認できる**場合だけ空白を除去する。
- 既知の広告ラッパーselector、明確な `data-ad-*`、広告専用ID/class、専用の placeholder と判定した場合に限る。ネットワーク遮断だけを根拠に一般の親コンテナを削除しない。
- 親をたどるのは最大2〜3階層、`document.body` / `html` / `main` / `article` / `header` / `nav` / `footer` 等を決して折りたたまない。
- 子に表示中の本文、リンク、フォーム、操作部品、動画、プレイヤー、Canvas、入力中のUI、live region等がある場合は親を折りたたまない。内部テキストの収集/送信は禁止。
- 折りたたみは内部管理のCSS class/data属性と限定スタイルで行い、`display:none` でレイアウト占有自体を除去する。`visibility:hidden` のみでは余白は詰まらない。必要なら安全が確認できたラッパーに限定してmargin/padding/min-heightもゼロ化する。ただし無条件の全親 `height:0` は禁止。
- 元のDOMノードを `remove()` で物理削除することをデフォルトにしない。**可逆な非表示/折りたたみ**を基本に、例外設定・無効化で戻せる設計とする。
- 最初はCSSだけで実現できるケースを優先。親ラッパー判定が必要なケースのみ、限定的な `MutationObserver` とバッチ処理を使う。例: 100〜200msで更新集約、最大100候補/バッチ、処理時間を計測し高負荷時は自動停止。数値は実測で確定する。
- 監視対象はできるだけ広告専用領域に絞り、**全DOMを常時スキャンし続けない**。非表示・バックグラウンドタブ、document unloadでobserverを停止。SPA遷移時に必要な再判定を行う。
- 表示遅延、layout shift、スクロールジャンプ、本文欠落を計測。サイト単位で「余白の折りたたみOFF」を設定可能。

### 4.3 ユーザーがページ要素を選んで隠す（P1）

- シールド → `ページ内の要素を選択して非表示` を追加。AdGuard系の要素選択機能に近いUXだが独自の実装とする。
- ページ上のタップで対象の範囲をプレビュー。前後の選択範囲の拡大/縮小、**適用/キャンセル/元に戻す**を設ける。
- **ドメイン限定の最小selectorを提案**する。一般的な `div` だけ・`body`・`main` といった広範囲の消去規則は保存禁止。
- 画面全体、フォーム、パスワード入力、決済画面、ダイアログ、ブラウザ自身のUIは選択削除不可または強い警告。
- 保存規則はサイト単位。誤検知はシールドの `サイト別ルール` から一覧/削除でき、全ルール一時停止も可能。
- 選択中のDOM/テキスト/フォーム内容、URLクエリ、スクリーンショットをnative messagingやサーバーに送らない。
- タッチ操作のイベントを恒久的に乗っ取らない。選択モード終了後はページの通常操作が復帰する。

### 4.4 デフォルトルールと外部フィルター

- **AdGuard公式と同じ効果を目指すなら、遮断ドメインだけでなくcosmetic rulesのカバレッジが必要**。現行のPeter Loweリストだけに依存しない。
- v0.3最初の実装は、MITの独自コード + 小規模の検証済み内蔵広告ラッパー規則 + ドメイン指定カスタムルール + 要素選択器で始める。
- 将来的に汎用的なフィルターリスト（EasyList等）の一部を採用する場合は、**原リストの利用許諾/帰属/更新条件/形式互換/サイズ/CPU/誤検知**を調査してから個別に導入する。現行 `MAX_BYTES=2MiB` / `MAX_RULES=20000` を根拠なく撤廃しない。ユーザー同意や適用件数、スキップ件数の表示を維持。
- 未対応の構文を黙って部分適用して正常に処理したと表示しない。AdGuard ExtendedCSS、scriptlet、HTML filtering の完全再現はスコープ外。
- リモートCSS/JSのダウンロード実行、eval、任意コードの注入、第三者のフィルター本体の無断再配布は禁止。

### 4.5 広告ブロック画面のUX

シールドを押したときに少なくとも以下を表示。

```text
広告とトラッカーのブロック
サイト: example.com
[このサイトでブロックする      ON]
[広告要素を非表示にする       ON]
[広告の空白を折りたたむ       ON]
[このページの要素を選択して隠す]
[このサイトの非表示ルールを管理]
遮断判定: XX / 非表示: YY / 折りたたみ: ZZ
エンジン: Velocity Block Lite
[詳細設定] [フィルター更新]
```

- 通信の「遮断判定件数」と実際の「非表示要素件数」は混同しない。リクエストを遮断した件数 = 広告の個数ではない。
- AdGuard実験モード選択中はLiteのcosmeticを重ねがけしない。AdGuard自身の要素非表示状況を計測できないなら「未取得」と表示する。
- サイト例外はOrigin単位の既存規約を維持。非公開モードのサイトルールは現行通りメモリ内のみとし、最終非公開タブ終了時に消去。
- フィルター変更で未保存フォームを消さないため、必要に応じて「再読み込みすると反映」を表示。CSSだけの変更は可能な範囲で即時更新、ネットワーク遮断の過去リクエストまで遡及しないことを明示。

### 4.6 Cosmetic Filtering の受け入れテスト

固定fixture（ローカルHTTP/HTTPS）を用意し、以下を**DOM結果だけでなく描画レイアウト**でも検証する。

1. 広告画像/iframeをネットワーク遮断する。
2. 広告専用の空 `div` に `height:250px` が残っていたケースで、折りたたみONなら次の本文が自然に上へ詰まる。
3. 非表示OFF/サイト例外で、そのレイアウトが再表示される（不可逆なDOM削除をしていない）。
4. 似た `banner` という名前の**サイトヘッダー/本文/ログインフォーム**は消えない。
5. 非広告の iframe（動画/決済）や動いているフォームは消さない。
6. 途中で動的挿入される広告ラッパーに適用。連続100回の追加・削除で監視・メモリの増大なし。
7. SPAページ遷移、サイト例外変更、ライト/ダーク、タブの切替・休眠後に広告要素が復活しない（仕様通りの再評価）。
8. `domain##selector` と `domain#@#selector` の優先順位、サブドメイン、private分離、悪意ある巨大selector、無効構文、正規化をテスト。
9. AdGuardモード時にLite由来CSSがページへ残留しない。モード移行失敗で元の設定とページ保護を破壊しない。
10. 観測不能なことは「未測定」と記録し、実機で確認できた範囲だけ成功扱い。

## 5. 実装する新規ファイル/改修目安

確定名ではないが、責務を分ける設計にする。

```text
core/src/main/kotlin/dev/velocity/browser/tabs/
  AdaptiveConfig.kt             # ソフト上限、プリセット、休眠時間
  AdaptiveTabScheduler.kt       # 保護と一時超過を扱う状態機械
  TabRecord.kt                  # 論理状態とライブ状態の表示区別
  TabProfile.kt                 # 新規: 省メモリ/バランス/高速/カスタム
app/src/main/java/dev/velocity/browser/tabs/
  AdaptiveTabCoordinator.kt     # Gecko session管理、メモリ圧、設定反映
  TabRepository.kt              # 保存の拡張、旧版migration
app/src/main/java/dev/velocity/browser/ui/
  BrowserChromeView.kt          # アドレスバー、ツールバー改善
  TabSwitcherView.kt            # タブ一覧UI刷新（または置換）
  SettingsView.kt               # 新規: カテゴリ別設定
  PrivacyBlockView.kt           # 新規: シールド詳細
  CapacityDialog.kt             # 通常経路から廃止。必要なら非破壊エラーへ置換
app/src/main/assets/velocity-block/
  engine.js                     # 厳格なcosmetic rules解析・例外
  background.js                 # サイト別config、世代、状態
  cosmetic.js                   # CSS非表示+安全な空枠折りたたみ
  manifest.json                 # 変更が必要な場合のみ権限見直し
app/src/main/java/dev/velocity/browser/block/
  BlockController.kt            # 設定反映、ルール、サイト別除外、統計
core/src/main/kotlin/dev/velocity/browser/block/
  LiteRules.kt                  # KotlinとJSで規則の受理条件を一致
app/src/androidTest/java/dev/velocity/browser/
  BrowserUiIntegrationTest.kt   # 大量タブ/画面/タブ削除など
  BlockIntegrationTest.kt       # CSS/余白/誤検知を追加
  AdguardIntegrationTest.kt     # XPIとLite排他の回帰
core/src/test/kotlin/dev/velocity/browser/
  AdaptiveTabSchedulerTest.kt   # 3枚目OK、保護維持、LRU、メモリ圧
  ...                           # parser/優先順位などを追加
.github/workflows/android.yml  # APK artifactの維持、テスト失敗を不可視化しない
docs/
  architecture.md
  known-limitations.md
  adblock-decisions.md
  verification.md
  benchmark-methodology.md
```

### 技術上の注意

- 実装前に使っている **GeckoView Stable 157.0.20261005135250** のAPIを実際のsources.jarで検証すること。新しいjavadocにあるAPIを採用版に未確認で使わない。
- Mozillaの `GeckoSession.setActive(false)` は非表示セッションのメモリ節約に使えるが、`restoreState` はJS heap/進行中認証/アップロードの完全復元ではない。
- `GeckoView.releaseSession()` → `setSession()` の順序を守る。1個のViewに複数sessionを同時アタッチしない。
- セッションの lifecycle はメインスレッド所有のイベントキューで扱い、回転・アプリのbackground/foreground・WebExtensionsからの新タブ要求を含めた再入をテストする。
- `onTrimMemory()` 後も保護中のセッションを勝手に消さない。Android自身がプロセスを終了した場合の完全復元は保証しない。

## 6. テスト・評価のゲート

### 6.1 必須テスト

- 2、3、6、12、20、50、100論理タブを作成し、切替・閉じる・再起動・復元・検索・並び順を確認。ライブ目標6枚でも7枚目が**ブロックされず表示**されること。
- 6枚がフォーム/メディア/UNKNOWNで保護中でも7枚目を選べること。前6枚を勝手にcloseしないこと。
- メモリ圧下で**安全に休眠可能な非選択ページ**だけを解放し、フォーム内容を保護すること。
- 不正identity / 古いepoch / 保存タイムアウト / コールバック競合で誤ってセッションcloseしないこと。
- Privateタブの閉鎖・再起動でprivateのURL/閲覧情報/カスタムルールが復元されないこと。
- 背景音声、カメラ、マイク、ログイン、決済、パスキー、ファイル添付、ダウンロードの少なくとも既存テストに退行がないこと。
- 広告のCSS隠蔽、余白の折りたたみ、undo/サイト例外、AdGuardモード排他の描画テスト。
- 48dpタップ領域、TalkBack、文字サイズ200%、夜間テーマ、回転、端末バック、キーボードIME重なりを検証。

### 6.2 性能ゲート

- **Chrome/Firefox/Velocityを同条件**で比較し、勝手に5〜10倍高速などと宣言しない。
- 冷起動/温起動p50,p95、Speedometer等の再現可能なベンチマーク、RAM（アプリ/子プロセスを含むPSS）、CPU、描画jank、ページ切替時間を記録。
- ライブ目標 3 / 6 / 12、論理タブ1 / 5 / 20 / 50、ブロック OFF / LITE / ADGUARD（実験）の条件を比較。
- cosmetic ON/OFFで広告の空白消失だけでなく、追加CPU・DOM処理・長時間タブ放置のメモリ増加を計測。
- 結果未取得は `NOT_MEASURED`。エミュレーターやfixture結果を実機結果と偽らない。

### 6.3 実行コマンド

リポジトリの現在の設定に合わせ、最低限下記を実行する。Android SDK/実機が利用できなければ実行不能を記録し、合格扱いしない。

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=dev.velocity.browser.RequiresExternalFixture
cd tools/bench-runner
bun install --frozen-lockfile
bun test
bun run typecheck
```

### 6.4 GitHub Actions

- 既存の `.github/workflows/android.yml` を維持する。
- **APKを単独でダウンロードできるartifact**を維持し、ソースZIPしか得られない変更をしない。
- 署名方式/テスト用署名の表示を保持し、debug/test-signedを正式な配布署名と偽らない。
- lint・テスト・ビルドが失敗した場合はワークフローを失敗にする。skipした実機テストを成功件数に混ぜない。

## 7. Codex実装順（1つの巨大PRにしない）

### PR 1 — タブ制限撤廃（P0）

1. `AdaptiveConfig` にタブプリセットとソフト目標を導入。
2. schedulerを安全な一時超過対応へ変更。
3. Coordinatorのハード上限assert/2枚制限・CapacityDialog通常経路を除去。
4. 3/6/12/20枚以上の切替、保護、メモリ圧、アプリ復元のテスト。
5. UIは既存でもよいので**最低限普通にタブを開ける状態**にしてから次へ。

### PR 2 — タブUI・設定UI刷新（P0/P1）

1. タブ一覧をグリッド/一覧切替・検索・即時追加・閉じる・Undoへ変更。
2. アドレスバー、下部ボタン、テーマ、画面回転・TalkBackを改善。
3. カテゴリ別設定画面を実装。
4. メタデータmigration、スクロール位置、50〜100枚タブ性能のテスト。

### PR 3 — 広告要素非表示と空白解消（P0/P1）

1. 既存のLite parserに安全なドメイン限定selectorと例外を追加。Kotlin/JSの解釈を一致させる。
2. CSSで広告専用要素を隠す。空の専用ラッパーを折りたたむ。
3. 境界を絞ったMutationObserver/バッチ更新を追加。誤検知防止・性能ゲートを設ける。
4. シールド画面でネットワーク遮断/要素非表示/空白折りたたみの切替・サイト別例外・統計を表示。
5. 広告領域の高さ、正常な本文・入力保護、private/AdGuard排他の実機テスト。

### PR 4 — 要素選択器・最終検証（P1/P2）

1. ページ要素選択→プレビュー→ドメイン限定ルール保存/Undo。
2. ルール管理とサイトごとの誤検知レポート。
3. サムネイル等のP2 UXを性能計測後に必要な範囲だけ導入。
4. 既知制限と測定レポート、README、Actions artifact更新。

## 8. 明確な完了条件（Definition of Done）

- [ ] タブの3枚目・7枚目を開くために「別タブを閉じてください」と求められない。
- [ ] ライブ目標6枚でも保護中の既存タブを保持し、可能な限り新規タブの表示が続けられる。
- [ ] フォーム/動画/認証/ダウンロード/権限/プライベートの安全性で退行なし。
- [ ] 20〜50タブをUI上でスムーズに検索・切替・閉鎖できる。
- [ ] 通常のタブ作成、閉じる、設定、広告ON/OFFが直感的に1〜2操作で完了。
- [ ] 広告だけを消して250pxの空白が残るテストケースで、広告専用ラッパーを安全に折りたためる。
- [ ] 本文やフォームは誤非表示しない。非表示ルール例外と元に戻す機能が動く。
- [ ] AdGuardモードでLite CSSを重複注入せず、既存機能を壊さない。
- [ ] 既存のライト/ダーク、ファイル転送、passkey経路、プライベート、APK-only Actionsを維持。
- [ ] Kotlin/Bun単体テスト、lint、APKビルド、実行可能なinstrumentationが成功。未実施は未実施と明示。
- [ ] 変更ファイル、設計判断、残課題、性能の実測/未測定を文書へ反映。

## 9. Codexへの作業指示

1. まず `main` の最新版と現行テスト・ビルド設定を読み、上記 `0.2.3` ベースの前提から変わっている箇所を洗い出す。
2. `specfix.md` を満たす実装を **PR単位で段階的** に進め、ユーザー操作・安全性・機能の互換性を優先する。
3. 単なる文書更新やUIモックのみで終わらず、**アプリに動作する変更を入れる**。
4. 安全性を証明できない場合は「何ができないか」を限定して説明し、無断タブcloseやデータ消失につながるショートカットをしない。
5. 新機能が遅い場合はアルゴリズム/候補走査の回数を減らす。未測定の最適化効果を主張しない。
6. 各PRの最後に、実施内容、ファイル一覧、テスト結果、残る既知制限、ユーザー視点でどう変わったかを簡潔に報告する。
7. 本文を消す可能性のある広告ルールやライブタブ強制解放など、安全性の低い実装で要件を達成したことにしない。

## 10. 参考資料

- [Velocity Browser repository](https://github.com/linkalls/velocity-browser)
- [Velocity Browser README](https://github.com/linkalls/velocity-browser/blob/main/README.md)
- [既存: next-stage](https://github.com/linkalls/velocity-browser/blob/main/docs/next-stage.md)
- [既存: adblock-decisions](https://github.com/linkalls/velocity-browser/blob/main/docs/adblock-decisions.md)
- [Mozilla GeckoSession API](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.html) — `setActive`, `restoreState` の制約を採用バージョンで要照合
- [AdGuard Knowledge Base: Create your own filters](https://adguard.com/kb/general/ad-filtering/create-own-filters/) — `##`, `#@#`, cosmetic CSS。構文参考資料であり完全互換要求ではない

---

**要約:** 「ライブタブ2枚固定」を安全な**ソフト目標6枚**に置き換え、普段使いのブラウザUIへ改修し、広告通信をブロックするだけでなく**広告専用の空コンテナも安全に消して本文を詰める**。既存のデータ保護・GeckoViewの制限・実測の誠実さは維持する。
