# 0.2.1検証状況

- 起点mainのActions run 38119712582: build / Bun成功。
- 今回のJVM: 39件成功、失敗0、エラー0。
- 今回のBun: 18件成功、失敗0。TypeScript型検査成功。
- 最終Androidビルド・lint・APK再計測: 実行中。完了後に更新する。
- ローカルSDK37.1 / Java17使用可能。adb接続端末0、/dev/kvmなし。ローカルinstrumentationは実行不能で未検証。
- 新版GitHub Actions / emulator smoke: このブランチをpush後に実行する。未実行を成功と扱わない。
- AdGuard公式XPI導入・HTTPS遮断、Lite HTTPS、実機性能・RAM/CPU/load、ストア配布は未検証/未測定。条件付きテストのskipは成功としない。

変更と状態遷移の根拠は[next-stage](next-stage.md)。
