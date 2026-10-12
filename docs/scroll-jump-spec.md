# Velocity Browser 0.5.x — スクロール逆戻り・上下ジャンプの調査および修正仕様

作成日: 2026-10-12  
対象リポジトリ: https://github.com/linkalls/velocity-browser  
調査時点の `main`: `92b8c989eb48655889ce2409319a86b028ca8e25`（0.5.0 / GeckoView Stable 157.0.20261005135250）  
想定担当: Codex Cloud  
優先度: **P0**（ユーザー可視のスクロール不具合）

## 0. 最初に守ること

1. **作業開始時に最新の `main` を取得・確認すること。** 上記SHAは調査時点の基準であり、現在のHEADが進んでいれば差分を調べてから対応する。
2. この文書は「原因が確定したパッチ」ではなく「観測 → 切り分け → 最小修正 → 検証」の実装依頼である。症状が消えたと測定せずに宣言しない。
3. **スクロール操作そのものをJavaScriptやViewのスクロール位置書き換えで補正しないこと。** 指の動きに逆らって画面を固定する「見かけの修正」は不可。
4. GeckoViewを唯一のエンジンとして維持。WebView/Compose/TextureViewへの無断置換、GeckoViewのセキュリティ保護停止、サイトのJavaScript無効化は禁止。
5. 0.5.0で修正した **56pxのfixed-footer空白問題を再発させない**。上部配置・下部配置・自動非表示ON/OFF・IME・選択ドラッグ・TalkBackを必ず回帰試験。
6. タブ／フォーム／認証／メディア／アップロードの保護、通常・プライベート分離、データ移行、タブID/identity/epochは変えない。
7. 広告Liteの180ms静穏期間と4ms/12要素単位の小分け処理は既にmainに存在する。PR #5を無差別に再マージしない。
8. 今回は**スクロール品質だけ**。UI全体の再設計、ブックマーク、Passkey、Discover、他エンジン追加などはスコープ外。

## 1. ユーザー報告と動画

ユーザー報告：**「Webページを上下にスクロールしている途中、一瞬逆方向へ戻る／ガクッと大きく跳ぶ」**。単純なFPS低下だけではなく、連続しているはずのコンテンツ位置が不連続になることが問題。

提供録画: `1000124837.mp4`、縦1080×2340、約8.5秒。表示ページは `news.yahoo.co.jp`。動画がCodexの実行環境からアクセスできない場合でも、以下の記録を根拠に問題設定し、**必ず自前の再現fixtureを作ること**。

- 約4.0〜5.0秒: 長い記事リストを高速移動。
- 約5.0〜5.2秒: 記事リストが大きく移動し、ページ上部のYahoo!ニュースのヘッダーが現れる。
- 下部にVelocityのアドレスバー・タブカウンターが映っている。
- **映像には指・タッチイベント・プログラム起因のスクロールが記録されていない**ため、5秒付近の移動そのものを異常と断定しない。ただしユーザーは「意図しない逆戻り・ジャンプ」を確認している。

判定すべき現象は以下を分けて扱うこと。

A. 同一DOM要素が一瞬逆向きに移動する／本来のスクロール量に対して不自然に跳ぶ。  
B. Web本文は連続移動するが、ツールバー・fixed/footer・viewportだけが一瞬ズレる。  
C. ページJS、アンカー、フォーカス、スクロール復元、広告除去などでWeb側のscrollYが本当に変化する。  
D. フレーム落ちにより連続するスクロールが飛び飛びに表示されるだけで、scrollY自体に逆行はない。

**A〜Dを混同しない。** 動画だけで根本原因を確定したことにしない。

## 2. ソースから判明している要調査ポイント

### 2.1 `GeckoSessionAdapter.kt`: 二重のツールバー表示要求

`app/src/main/java/dev/velocity/browser/engine/GeckoSessionAdapter.kt`

現在、同じツールバーに対して以下の2経路から通知が入る。

- `session.compositorScrollDelegate.onScrollChanged` → 移動方向を累積、24dp以上で `toolbarVisibility(..., Source.COMPOSITOR)`
- `session.contentDelegate.onShowDynamicToolbar/onHideDynamicToolbar` → `toolbarVisibility(..., Source.CONTENT)`

`onScrollChanged` 内の計算は `update.scrollY * update.zoom`。**scrollYはCSS pxで、zoomを掛けてscreen pxにすること自体はMozilla APIに沿っている。** ただし、ズーム変更とページスクロールが混ざると「実際の縦移動」と違うdeltaが生じ得る。さらに `SOURCE_OTHER` から `SOURCE_USER_INTERACTION` に切り替わった直後の差分は連続操作の一部とみなしてよいか検証が必要。

調査・修正候補:

- イベントソース切替・ズーム変更・ナビゲーション・タブ切替・不連続な大移動を跨いで、不要な移動方向推定を引き継がない。
- `SOURCE_OTHER`（script/engineによる移動）をユーザーのフリックとして扱わない。一方、サイトによる意図的な移動は妨げない。
- 同一タブ/同一identity/現在選択中であることを確認してから、ツールバー操作を送る。
- **スクロール方向の推定はツールバーの表示にのみ使用**し、Gecko本文のscrollYを再設定しない。
- 一律の「大きなdelta拒否」は高速フリックを誤判定するため禁止。イベントのsource、ズーム、時間差、ナビゲーション状態を確認して扱う。

### 2.2 `ToolbarMotion.kt`: 非同期通知競合・アニメーションの反転

`core/src/main/kotlin/dev/velocity/browser/ui/ToolbarMotion.kt`

現状、`COMPOSITOR` を優先する期間160ms、方向転換ヒステリシス80ms、表示/非表示のtarget・visible独立管理がある。値は旧PRで導入された**暫定値**。ここを魔法の定数として変更する前に、実際のイベント時刻と順序を採取すること。

調査・修正候補:

- `COMPOSITOR` と `CONTENT` が短時間に交互に要求したとき、targetが予期せず反転しないか。
- 新しい表示要求で `ValueAnimator` がキャンセルされた直後、**現在の可視位置**から自然に再開するか。
- レイアウト変更で target端点にスナップしないか。
- ピン留め（テキスト選択・IME・modal）解除時、古い保留要求を誤適用しないか。
- 同じ要求が連続したとき、アニメーションやClipping設定を無駄にやり直さないか。
- ユーザーが方向転換した正当な動作は反応し、過剰な遅延を増やさない。

### 2.3 `BrowserChromeView.kt`: GeckoViewの表示領域とClipping

`app/src/main/java/dev/velocity/browser/ui/BrowserChromeView.kt`

重点確認箇所:

- `syncDynamic()`
- `onEngineToolbar()`
- `animateTarget()`
- `applyOffset()`
- `addOnLayoutChangeListener`
- `setOnApplyWindowInsetsListener`
- `setAddressBottom()`

0.5.0は `setDynamicToolbarMaxHeight` 時のClippingリセットと、static/dynamic別の計算を修正済み。**これを壊さず**、以下を切り分ける。

- ツールバーの `translationY` / `engineContainer.translationY` / `setVerticalClipping` が変わる時刻と、ページの視覚位置が跳ぶ時刻が一致するか。
- Layout/Insetsコールバックにより再適用が走っていないか。数値が同じ場合は重複setterを避ける。
- `setDynamicToolbarMaxHeight` は実際に最大高さが変わるときだけ呼び、必要なClipping再適用は必ず残す。
- 上配置では親Viewのtranslationがあるため、固定位置要素・タップ座標・Caretが一致するか。下配置でもClippingの符号とviewport基準を検証。
- `SurfaceView` を使用している点を尊重し、無根拠な `geckoView.translationY` への変更やTextureViewへの置換をしない。

### 2.4 `cosmetic.js`: Liteのスクロール後DOM処理

`app/src/main/assets/velocity-block/cosmetic.js`

180msスクロール静穏期間、4ms/12要素の小分け処理が実装済み。ただしシングル `querySelectorAll` は同期処理であり、4msを厳密に保証できない。

- Lite OFF / ETP_ONLY / Lite ON（余白除去OFF）/ Lite ON（余白除去ON）を同じfixtureで比較。
- 広告専用wrapperの折りたたみがページのscroll anchoringや位置調整を引き起こしていないか観測。
- スクロール中の重いDOM走査・Style readを増やさない。
- 本文やフォームの親要素は従来通り保護し、非広告のDOMを削除しない。
- スクロールjumpの原因がLiteと証明できなければ、Liteを無断で弱体化させない。

## 3. 最初に追加する診断: 低負荷リングバッファ

原因が曖昧なまま試行錯誤で定数を変更しないこと。テスト/診断ビルド限定の `ScrollTrace`（仮称）を作り、**500〜1000件程度の固定サイズリングバッファ**へ次を記録する。

- 単調増加時刻（`elapsedRealtimeNanos` / `uptimeMillis` のどちらかに統一）
- 選択中タブの匿名の識別子とidentity・epoch（実URLは記録しない）
- `scrollY`、`zoom`、`source`、計算deltaと累積intent
- `CONTENT` / `COMPOSITOR` / `UI` の要求方向・採否・拒否理由
- `motion.visible`、`motion.target`、状態、アニメーション開始/中断/終了
- toolbarHeight、dynamicMax、verticalClipping、toolbarTranslation、contentTranslation
- Layout/Insets/選択ピン/IME/modal/タブ切替などの状態遷移
- フレーム処理が遅延した区間の時刻（軽量な計測、必要な場合だけ）

**禁止:** 全URL、検索クエリ、HTML、Cookie、フォーム入力、個人データ、スクリーンショットをログに入れること。通常配布版の常時収集・サーバー送信は禁止。イベントごとに文字列連結してlogcatへ大量出力しない。ユーザーが明示操作した場合だけ診断情報をローカルで書き出せる設計とし、非公開タブは原則対象外とする。

予測されるログの読み方:

- Webコンテンツ座標は連続しているがClipping/translationが激変: **表示領域側が第一候補**。
- `SOURCE_OTHER` の飛躍時にページだけ跳ぶ: **サイトscript/scroll anchoring/engine由来を優先調査**。
- 座標の逆行がなく、表示フレームに大きい時間欠損: **描画・コンポジット問題を優先**。
- ツールバーtargetが短時間で交互反転: **通知競合/状態機械を優先**。

## 4. 再現fixtureと実験手順

### fixture

既存 `tools/bench-runner/fixtures/scroll-static.html`、`scroll-dynamic.html`、`app/src/androidTest` 内のreal-Gecko fixtureを再利用し、次を増設する。

1. 長い静的記事リストと固定ヘッダー/フッター。
2. 途中挿入・広告専用wrapper collapse・画像遅延読み込みがある動的記事リスト。
3. 内部scroll container / nested scrolling。
4. フォームfocus、IME、テキスト選択drag、ページ内アンカー、`scrollIntoView` 等の意図的なジャンプ。
5. ズーム変更、タブ切替、戻る、画面回転、ホーム遷移。

fixtureは自前/localhostで構築し、Yahoo!ニュースの実ページに依存したCIテストにしない。実サイトは追加の目視確認対象とする。

### 切り分け順序

1. **ツールバーの自動非表示OFF / Lite OFF** で再現するかを確認。
2. **ツールバーの自動非表示ON / Lite OFF** にして差を確認。
3. 自動非表示ONのまま **ETP_ONLY → Lite ON（collapse OFF）→ Lite ON（collapse ON）** を比較。
4. アドレスバーの上/下配置、60Hz/120Hz、ゆっくりpan/高速fling/慣性スクロール/方向反転で比較。
5. 静的fixtureで異常がなく、動的fixtureのみで起こるなら、DOM変更・scroll anchoring・Gecko compositorの順に切り分け。
6. `CONTENT` 通知と `COMPOSITOR` 通知の交互到着をテストから明示的に再現し、ツールバーが反復反転しないことを検査。

**自動非表示OFFで現象が消えた場合は、優先してツールバーの表示領域処理を調査**。ただし同条件で違うページ負荷になっていないかチェックすること。自動非表示OFFでも残る場合はブラウザ本文、サイトJS、描画フレーム側に調査を広げる。

## 5. テストの追加・更新

- `core/src/test/kotlin/dev/velocity/browser/ui/ToolbarMotionTest.kt`：短時間でCOMPOSITOR/CONTENT交互要求、同方向連打、途中反転、lock/unpin、重複通知。
- `app/src/androidTest/java/dev/velocity/browser/V04UiIntegrationTest.kt` および `CosmeticLayoutIntegrationTest.kt`：ツールバー表示状態の幾何学、コンテンツfixed/footer・フォーム・タップ位置・選択ピン・Layout/Insets・Lite ON/OFF。
- `app/src/androidTest/java/dev/velocity/browser/BrowserUiIntegrationTest.kt`：スクロール後に選択タブ/identity/epochが保持されること。
- `tools/bench-runner/src/cosmetic-scheduling.test.ts`：スクロール中の処理待機、途中で再スクロールしたときの延期、100候補上限とフォーム保護の維持。
- 必要なら `ScrollIntentTracker` 等の小さい純Kotlinロジックを `core` に切り出し、source切替/zoom変更/ナビゲーション/タブ切替をユニットテスト。適当に巨大なUIクラスを増やさない。

単なる単体テストだけで「現象解消」としないこと。画面そのものの飛びは**物理端末の目視/動画とトレース**で最終判断。

## 6. 実機・計測の要件

主対象: Pixel 7 Pro（実機）、60Hz/120Hzを分ける。検証不可なら「NOT_MEASURED」と明記し、端末があるふりをしない。

- 同一端末、同一表示倍率、同一サイト/fixture、同一広告設定、同じバイナリ種別、同程度の温度・電力条件。
- 修正前/修正後を交互に比較し、各条件で**少なくとも20回のpan/fling**、3セット以上を目安に記録。
- 画面録画では固定領域とページ本文を別々に追跡。同じDOM特徴点（画像/見出し）が連続フレームでどちらへ動くかを計測し、意図したタップ/アンカー/JS/方向転換による移動を除外。
- PerfettoのAndroidホストFrameTimeline（p50/p95/p99、>50msなど）に加え、Gecko内容側のスクロール/コンポジタ計測や視覚トレースを使う。
- **Perfetto FrameTimelineだけではSurfaceViewコンテンツ側の全フレームを把握できない**。ホストの数値をGecko本文のFPSや実機120FPSだと誤表現しない。
- 必要に応じてGecko Profilerを使用。ただし実運用でリモートデバッグを常時有効化しない。
- フォーム入力・Passkey・背景メディア・プライベートのデータ喪失/漏洩がないことを回帰確認。

### 受け入れ条件

1. 実機で再現できた症状について、**再現条件・原因を支えるログ・変更前後の同条件比較**を残す。
2. 同一条件の繰り返しスクロールで、ユーザーの指/プログラム操作に由来しない可視の逆戻り・ジャンプが**観測されない**。再現性がない場合は「修正確定」とせず経過観察。
3. ツールバーの表示/非表示は自然に完了。途中キャンセル・連続反転・レイアウト更新でも意図しない端点スナップなし。
4. 上/下・ON/OFF・Lite ON/OFF・固定ヘッダー/フッターでClippingやタップ位置が正常。旧56px空白再発なし。
5. 不必要なCPU/メモリ/フレーム遅延の悪化がないことを測定値で説明。計測できなければ改善率は**未測定**と記す。
6. 調査用ログ機能が配布版のプライバシー/性能に悪影響を及ぼさない。
7. 既存CIテスト（未除外分）が全通過し、警告増加・テストskipを隠蔽しない。

## 7. ビルド・検査コマンド

リポジトリルートから実行:

```bash
./gradlew --max-workers=1 :core:test :app:testDebugUnitTest :app:lintDebug
./gradlew --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew --max-workers=1 :app:assembleRelease :app:bundleRelease :app:assembleBenchmark
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=dev.velocity.browser.RequiresExternalFixture
cd tools/bench-runner
bun install --frozen-lockfile
bun test
bun run typecheck
```

- Android SDK/Java等が使えない環境では、実行できなかった理由・未実施テストを明記する。
- 成功していないテストをPASSに含めない。
- ActionsにAPK-only artifactが存在することを確認。署名鍵が異なる端末インストールでの上書き失敗に注意し、**既存アプリの削除をユーザーに無断で要求しない**。

## 8. 納品物

1. 必要最小限のコード差分（複数の独立原因があれば段階的なコミットにする）。
2. `docs/scroll-jump-root-cause.md`：再現、確定した事実、原因仮説、ログ、修正差分、反例、未解決。
3. `docs/scroll-jump-validation.md`：機種/Hz/条件、スクロール動画・トレース、テスト結果、before/after表、未測定欄。
4. 本件専用のユニットテスト・real-Gecko統合テスト・Bunテスト。
5. 成功したGitHub ActionsのリンクとAPK-only artifact（可能な場合）。
6. PR本文には「実際に再現したこと」「実際に直したこと」「未確認のこと」を**区別して**記載。

## 9. Codexへの実行指示

**今回のゴールは「ベンチのFPSを上げる」ことではない。実際に感じる上下逆戻り／画面ジャンプを無くすこと。** まず既存0.5.0の挙動を保持したまま原因を観測し、`GeckoSessionAdapter`の通知処理と`ToolbarMotion`/`BrowserChromeView`のClipping/translationを中心に、安全な最小修正を行う。症状がWeb側由来なら、ブラウザが誤った対処をしないよう根拠を残す。できる範囲で実装・テスト・CIまで進め、端末のない環境では仮説を確定事項として書かない。

## 参考リンク（一次情報中心）

- 現在の改修記録: https://github.com/linkalls/velocity-browser/blob/main/docs/scroll-jank-investigation.md
- v0.5 検証: https://github.com/linkalls/velocity-browser/blob/main/docs/overhaul/validation.md
- 既存PR #6: https://github.com/linkalls/velocity-browser/pull/6
- 既存PR #5（重複に注意）: https://github.com/linkalls/velocity-browser/pull/5
- Mozilla GeckoView / GeckoSession.ScrollPositionUpdate: https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.ScrollPositionUpdate.html
- Mozilla GeckoView / GeckoView: https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoView.html
- Mozilla GeckoView / ContentDelegate: https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.ContentDelegate.html
- Perfetto FrameTimeline: https://github.com/google/perfetto/blob/main/docs/data-sources/frametimeline.md
