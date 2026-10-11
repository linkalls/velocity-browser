> 以下には0.2系の設計・結果を含みます。v0.3の変更と最新の実行結果は [v03-validation](v03-validation.md) を参照してください。

# Velocity Block Lite — v0.2判断と未検証

更新版spec.md第16章を実装基準として読み、M0〜M4の後にM6を追加。独自実装はMIT。AdGuard本体や外部フィルターをAPKへコピーしていない。

## API・方式

採用Stable 157.0.20261005135250のsources.jarで `ensureBuiltIn`, `enable/disable`, `install`, `setAllowedInPrivateBrowsing`, PromptDelegate, background native port, `setTabActive` のシグネチャを確認し、Androidコンパイルに成功。これは端末でのWebExtensions互換性証明ではない。

`ensureBuiltIn(resource://android/assets/velocity-block/, velocity-block@velocity.browser)`を共有Runtimeに一度呼び、同版の再インストールは公式APIに委ねる。成功時だけ同梱versionを記録。WebExtension権限はall_urls/webRequest/webRequestBlocking/tabs/nativeMessaging/geckoViewAddons。nativeMessagingFromContentは付与せず、native portのアプリ名・extension ID・extension環境・session=nullを検査。ready返信は型・サイズ・世代を検査。ACKは設定適用の応答であり遮断成功ではない。本文/フォーム入力/閲覧URLをnativeメッセージで収集しない。

初期ETP ON、Lite OFF。性能・誤検知が未検証のため既定ONにしなかった。盾から明示的にLiteを有効化できる。Lite/AdGuard切替は両方を停止してから一方を有効化。AdGuard install前にもLite停止を待つ。ALL_OFFは比較用にETP/antiTrackingをOFFとするがSafe BrowsingとTLSは維持。ETP設定は共有Runtime全体のため、privateから変更不可。

拡張がGeckoSessionを生成する経路は提供しない。拡張tabs.create/設定タブを明示拒否、tabs.update/removeはstable API既定の拒否。Android Schedulerだけがセッションを開く。setTabActiveで選択状態を拡張へ通知。AdGuardのPopup/設定UIを代替エンジンで開かない。これらは**未対応**であり、本家完全互換として販売できない。

## ルールと安全性

原著の最小同梱リストは4ドメインとfixture用CSS1件。広告・trackerドメインの名前を記載した独自リストで、第三者リストのコピーなし。網羅的広告遮断率は未測定。

対応: `||domain^`、`@@||domain^`、`domain##selector`。ネットワークはhost suffixのSet索引でドメイン境界を守り、許可規則優先、HTTP(S) subresourceだけ、main_frameは拒否しない。全規則の毎request線形走査なし。CSSは5つの限定selectorのみ、scopeのhost suffix照合。form/controlそのものとform/controlを含むcontainerをCSSで除外。DOM走査・MutationObserver・常駐監視timerなし。初期native port再接続のみ最大5回。

**未対応:** URL path/wildcard/regex、$third-partyを含むすべてのmodifier、複数domain/除外domain、generic CSS、scriptlet、redirect、アンチ広告ブロック、AdGuard独自構文。検査時にスキップ数を表示し、適用成功とはしない。eTLD+1を自作近似しない。サイト例外はURLクエリを含まない正規化HTTP(S) origin（scheme/host/port）一致、HTTP/HTTPS/サブドメインを別扱い。適用確認には再読み込みを案内。

privateの有効フラグ、例外、ユーザー規則はメモリだけ。通常設定をprivateへ複製しない。同梱・共有更新リストだけ両方で使用可能。最後のprivateタブを閉じるとprivate設定を消去。拡張はstorage権限を持たず、JS側は一時Map/Setのみ。WebRequest incognito、content senderのprivate区別、Gecko profile分離は実機ゲート。

## 更新・ライセンス

更新元固定: https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext 。配信側 `!Last modified: RFC1123日時`（または12桁version）をUTC12桁へ正規化して検査、古い版への巻き戻しを拒否。明示許可後に単一I/O executorでHTTPS取得。redirect禁止、timeout15秒、最大2MiB、最大20000採用規則、完全取得・構文検査・版検査後AtomicFile置換。失敗は旧版継続。filter fetchを初回frame/起動必須経路に置かない。ユーザー規則最大64KiB。remote code/eval/CSS任意注入は禁止。compiled filter cacheはアプリ内部のみ。ネットワークの手動更新で配信元にIP/HTTPS要求は送るが閲覧URLは送らない。

[Peter Lowe提供元](https://pgl.yoyo.org/adservers/)は「Feel free to combine this list with yours or lists from other sites and put it up on the web, though!」と統合・Web掲載を明示許可している。SPDX形式のライセンス名を推定して付けず、この公開許諾を記録する。credit: Peter Lowe / pgl@yoyo.org、source/版/SHA256/取得日時をcacheへ記録し、UIにsource/版/日時を表示。0.2.1では公開再掲載許諾を再確認し、同版リストとcreditを同梱する。更新取得はユーザー選択のみ。

実リスト検査でEasyPrivacyは43,218対応規則（1,508,816 bytes）あり、2万件上限を超えた。そのため既知の小さいPeter Loweリストを選択し、上限を拡張せず保持。リスト件数・ファイル長は静的確認で、常駐RAMの実測ではない。

## AdGuard実験

公式Mozilla AMOの署名済みXPI 5.5.2.3を固定、ID `adguardadblocker@adguard.com`。ユーザー操作と個別permissions/origins確認後のみ `install`。private利用・技術データ収集は拒否。未知IDは削除。自動更新/追加権限は許可せず、オプション導入を実験と表示。本体コード/フィルター/アイコンはAPKにもソースZIPにも含めない。拡張自身のフィルター更新は本家実装に従うが、このhostでの動作は未確認。

2026-10-11の[静的検査](benchmarks/adguard-static-inspection.json): manifest Android>=113、tabs/webRequestBlocking/storage等、options page・popup宣言、META-INF署名ファイルの存在を確認。署名ファイルの存在は暗号学的署名検証ではない。実際のMozilla署名検証はGecko install時に行われるが、その実行は**未検証**。

source repository LICENSEはGPL-3.0、同日時点のAMO metadataはLGPL-3.0-onlyを記載しており一致しない。再配布は行わない。将来再配布するなら対象版の実際のLICENSE/著作権表示/ソース提供を確認し、AMO表示だけでGPL義務がなくなると判断しない。AdGuardの商標・図案をVelocity独自機能のブランドに流用しない。

## 受入・性能

Pure engineのBunテストとKotlin規則/版/上限/正規化テストは実行済み。配信元の実リストについても静的parser確認を行い、[採用/スキップ件数](benchmarks/filter-static-inspection.json)を保存。ただしAndroidでの更新/置換の実行は未検証。mock WebExtensionsテストは実GeckoのAPI動作を証明しない。

HTTPS integration test sourceは先にETP_ONLYで広告/許可URLへのTLS到達を確認し、Liteで遮断、origin例外で再許可、2liveを確認する。信頼済みHTTPS fixture引数が必要。未指定はskip、端末なしは**未検証**。server request count/CSS実画面/フォーム/長時間/private/インストール失敗/更新失敗/音声退行は実機ゲート。

ALL_OFF / ETP_ONLY / LITE / ADGUARDの起動p50/p95、PSS1/5/20、CPU、ページload、転送量、電力は **NOT_MEASURED**。フィルターデータ常駐RAMも未測定。MB削減、%削減、広告遮断率を保証しない。サイズのみ同条件Release APK vs minimalを実ファイルで測り、[size.json](benchmarks/size.json)へ保存。追加フィルターを増やす判断は実測後。

## 0.2.1の変更

LiteにPeter Loweの2026-10-10版を同梱した。以前の非同梱方針はこの版で変更。出典と公開再掲載許諾・creditをlicensesとassetsに保持する。規則上限を増やさず、約3,500ドメインの索引で開始する。AdGuard設定タブは署名済み導入結果のID/baseUrl一致と2ライブ枠に限定して提供する。Popupと完全互換性は未検証。URLなしの遮断判定件数は提供するが、これを実機遮断率や性能へ変換しない。
