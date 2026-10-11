# 既知の制約・未検証

- Webの最新入力、アップロード、決済等は公式APIで完全追跡できずUNKNOWN保護。2つのWebセッションが開くと3つ目は表示保留。未操作blankのみ自動回収。20タブ閲覧の一般的なメモリ削減/高速化はまだ実証されていない。
- flushSessionState通知は要求IDなし。Webの通知を最新保存/安全破棄と認定しない。状態通知待ちは安全側に継続し、タイムアウトを成功扱いしない。
- 完全フォーム/JS heap/メディア/履歴の再起動復元なし。通常URL/title/listのみ。保存I/O完了前の即時kill/電源断には最新metadataを保証できない。CookieなどGeckoの通常profile保存はGecko標準に従う。
- ダウンロード、アップロード用ファイルピッカー、HTTP認証、screen captureは未対応案内・拒否。window.openはopenerを持たない独立URLタブに変換。支払い/Passkey/WebAuthn/DRM等の完全互換性は未検証。
- permissionsのPERMISSION保護は許可後も保守的に残る。位置情報はAndroid fine/coarse両方の成功を必要とし、概略位置だけの選択は拒否される場合がある。サイト権限管理/revoke UIは未実装。
- 証明書失敗は明示停止。Gecko標準証明書例外画面の全操作は提供せず、安全性迂回なし。
- プライベートmodeのWebデータ隔離はGecko標準private sessionを使用。アプリ履歴保存なし。privateタブが存在する間はFLAG_SECUREを適用。最近のアプリ表示を含む実機挙動は未検証。
- speculativeConnectはvoidで完了通知なし。単一発行ガード+30秒cooldownはあるがTCP接続の本数/完了を保証できない。root限定と明示許可により予測効果は小さい可能性がある。ユーザーは金融/認証rootを許可しないこと。
- Macrobenchmarkは起動とFrameTimingを提供。スクロール/タブ往復/Cold restore/電力は固定fixtureと実機の手順で測る。内部Metricsは絶対elapsed時刻であり、単体でTTFFや速度優位を算出しない。
- instrumentationコードはIntent/回転共有、repository private除外、危険Intent拒否、loopbackフォーム入力保持と20タブ項目/120秒候補イベントの自動テスト。実時間2分の待機、音声、permission確認、TLS、OSプロセスkillはリリース前実機手順を参照。未実行を合格扱いしない。
- Release署名、Gecko内包第三者NOTICEの配布確認、ストア規約審査は未完了。Safe Browsing/profile仕様はGeckoデフォルト、通常初期設定ではETP/Safe Browsing ON。比較用ALL_OFFはETP/antiTrackingのみ停止可能で、Safe BrowsingとTLSは維持。

優先順: 実機安全性ゲート → 保存最新性の公式API調査と安全な対象拡大 → サイト権限管理/重要互換機能 → 実機比較測定 → 測定に基づく最適化。

- M6の公式WebExtensions APIはコンパイル確認済み。network blocking、private区別、CSS、port、フィルター更新の端末動作は未検証。Lite初期OFF、網羅率は未測定。
- 本家AdGuardのXPIは静的manifest検査のみ。インストール・署名検証・遮断・フィルター更新は未検証。拡張Popup/設定タブ/追加タブは未対応。4条件性能はNOT_MEASURED。
- フィルターのHTTPS取得・AtomicFile置換は実装済みだが、端末での回線断・容量不足・CA/redirect/巻き戻しのfault injectionは未検証。

- Lite ON/OFF後はページを手動再読み込みして適用を確認する。既に遮断したrequestは再取得されず、拡張disableだけで挿入済みCSSが即座に消える保証はない。未保存フォーム保全のため無断の自動reloadは行わない。
