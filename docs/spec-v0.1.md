# Velocity Browser — GeckoView Ultra-Light Adaptive Browser

> **開発仕様書 / Codex Cloud実装指示書**  
> Version: 0.1.0-draft  
> Date: 2026-10-11  
> Language: 日本語（UI・README・主要コメント）、ソースコード: Kotlin、補助ツール: Bun + TypeScript  
> Priority: **安全性 ＞ データ保全 ＞ 実測可能性 ＞ 体感性能 ＞ APKサイズ ＞ 機能数**

## 0. Codexに対する最重要指示

このリポジトリで、**GeckoView採用のAndroidブラウザ「Velocity Browser」**を実装する。これは企画書ではなく、動作するソース・テスト・ビルド手順・計測手順を成果物とする実装タスクである。

1. **描画エンジンはMozilla GeckoViewに固定**。Android `WebView`、Chromium Embedded、Custom Tabsなどをページ表示エンジンの代替として導入してはいけない。比較対象としてChromeに言及するのはよい。
2. 狙いは**Google Goを連想させる、機能を削ぎ落とした非常に軽快な使い心地**。Google Goの過去の配布容量とGeckoView内蔵APKを同列に比較しない。GeckoView自身はアプリに含まれるため「5MB以下」を実現済みの要件・合格条件にしない。
3. **最大2ライブタブ、接続プリロードON、非表示120秒後に破棄候補**をAdaptiveモード初期値として実装する。2は`GeckoSession`の同時オープン数を指し、Geckoの子プロセス数を指さない。
4. データ喪失回避を速度より優先。保存が完了したと確証がない場合、入力、メディア、録画、通話、ダウンロード、ダイアログ、権限許可、支払い等を進行中のタブは**自動破棄しない**。
5. 使っているサイトやユーザー入力のURLを勝手に外部テレメトリーへ送信しない。自動同期、独自プロキシ、外部AI解析、広告SDKは導入しない。
6. API名、Gradleバージョン、設定の可否を推測で埋めず、採用した**安定版**に対応する公式APIで確認し、依存バージョンをピン留めする。Nightlyは原則禁止。
7. **5～10倍高速化／RAM 90%削減は挑戦的な研究目標であり、達成を保証しない**。Chromeと公平な条件で測定し、未測定の改善を成果として書かない。
8. Codex Cloudの環境にAndroid SDK・実機がなければ、実装可能部分のコード、JVMユニットテスト、CI、ローカル検証手順まで完成させ、未検証項目を明確に報告する。ビルド・テストに成功したと偽ってはいけない。
9. 質問で止まらず、本仕様の「v0.1必須範囲」を順次実装する。設計上の判断は`docs/decisions.md`に記録する。
10. 既存リポジトリに`AdaptiveTabScheduler.kt`や`GeckoAdaptiveCoordinator.kt`の試作がある場合は利用・修正してよいが、試作の正しさやAndroid実機ビルド成功を仮定しない。新仕様と矛盾したらこの文書を優先する。

---

## 1. プロダクト定義

### 1.1 コンセプト

**Velocity Browser =「Webエンジンは本格派、ブラウザの外側は極限まで軽量」**。

- 必要な操作（起動、URL入力、ページ表示、戻る、進む、タブ変更）を極小UIで提供。
- あまり使わないタブを賢く休眠／解放し、メモリを増やしにくくする。
- プリロードは低負荷な`GeckoWebExecutor.speculativeConnect(...)`を起点とする。
- GeckoViewのセキュリティ更新を継続できる構成にする。
- ページそのものの互換性を犠牲にした無断のJavaScript無効化・CSS削除・ユーザーエージェント偽装はしない。

### 1.2 プロダクトの非目標

- Geckoエンジンの自作・フォーク。
- Chromium/WebViewへの自動フォールバック。
- Chromeと完全に同一の拡張機能対応。
- すべてのページ・すべての端末でChrome比5～10倍のレンダリング高速化。
- 表示中のWebエンジンRAM使用量を無条件に10分の1へ圧縮。
- アカウント作成、クラウド同期、ニュースフィード、AI要約、翻訳エンジンの同梱。
- 独自HTTP/TLS実装や独自DNSへの強制変更。

### 1.3 想定端末

- 開発時の第一検証端末: **Pixel 7 Pro**。
- `minSdk`: **API 26**を提案値とする。GeckoView安定版のサポート条件と整合しなければ、その根拠とともに調整。
- `targetSdk`: ビルド時点で使える安定Android SDKおよび適用される配布要件に合わせて設定し、固定値と根拠をREADMEへ明記。
- アーキテクチャ: `arm64-v8a`を優先。リリースで他ABIを配布する場合はABI分割/AABを検討。x86_64エミュレーター向けの開発ビルドは用意する。
- 画面回転、Androidのプロセス終了、復帰、ダークモード、文字サイズ変更に対応。

---

## 2. v0.1必須機能（Definition of Doneに直結）

### 2.1 基本ブラウザ

- ホーム画面は検索／URL入力フィールドとタブ操作だけの簡潔なUI。
- URL直接入力、HTTP/HTTPS遷移、検索キーワードの検索エンジン送信（初期候補は選択可能、検索クエリ送信前にURL判定）。
- 戻る、進む、更新、読み込み中断、タイトルと現在のURL表示。
- 新規タブ、タブ一覧、タブ切り替え、タブ閉じる、タブ選択時の復元。
- タブ一覧は軽量な文字カードを優先。サムネイル大量保存は禁止。
- アプリを強制終了して再起動しても、**通常タブのURLとタブ一覧**を復元できる。
- プライベートモードのタブ・履歴・フォーム状態を永続化しない。
- 外部からの`https://` URLを開く基本的なIntentに対応。Intent・deep link入力は検証する。
- `http://`から安全な`https://`へ変更可能な場合の挙動はGecko標準機能に委ね、セキュリティ警告を隠さない。

### 2.2 GeckoView統合

- Androidプロセスにつき**共有`GeckoRuntime`を最大1個**生成する。
- `GeckoView`は表示面として原則1個だけ。バックグラウンドタブの`GeckoSession`とは分離。
- `GeckoView.setSession(...)`の前には必要に応じて既存表示セッションを`releaseSession()`する。
- `GeckoView`の`SurfaceView`バックエンドを初期設定とする。UI遷移のために不必要な`TextureView`へ切り替えない。
- `GeckoSession`の各delegate（進捗、ナビゲーション、コンテンツ、メディア、権限、プロンプト）を必要範囲で統合。
- Androidの`Activity`/アプリライフサイクルに合わせ、不可視セッションを適切にinactive扱いする。
- Geckoがサポートする機能をアプリ独自の憶測で上書きしない。

### 2.3 UI実装技術

- **Kotlin + Android標準View**を採用。v0.1ではJetpack Composeや複雑なデザインシステムを導入しない。
- レイアウトはXML、またはシンプルなViewのプログラム構築。エッジケース対応を含め、シンプルなものを選ぶ。
- UI文字列はAndroid resourcesに集約。日本語を基本とし、重要箇所は英語リソースも検討。
- `GeckoRuntime`初期化やDB読込などを初回フレームのクリティカルパスに不要に載せない。ただし**空画面を速く描画しただけで高速ブラウザと主張しない**。UI応答可能時間とページ表示までの時間を別々に計測する。

---

## 3. Adaptive Mode仕様

### 3.1 初期設定

| 項目 | 値 | 備考 |
|---|---:|---|
| `maxLiveTabs` | `2` | 同時オープンの`GeckoSession`数 |
| `discardAfterMs` | `120_000` | 最後に非表示になってからの経過時間 |
| `preconnectEnabled` | `true` | 接続ウォームアップのみ |
| `preconnectMaxInflight` | `1` | 同時先読み上限 |
| `preconnectCooldownMs` | `30_000` | 同一オリジンでの連続先読み抑制 |
| `backgroundScanIntervalMs` | `15_000` | アプリ前景での周期。バックグラウンドでは常駐タイマーを回さない |
| `aggressiveMemoryReclaim` | `true` | メモリ逼迫時に保護不要のセッションを早期解放 |
| `restoreFallback` | `URL_RELOAD` | セッション状態を復元できない場合 |

テスト可能性のため、上記値を`AdaptiveConfig`として中央集約し、`Clock`注入可能にする。経過時間は壁時計ではなく`SystemClock.elapsedRealtime()`で測定する。

### 3.2 タブ状態

- `COLD`: タブのメタデータは保持。オープン済み`GeckoSession`なし。
- `RESTORING`: 新セッションの生成・復元中。
- `ACTIVE`: 現在選択され可視のセッション。
- `WARM`: 開いているが非表示のセッション。`setActive(false)`を適用。
- `SAVING`: 最新の復元状態が保存できたか確認中。
- `PROTECTED`: ACTIVE/WARMなどと直交する保護フラグ群。独立した表示状態にはしない。
- `FAILED`: セッション復元などの失敗を記録し、URL再読込を選べる状態。

状態遷移は**単一スレッドで直列化**。UI層が生の`GeckoSession`を直接操作しない。

### 3.3 2ライブ制限の定義

- 通常のタブ切り替え後、`ACTIVE`が1、`WARM`は最大1。残りはCOLD。
- `ACTIVE`タブは自動破棄しない。
- 新規表示に枠が必要なら、**非表示かつ未保護、かつ安全な保存状態を確保できた**LRU候補を解放する。
- 安全な候補がなく2ライブ枠が埋まっている場合、**新タブの表示を保留し、理由と操作選択肢をUIに表示**する（勝手にデータ破棄しない）。
- 表示保留中でもタブ項目は生成できる。既存の表示中ページを失わない。
- ユーザーが明示的に危険なタブを閉じようとした場合は警告／確認を出す。確認なしの強制破棄は禁止。
- 不意の`GeckoSession`追加作成（ポップアップ、target=_blank等）による上限逸脱を防ぐ。新窓要求はタブ管理層を経由させる。
- `liveCount <= 2`をデバッグビルド・ユニットテストで常時assertする。

### 3.4 120秒のタイマー

1. タブが非表示へ移行した時点の`elapsedRealtime`を記録。
2. 非表示直後に`setFocused(false)` / `setActive(false)`で負荷を下げる。
3. 15秒間隔の前景チェックまたはメモリ通知時に判定。
4. 120秒以上非表示かつ未保護で、最新の復元状態を安全に確保できたタブだけ`GeckoSession.close()`。
5. 非表示から120秒未満でも、メモリ逼迫時は保存済み・未保護タブの早期解放を許可。
6. バックグラウンドで常時ポーリングしない。復帰時に経過時間を再評価する。
7. 120秒経過は**破棄の検討開始**であり、必ず120秒で閉じるという保証ではない。

### 3.5 破棄禁止条件

次のいずれかが真、または安全性を**判定できない**場合は自動破棄しない。

- メディア再生中（音声/動画、バックグラウンド再生含む）。
- マイク/カメラ録画、WebRTC通話、画面共有などのアクティブなセッション。
- 進行中のダウンロード、アップロード、決済、WebAuthn/Passkey操作。
- 入力フォームが存在し、最新入力状態の安全な復元が確証できない。
- 権限ダイアログ、JavaScriptプロンプト、ファイルピッカー表示中。
- 状態保存の完了が確認できない、またはタイムアウトした。
- `userPinned`など明示的に保持設定されたタブ。

`GeckoSession.containsFormData()`を保守的な判定の材料として使ってよいが、それだけで「フォームは安全に完全復元できる」とは判定しない。アプリ側がアップロードや決済の進行を確実に追えない場合は、初期版では自動破棄対象から外す。

### 3.6 Snapshot / 復元

- `GeckoSession.setActive(false)`は内部でセッション状態をフラッシュし得るが、明示保存には`flushSessionState()`も利用できる。
- **`flushSessionState()`は非同期であり戻り値は`void`**。呼び出し直後を保存完了扱いしない。
- 状態は`ProgressDelegate.onSessionStateChange(...)`から受け取る。保存の新旧を誤判定しないよう、タブのnavigation epoch、session instance ID、保存要求の世代、入力リスクを管理。
- **保存通知には要求IDが含まれない**。イベント到着だけで特定保存要求の完了を厳密に証明できない場合、破棄を見送り、安全側に倒す。必要なら追加のガード期間やセッションスナップショット比較を設計し、その限界を文書化。
- `GeckoSession.SessionState`は`toString()`/`fromString()`を備えるが、保存した文字列の互換性・機密情報・サイズを検証する。初期版の永続化はURL/タブ一覧が必須、完全SessionState永続化は**安全性と互換性を確認した場合のみ**有効化。
- メモリ内に有効な状態があれば`restoreState()`を利用。失敗・互換性問題があればURLを通常ロード。
- フォーム・JS heap・音声・ポップアップなどの**完全復元を保証しない**。
- 古いセッションのコールバックが再生成済みの新セッションや別のタブへ影響しないよう、session identityを検証。
- 画面回転やActivity再生成時は、可能な限り共有Runtime/セッションを引き継ぎ、二重初期化しない。

### 3.7 プリロードONの定義

- v0.1は**`GeckoWebExecutor.speculativeConnect(originUrl)`のみ**を行う。ページHTMLの先読み、隠し3タブ目の作成、ユーザー未要求のログインフォーム送信は禁止。
- プリロード候補: 現在閲覧中HTTPSページと**同一オリジン**に属する、ユーザーが次に開く可能性の高いリンク。v0.1では保守的な候補ルールでよい。
- プライベートモード、従量制ネットワーク、データセーバー、節電状態、アプリ非前景、低メモリ時はオフ。
- 金融・決済・認証画面、個人情報を含む可能性のあるパス、ユーザー未許可サイトへの予測接続は禁止。**不明ならプリロードしない**。
- 呼び出し対象はHTTPSのscheme/host/portのみのオリジンURLとし、パス・クエリ・フラグメントを渡さない。
- 同一オリジンにつきクールダウンを設定し、同時要求最大1件。
- `preconnectEnabled=true`は設定の初期値であり、ベンチマークで逆効果なら実験フラグでOFF可能にする。
- v0.1で「先読みが効いて5～10倍」は保証しない。比較はON/OFF両方を実施する。

---

## 4. 技術スタック

- Kotlin、Gradle Kotlin DSL、Android Gradle Plugin（安定版・バージョン固定）。
- GeckoView **Stable**、Mozilla Maven `https://maven.mozilla.org/maven2/`。
- Kotlin/JVM toolchain + Java 17対応（GeckoView公式Quick Startに準拠）。
- Android標準Viewと必要最小限のAndroidX。Compose、巨大なDI、画像読み込みSDK、Firebase等はv0.1に追加しない。
- 非同期処理はAndroid lifecycleに整合する軽量な仕組み。Coroutines採用時も依存を最小化。
- UIから独立したPure Kotlin schedulerへテストを集中させる。
- 保存: Android標準の軽量永続化（SharedPreferences等）または必要な場合のみDataStore/SQLite。大量のDBフレームワークを先回りして追加しない。
- 計測集計: **Bun + TypeScript**（Node.js専用設計にしない）。Androidのビルド自体はGradle。
- Android Instrumentation tests + JVM Unit tests。実機測定はMacrobenchmark、Perfetto、ADBを利用する。

**依存管理:** 安定版番号を`gradle/libs.versions.toml`など一箇所で固定する。動的バージョン`+`や`latest.release`は禁止。READMEに採用日・参照ページを記録。Gradle Wrapperをコミットする。SDK環境がない場合、検証不能なバージョンをでっち上げず、解決可能な公式リポジトリに基づいて選定する。

---

## 5. 推奨ディレクトリ構成

```text
velocity-browser/
├── spec.md
├── README.md
├── LICENSE
├── .gitignore
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/dev/velocity/browser/
│       │   │   ├── VelocityApp.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── engine/GeckoRuntimeHolder.kt
│       │   │   ├── engine/GeckoSessionAdapter.kt
│       │   │   ├── tabs/AdaptiveConfig.kt
│       │   │   ├── tabs/AdaptiveTabScheduler.kt
│       │   │   ├── tabs/AdaptiveTabCoordinator.kt
│       │   │   ├── tabs/TabRecord.kt
│       │   │   ├── tabs/TabRepository.kt
│       │   │   ├── security/TabProtection.kt
│       │   │   ├── security/PermissionController.kt
│       │   │   ├── preload/PreconnectPolicy.kt
│       │   │   ├── ui/BrowserChromeView.kt
│       │   │   ├── ui/TabSwitcherView.kt
│       │   │   └── util/Clock.kt
│       │   └── res/
│       ├── test/java/dev/velocity/browser/
│       └── androidTest/java/dev/velocity/browser/
├── benchmark/                         # Android Macrobenchmark (環境が許す場合)
├── tools/
│   └── bench-runner/
│       ├── package.json
│       ├── bun.lock
│       └── src/
├── docs/
│   ├── architecture.md
│   ├── decisions.md
│   ├── security.md
│   ├── benchmark-methodology.md
│   └── benchmarks/
└── .github/workflows/android.yml
```

構成は合理的な範囲で簡素化してよい。ただし`AdaptiveTabScheduler`はAndroid/GeckoView非依存にして、単体テスト可能にすること。

---

## 6. 実装の責務分離

### 6.1 `AdaptiveTabScheduler`

Pure Kotlinの状態機械。`Clock`、`AdaptiveConfig`とイベントを受け取り、**副作用を記述したCommand**を返す。

主な入力イベント:

- `CreateTab(id, url, private)`
- `SelectTab(id)`
- `HideTab(id)`
- `CloseTab(id)`
- `NavigationStarted(id, url, epoch)`
- `SessionStateChanged(id, sessionIdentity, epoch, state)`
- `MediaStatusChanged(...)`
- `FormRiskChanged(...)`
- `DownloadStatusChanged(...)`
- `PromptStatusChanged(...)`
- `MemoryPressure(level)`
- `AppForegroundChanged(boolean)`
- `Tick(elapsedRealtimeMs)`

主な出力Command:

- `CreateSession`
- `AttachSessionToView`
- `DetachSessionFromView`
- `SetSessionActive`
- `RequestSessionStateFlush`
- `PersistTabMetadata`
- `CloseSession`
- `Preconnect`
- `ShowEvictionBlockedNotice`
- `FallbackReload`

純Kotlin層には`Activity`、`View`、`GeckoSession`への直接参照を持たせない。破棄・復元・選択イベントは重複実行しても破綻しない冪等性を意識する。

### 6.2 `GeckoSessionAdapter`

公式GeckoView APIの呼び出しとcallbackを仲介。登録したdelegateのライフサイクル管理、例外処理、セッションidentity管理を担当。URLの変化は単一ソースに集約。

### 6.3 `AdaptiveTabCoordinator`

UI・Scheduler・Adapter・Repositoryを束ねるオーケストレーター。コマンド逐次実行、現在のセッション登録表、保護判定、タイマー停止・再開、メモリ逼迫対応を担当。

### 6.4 `TabRepository`

通常タブの一覧、ID、最新URL、選択タブID、閉じた日時などのメタデータをローカル保存。シークレットタブは永続化しない。機密情報・Cookie・セッション状態のログ出力禁止。

---

## 7. セキュリティ・プライバシー要件

### 必須

- GeckoViewのTLS証明書検証、警告画面、Safe Browsing等を恣意的に迂回しない。
- 標準的なサイト権限の許可/拒否をユーザーに提示し、既定では必要最小限の権限。
- マイク、カメラ、位置情報は、Android側の権限とWebサイト側の許可を両方扱う。
- `javascript:`、`file:`、`content:`、`intent:`、外部アプリ起動などのURL schemeを安全にハンドリング。未知のschemeを任意の外部Intentに無条件転送しない。
- アドレスバーや表示上のサイト識別で、見せかけのHTTPSやホスト名を偽装しない。
- プライベートタブのデータは通常の永続化対象に含めず、ログにURL/検索語/フォーム内容を吐かない。
- ブラウザ内での認証・Passkey・ダウンロード等が未実装の場合は、成功したように見せず明示的なエラー・案内を表示。
- 外部通信先は閲覧サイトとGeckoの必要な標準サービスのみ。勝手な追跡SDKや分析SDKは導入しない。
- セキュリティに影響し得る軽量化（プロセス分離の無効化、サイト隔離解除、証明書検証OFF等）は禁止。
- サイトへJavaScriptを注入してフォーム情報を収集する手法は原則禁止。v0.1ではGeckoView公式APIで保守的な保護判定をする。

### リリース前ゲート

- WebRTC/マイク・カメラ、ファイル添付、ダウンロード、複数ウィンドウ、WebAuthn/Passkey、ログイン/ログアウトを検証。
- 未対応の重要機能をREADMEの`Known limitations`へ列挙。
- 重大な安全性の欠陥があるならベータ配布は不可。速度優位では免除しない。

---

## 8. 超軽量化の実装規則

1. デフォルト画面は1つ。Web描画画面と最小限の操作UIに絞る。
2. ニュースフィード、タブサムネイル常時生成、画面キャプチャ、不要な画像アセットは含めない。
3. 巨大な依存関係を増やさず、各追加依存にサイズ/必要理由を`docs/decisions.md`で記録。
4. R8/リソース縮小をリリースビルドで適用。ただしGeckoViewの必要クラスを誤削除しないこと。
5. ABI最適化、AAB、未使用リソース除去、起動時遅延初期化を検証。
6. Geckoエンジンが占める容量は隠さず、**APK/AAB配布容量・インストール後容量・アプリ追加コード差分**を個別計測する。
7. 起動直後のUI表示時間と、GeckoRuntime ready、最初のWebコンテンツ描画までの時間を区別。
8. RAMはメインアプリプロセスだけでなく、**アプリ所属Gecko子プロセスを含む合計PSS**を測る。
9. むやみに`System.gc()`を呼ばない。プロセス隔離や安全機能を解除してRAMを削らない。
10. v0.1で読書専用のHTML簡易レンダラーを作らない。通常Web互換性を優先する。

### サイズ予算の扱い

- 最初に`minimal-geckoview`相当の比較ビルドを作り、そのReleaseサイズを**基準値**として記録。
- ブラウザ独自UI/ロジックによる**追加サイズは2 MiB以下を初期努力目標**とする。達成できなければ内訳を報告。
- GeckoViewを含むAPK全体の`5 MB`目標は設定しない。実測容量と削減余地を正直に記録する。

---

## 9. パフォーマンス計測仕様

### 9.1 比較対象

- Velocity Browser（Adaptive ON/OFF）
- Firefox for Android Stable
- Google Chrome Stable

ブラウザバージョン、Androidバージョン、端末名、SoC、RAM、リフレッシュレート、ネットワーク、テストページ、テスト時刻を計測結果へ記録。

### 9.2 計測項目

| KPI | 定義 | 初期開発目標 |
|---|---|---|
| `TTFF` | アプリタップからUI最初のフレーム | 前回実装または基準値より改善 |
| `TTUI` | アドレス欄が入力可能になるまで | p50/p95の改善 |
| `TTFirstContent` | URL要求から最初の有用なページ描画 | Chrome対比で検証 |
| `TabSwitchLatency` | タブ選択から内容表示まで | p95 ≤ 150msを研究目標（Warmのみ） |
| `ColdTabRestore` | COLD選択からWebコンテンツ再表示まで | 目標は初回測定後に設定 |
| `MemoryPSS1/5/20` | 1/5/20タブ時の合計PSS | Chromeとの差を測定 |
| `MemoryAfterIdle120s` | 20タブ作成後、非表示120秒のRAM | 90%削減を研究目標として評価 |
| `Jank` | 連続スクロールの遅いフレーム | Chromeと同条件で比較 |
| `EnergyPerJourney` | 同一の操作シーケンスの電池/エネルギー | 実測し差分を報告 |
| `RestoreSuccess` | タブ復元成功率/破棄による消失件数 | **破棄による未保存ユーザーデータ損失0件** |
| `APK / Install size` | サイズ内訳 | Geckoベースライン比を報告 |

`TTFF`だけの高速化で「ページ表示5倍高速化」と主張しない。WarmタブとColdタブを混合した平均だけを出さない。ランダムな公開サイトの表示性能はネットワーク影響が大きいため、固定テストページを主測定として併用する。

### 9.3 測定方法

- **Android Macrobenchmark**: Cold/Warm/Hot startup、FrameTiming、タブ切替のUIシナリオ。
- **Perfetto**: CPU、メインスレッドブロック、GPU/フレーム、Gecko関連プロセス、メモリの挙動。
- **ADB**: `dumpsys meminfo`、プロセス確認、APKサイズ、機種/ビルド情報。
- **Bun + TypeScript**: 計測JSON/CSVを集計し、p50/p90/p95、標本数、測定設定、失敗回数を`docs/benchmarks/`に出力。
- 比較は同一端末、同一回線、同じページ、近い温度・電池残量、同等のキャッシュ条件。冷間・温間・プリロードON/OFFを分離。
- 1条件につき原則30回測定。Chrome、Firefox、Velocityの順序を固定せず交互にする。外れ値の無断削除を禁止。
- ベンチマーク用実行時はdebuggable=falseに近い構成。リモートデバッグを常時ONにしない。
- **CPU/電力測定可能な実機がない場合は、計測値を捏造せず`NOT_MEASURED`とする。**

### 9.4 目標の表現

- 「速度5～10倍、メモリ90%削減」は**一部シナリオでのストレッチ目標**。未達でも計測・分析できる成果を優先。
- 複数タブの破棄によるRAM低下は「破棄で減ったメモリ」。単一のACTIVEページのGecko描画コストと混同しない。
- 比較結果にはChrome側のメモリ節約挙動・タブ破棄状態も明記する。

---

## 10. 実装マイルストーン

### M0 — Repository & Build

**Done when:** Androidアプリの構成、GeckoView安定版依存、Gradle Wrapper、GitHub Actions CI、READMEがある。環境が許せば`assembleDebug`成功。失敗は環境と原因を記録。

- `spec.md`をリポジトリ直下に含める。
- 最初の画面を起動しGeckoRuntime共有インスタンスを利用できる。
- 依存バージョンを固定。

### M1 — Single Tab Browser

**Done when:** GeckoViewによる任意のHTTP(S)ページ表示、URL欄、戻る、進む、更新、基本的なエラー表示が動作。簡素な日本語UI。

### M2 — Adaptive Scheduler

**Done when:** Pure Kotlin Scheduler実装、2ライブ上限、120秒判定、保護条件、LRU解放、復元、バックグラウンド移行の基本テストがある。

### M3 — Persistence & Safety

**Done when:** 通常タブ一覧とURLのプロセス終了後復元、シークレットタブ非永続化、プロンプト/権限/フォーム保護、エラー復元とデータ損失対策が入る。

### M4 — Preconnect & Telemetry

**Done when:** `GeckoWebExecutor.speculativeConnect()`を用いた低負荷先読み、節電・ネットワーク・プライベート条件ガード、ON/OFF実験フラグ、内部計測イベントが入る。ログはプライバシー保護。

### M5 — Benchmark & Polish

**Done when:** Macrobenchmarkまたは実行可能な代替測定、Bun集計ツール、Chrome/Firefox比較手順、APKサイズレポート、バッテリー/メモリ測定計画、Known limitationsが揃う。

M0からM4を優先実装。M5が実機環境に依存するなら「未測定」とし、確実に実行できるスクリプト・説明を残す。

---

## 11. 必須テスト（具体的な合否）

### Scheduler JVMユニットテスト

1. タブ1枚を作る → `ACTIVE=1`、`liveCount=1`。
2. 2枚目へ切替 → `ACTIVE=1`、`WARM=1`、`liveCount=2`。
3. 未保護タブの保存状態を確認後3枚目へ → LRUがCOLD化され`liveCount=2`。
4. 保存未完了のまま3枚目へ → 無条件の`close`なし、遷移保留。
5. 120秒未満で解放しない（通常時）。120秒以降、保存済みなら候補。
6. 音声/動画/マイク/カメラ/ダウンロード/フォーム/ダイアログ/固定タブは自動破棄禁止。
7. 保護タブ2枚で枠が埋まった場合 → ユーザー操作待ち、未保存データ破棄なし。
8. `onTrimMemory`相当イベント → 保護不要の非表示セッションの早期回収。
9. 古いセッションからの遅延コールバック → 新しいタブ・状態を破壊しない。
10. ナビゲーション世代が変わった直後 → 古いSessionStateを復元に使わない。
11. タイマーの`elapsedRealtime`が単調進行する仮想Clockで決定的に動く。
12. プライベートタブ → 永続化コマンドなし、プリロードなし。
13. 従量制/節電/前景外/危険URL → 先読みなし。
14. 同一オリジンHTTPSで安全条件を満たす → クールダウン内最大1先読み。
15. 50～100タブの反復操作 → タブID重複、リーク、`liveCount>2`、破棄後参照なし。
16. 一部セッション復元失敗 → URLから安全に再読み込み可能。

### Android UI/統合テスト

1. 外部URL Intentから正常起動。
2. 入力フォームを持つタブを往復しても、自動破棄による未保存入力消失が起こらない。
3. タブ20枚の往復・2分待機・復元がクラッシュしない。
4. アプリ再起動で通常タブ一覧のみ復元、プライベートタブは復元されない。
5. 画面回転/端末復帰/低メモリ時に二重Runtime初期化を起こさない。
6. メディア再生中は無断破棄なし。
7. Web権限要求はユーザー確認を伴い、無断許可しない。
8. HTTPS証明書エラー・未知URLスキームを安全に扱う。

### ログと開発品質

- Gradle Kotlin DSLビルドが通ること。
- 少なくともSchedulerの全主要状態遷移をJVMユニットテストで覆う。
- 危険なTODOは`docs/known-limitations.md`に書き、見えないように隠さない。
- URL/認証情報/フォーム内容のログ混入を調べるテストを用意する。

---

## 12. 実機ベンチマークのシナリオ

1. **Startup**: アプリをCold/Warm/Hotで開く。
2. **1-tab**: 固定HTMLページをロードし操作する。
3. **5-tab**: 5タブ順次作成、1分操作、合計PSS取得。
4. **20-tab**: 20タブ順次作成、2分アイドル、合計PSS取得。
5. **Restore**: COLD化タブへ戻り、表示時間・通信量・失われた状態を記録。
6. **Media Protected**: 音声再生タブを維持したまま別タブを開く。
7. **Forms Protected**: 未保存フォーム入力のあるページでタブ上限に到達する。
8. **Preconnect ON/OFF**: 同一オリジンの遷移のみを比較。ページロードまでの時間と余剰接続数を測る。
9. **Scroll**: 一定長の固定記事とJSアプリでスクロールしJankを計測。
10. **Size**: arm64向けAPK/AABサイズとインストール後容量、GeckoView基準版との差分を記録。

Chrome/Firefox/Velocityでできる限り同一操作にし、比較不能な部分はそのまま注記する。

---

## 13. Codex Cloudでの実行手順

1. リポジトリの現在の状態を読む（既存コード・README・Gradle構成・試作の有無）。
2. この`spec.md`に従って、GeckoView stableのバージョン・関連API・Android Build Toolsを確認。
3. `docs/decisions.md`に技術選択・制約・トレードオフを記録。
4. M0 → M1 → M2 → M3 → M4の順にソースを実装。
5. Android SDKがあればGradleビルド・JVMテスト・instrumentation実行可能範囲を検証。
6. Android SDKがない場合もビルド可能なソース構成、JVMテスト、GitHub Actions CI、ローカルビルド手順を完成させる。
7. Bunのベンチ集計CLIを追加し、**テスト用サンプルデータ**と実機測定データを混同しない表記にする。
8. `README.md`に「必要環境」「ビルド」「APK生成」「実機インストール」「テスト」「既知の制限」「ライセンス/依存関係」を記す。
9. 最後にファイル変更一覧、実行したコマンド、成功・失敗・未実行テスト、未解決のセキュリティ課題、次の実装優先順位を報告。

### 想定コマンド（実際のGradleタスク名に合わせ調整）

```bash
# AndroidアプリのデバッグAPK生成
./gradlew :app:assembleDebug

# JVMユニットテスト
./gradlew :app:testDebugUnitTest

# Android実機またはエミュレーター上のinstrumentationテスト
./gradlew :app:connectedDebugAndroidTest

# Android lint
./gradlew :app:lintDebug

# Bunを使う集計ツールの依存インストール・テスト
cd tools/bench-runner
bun install --frozen-lockfile
bun test
```

Android SDKの不在や接続不能により実行できないコマンドがあれば、その理由を書いて次の作業を進めること。

---

## 14. レビュー・受け入れ条件（最終チェックリスト）

- [ ] GeckoViewが唯一のページ描画エンジンになっている。
- [ ] Kotlin/Androidのビルド手順を含み、依存が安定版で固定されている。
- [ ] 基本的なURL入力・ページ表示・戻る・進む・タブ切替がある。
- [ ] `GeckoRuntime`はプロセスで共有し、二重生成しない。
- [ ] `GeckoView`は原則1個でセッションの付け替えを行う。
- [ ] ライブタブ上限2をSchedulerが保持し、テストで検証できる。
- [ ] 非表示120秒は破棄候補時刻として正しく扱う。
- [ ] 保存確認前の自動破棄をしない。
- [ ] メディア・未保存フォーム・ダウンロード等の保護がある。
- [ ] プリロードONは安全な`speculativeConnect`で実装され、同時上限1。
- [ ] 通常タブは再起動後も少なくともURLとタブ一覧を復元する。
- [ ] プライベートタブは永続化・先読みしない。
- [ ] UIは標準View中心の簡素な日本語UI。
- [ ] サイズ、性能、メモリ、クラッシュを計測できる仕組みがある。
- [ ] 5～10倍高速化/90%削減を、未測定のまま達成と書いていない。
- [ ] ユーザーデータを破壊する既知の挙動がない。
- [ ] README、意思決定記録、既知の制約、テスト結果を残している。

---

## 15. 公式資料（実装前に最新情報を確認）

- GeckoView Quick Start: https://mozilla.github.io/geckoview/consumer/docs/geckoview-quick-start
- GeckoSession JavaDoc: https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.html
- GeckoSession.SessionState JavaDoc: https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.SessionState.html
- GeckoView JavaDoc: https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoView.html
- GeckoWebExecutor JavaDoc: https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoWebExecutor.html
- GeckoView Architecture: https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/geckoview-architecture.html
- Android Macrobenchmark: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- Perfetto: https://perfetto.dev/docs/getting-started/start-using-perfetto

### 最終ゴール

**「GeckoViewの互換性と安全性を保ちながら、UIとタブ管理を徹底的に軽量化し、20タブでも快適なAndroidブラウザ」をまず動かす。**

そのうえで実測に基づきChrome比5～10倍の特定操作高速化と、未使用タブ主体での大幅なメモリ削減に挑戦する。最適化は測定→改善→再測定の順で行い、数値を作らない。
