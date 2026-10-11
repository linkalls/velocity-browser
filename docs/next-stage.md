> 以下には0.2系の設計・結果を含みます。v0.3の変更と最新の実行結果は [v03-validation](v03-validation.md) を参照してください。

# 次段階: 安全な閲覧範囲とネイティブUI

起点: checkout時点のmain b487694。開発中にmainを再取得し、ecd714fのAPK-only artifact変更をmergeして保持した。既存GitHub Actions run 38119712582 はbuild / Bunとも成功。GeckoView Stable 157.0.20261005135250固定。Android非依存AdaptiveTabSchedulerと実装名AdaptiveTabCoordinator（Gecko側の仲介）を精査した。

## 保留条件と状態遷移

通常HTTP(S)はUNKNOWNを保持する。onPageStopでcontainsFormData=falseでも、JS heap、fetchによるアップロード、決済、認証操作、メディア開始の不在を証明できない。2枠の双方が証明不能なら、SelectTabは現在表示を変更せずpendingIdを記録する。

SAVING中のリスク・navigationは証拠を失効し、再試行可能なWARMへ戻す。保存要求は5秒タイムアウトで世代を失効する。通知を成功へ変換しない。独立証拠が成立した時、またはユーザーが確認して不要タブを閉じた時、保留選択を再開する。別タブの明示選択で保留を取消す。

安全な非表示LRUを先に解放する。非表示候補が保護中でも、切替前の表示タブが再表示可能な場合は、Detach / inactive / WARMを経てから解放する。CloseSession時点ではACTIVEではない。復元不能なフォーム、音声、権限等はこの経路へ入らない。セッションはCloseしてからCreateし、上限2を維持する。

## Stable公開APIの復元限界

同版sources.jarのGeckoSession.java:

- restoreState: 保存されたhistory、scroll、zoom、form dataのみ。完全フォーム、file、JS heap、WebRTC、進行中通信、認証等は保証しない。
- flushSessionState: 非同期void。ProgressDelegate.onSessionStateChangeにflush要求ID・navigation epochがない。最新性を認定するACKにはしない。
- containsFormData: form dataの存在を返す非同期Boolean。「入力可能な要素が存在しない」ことや安全な完全復元を保証しない。
- GeckoSessionSettings.allowJavascript(false)とLoader.data(byte[], MIME)は公開API。別の描画エンジンへ変更していない。
- WebExtension.metaData.baseUrl / optionsPageUrlとTabDelegateは公開API。拡張自身のページをアプリ管理のsessionへ載せる。

FetchFlagsのJava IntDefにはflag=trueが欠けるがfetch JavaDocは"one or more"を明記する。匿名・private・redirect禁止の公開flagsをORする箇所だけWrongConstantを抑制している。TLS / classifierは迂回しない。

## 段階的拡張: 閲覧専用コピー

通常ページを無断で書き換えたり、危険なページのUNKNOWNを解除したりしない。ユーザーが「閲覧専用」を選んだ公開URLをGeckoWebExecutorで匿名/private GETし、Cookie・認証情報を送らず、通常profileから分離する。HTTP(S) status 200、HTML/plain textのみ、redirectは停止する。UIの論理取得待ちは30秒、本文readは5秒・処理20秒で制限する。native GETの完全cancelは保証できないため、論理timeout後も未完了GETが終わるまで追加取得を拒否して同時1件を維持する。

Jsoupで取得したHTMLを解析し、form/input/textarea/select/button/script/media/frame等を除去する。抽出した文字列をescapeし、アプリ所有の固定HTMLへ入れる。元のDOMや入力値をJavaScriptで収集しない。URL・任意属性・サイトのCSSは復元HTMLへ引き継がず、CSP default-src none、JavaScript無効でGeckoView表示する。画像・リンク操作・ログイン・サイトJSは使えない。

完成した本文コピーを保持している場合だけReplayReadyで証拠を発行する。Geckoのflush保存ではなく、同じ不変本文を再表示できる独立証拠である。対象もidentity / epoch / private属性 / 追加保護を検査する。snapshot callbackを最新と偽認定しない。

原文512KiB、生成HTML128KiB/タブ、全コピー2MiBの上限。超過時は失敗し、保持中本文を勝手に捨てない。これはキャッシュのバイト上限であり、実RAM使用量ではない。CloseTabで参照を解放する。コピーは永続化せず、通常URLと閲覧モードだけ保存する。

休眠復帰は保持した同じ本文を先頭から再表示する。スクロール・zoom・履歴はこのコピー経路では保証しない。再起動後は再取得するため同一内容を保証しない。プライベートは未対応。AdGuard等外部拡張が存在する場合は無効化し、導入前に既存ライブページの証拠も失効する。外部拡張が存在する場合はblankの独立証拠も発行しない。アンインストール直後に自動再認定せず、再起動後に再取得する。

通常の動的Webタブ2枚が保護中なら3枚目は引き続き保留。全種類のWebページを安全に自動破棄できる完成版とは扱わない。フォームを持つ通常タブ1枚と、再表示可能な閲覧専用タブの往復は状態機械で検証し、実Geckoの20ページ往復試験も用意した。

## UIと広告ブロック

Android標準Viewの範囲で、丸いアドレス欄、Canvasアイコン、下部ナビゲーション、ホーム、下部タブ一覧、専用アイコンを追加。プライベートを含むタブ一覧DialogにもFLAG_SECUREを適用する。可視UIとアクセシビリティの実機検証は別記の結果に従う。

Liteは少数の独自規則とPeter Loweドメインリストを同梱する。出典・公開再掲載許諾・creditをassetsとlicensesに保持する。更新は手動、失敗時旧版、古い版は拒否。新リストへ置換時に古い同梱ドメインを混合し続けない。任意CSS / scriptlet / 正規表現は未対応で、許可したCSSのみform保護付きで適用する。すべての広告・第一者広告への網羅性は保証しない。

Native portへURLなしのnormal/private遮断判定件数を最大1秒に1通知で送る。画面の件数はcancel判断の数であり、エンジンが実際に遮断した広告数・遮断率の測定とは別。新設定でカウンタをリセットする。Lite / AdGuard設定変更では入力のあるページを無断reloadしない。適用確認は明示reloadで行う。

AdGuardの設定・初期設定タブを全面拒否していた経路を修正。署名済み導入結果のIDとbaseUrlに一致するmoz-extensionページのみ、同じSchedulerへ登録する。通常タブの保護を破らず、2枠が埋まれば保留。内部UUID付きURLはmetadataへ保存しない。外部Intentから内部URLは受け付けない。Popup、任意他拡張・任意外部タブ操作、完全互換性は未対応または未検証。

実機向けLite試験: loopback 127.0.0.1/127.0.0.2で、先に同一広告endpointへの到達を証明し、その後webRequest cancel、CSS、フォーム保全、native counter、サイト例外を確認する。外部DNS/TLSなしの実Gecko試験。HTTPS fixture試験も保持する。

AdGuard実機試験: 明示opt-inで公式AMO XPI導入、version/enabled/private除外、モード切替を検査する。別の専用HTTPS試験ではAdGuard設定のユーザー規則を準備し、OFF時到達→ON時遮断→設定切替で入力保持を確認する。実機で未実行なら未検証。引数未指定のAssumptionが現在のAGPでfailureとして記録されるため、通常スモークはRequiresExternalFixture annotationの3件を除外し、8件すべての実行を必須にした。除外は成功としない。個別手順は[端末テスト](device-tests.md)。
