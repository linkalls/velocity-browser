# v0.3 既知制限

- ライブ目標はハード上限ではなく、UNKNOWN・フォーム・認証等のページが多いとRAMが増える。OSによるプロセス終了やnative OOMの完全な回復は保証しない。
- flushSessionStateは保存完了の証拠ではない。自動休眠は安全な空ページ・独立保持した閲覧専用コピーに限定。任意のJS heap・フォーム・認証・メディアを完全復元できない。
- メタデータ保存前の強制終了／電源断では最新変更が失われる可能性がある。Undoは短期メモリ内の通常URL再読み込み。閉じたセッションの完全復活ではない。
- 非公開ファイル添付、camera/folder入力、転送再開、proxy認証、サイト権限取り消し一覧、拡張Popupは未対応。私的ダウンロードは明示exportで保存したファイルが残る。
- PasskeyはGeckoの標準OS経路を接続しているが、物理端末の作成・ログイン・provider互換性は未検証。権限／署名allowlist等の条件を満たすことは保証しない。
- Liteは限定構文のみ。1ページ128selector、observerの候補／保持要素100、150ms集約、32ms処理予算。高負荷時は余白監視を停止する。統計は全タブの限定観測数であり、広告総数や網羅率ではない。
- CSSと広告専用wrapperの判定を区別。一般の空白、本文を含む親、未識別の広告枠は削除しない。AdGuard ExtendedCSS・scriptlet・HTML filtering・全サイトの広告排除は未対応。
- CSS解除の応答がないとモード切替／AdGuard導入を停止する。エンジン切替失敗では設定を戻しても実際の拡張状態がUNKNOWNになる場合がある。診断を確認する。
- 既に遮断したリクエストは設定変更で復活しない。必要なら手動reload。入力保護のため無断reloadしない。
- 要素選択は一意のID／クラス／data-adに限定。匿名要素や広範囲を保存しない。選択中もサイト独自の複雑なpointer動作を完全制御する保証はない。
- 背景音声・通話・camera・microphone・支払い・実機TalkBack・文字200%・OEMの戻るアニメーション・長時間メモリ・実機50タブjankは未検証。
- AdGuard公式XPIの物理端末導入・遮断・更新・設定、外部TLS/DNS fixtureは未検証。正式署名／ストア配布／自動更新は未実装。
- PSS・CPU・起動p50/p95・Speedometer・Chrome/Firefox比較はNOT_MEASURED。APKサイズだけをRAMや速度の証明にしない。

## 0.5 scroll / UI overhaul

- Clipping and toolbar interruption regressions have source/unit/real-Gecko coverage;
  Pixel 7 Pro visible jump/fixed-footer/caret/60Hz/120Hz/manual flick acceptance
  remains NOT_MEASURED. No physical smoothness or Gecko FPS claim.
- Selection pin recovery uses the next engine/layout event; there is no polling
  timer during a selection drag. Validate prolonged selection and unpin on device.
- Compact sheets/large font/IME use native insets. Physical TalkBack, RTL,
  foldable resizing and OEM gesture behavior remain UNVERIFIED.
- Existing test-key signing is preserved. Independently generated CI debug keys
  can differ across runs; do not promise upgrade compatibility with an APK signed
  by a different key. In-place metadata retention under the same key is tested;
  production signing is still not configured.
- Cosmetic batches yield at 4ms/12 elements, but querySelectorAll is synchronous
  and cannot be preempted. Network rules/CSS continue while optional layout reads
  wait until 180ms after scrolling.
