# 技術判断（2026-10-11）

1. M0: Gecko Stable Maven metadataのrelease=157.0.20261005135250を採用。Quick Startと当該版sources.jarを取得し、NavigationDelegate/MediaDelegate/PermissionDelegate/PromptDelegate等のシグネチャを確認。mozilla-central JavaDocの推測だけで実装していない。AGP 9.4.1/Kotlin 2.4.20は公式Maven POM実在確認、Gradle 9.6.0は公式ZIPとWrapperを使用。Java17、SDK37.1/Build Tools36.0.0。依存版はlibs.versions.tomlへ集約。
2. M1: Android標準View。Compose、DI、画像、ネットワークSDKを追加せずGeckoViewのみでページ表示。Runtime共有、初回UI後にrepository読み込み。追加windowはopenerを持つ新sessionを返さず、独立した管理タブにURLを渡す。window.openの完全互換性は未対応。
3. M2: Pure Kotlinを`:core`に分離し、Android SDKなしでも状態機械をテストできる構成。状態通知とsafe checkpointを別イベント化。セッションidentity/epoch/revision/saveGenerationを照合する。要求IDのない通知をflush完了扱いしない。追加枠を得る前にACTIVEをhideしない。
4. M3: `containsFormData()`は非同期Boolean。falseだけで安全を証明しない。フォーム入力監視のためのJS注入なし。アップロード/決済/WebAuthnの追跡も証明できないため、すべてのWebセッションにUNKNOWNを残す。120秒・低メモリ・LRUでも保護を優先する。実エンジンでのblank checkpointは「保存通知完了」ではなく、未操作空ページに失われるユーザーデータがない独立証拠。snapshot通知を使うかどうかとは無関係。
5. SessionStateは文字列化/永続化しない。秘密情報・互換性・サイズの確認前なのでWebページ状態を復元済みと主張しない。通常metadataのみSharedPreferences、allowBackup=false。DB/暗号化ライブラリ追加なし。
6. M4: speculativeConnectはvoidで完了通知なし。最大1発行ガードを30秒保持し同一origin cooldown。実TCP接続の完了や本数をアプリAPIで証明できない点は既知制約。候補はユーザーが公開・非金融/非認証ページと明示指定したHTTPSルートの同一originに限定。未許可/パス/クエリ等ではOFF。ON初期値は全サイトで接続する意味ではない。
7. 内部Metricsは固定enum相当の名前と数値のみ、512件ring buffer、手動ローカル出力。外部送信なし。SystemClockの絶対経過値はKPI値ではない。TTUI/FirstContentの正確な差分とPerfetto照合は実機計測ゲート。
8. 追加依存: GeckoViewはエンジン。Junit4はJVMテスト、AndroidX test/uiautomatorはinstrumentationのみ、Macrobenchmarkは別test APKのみ。Bunの@types/bunとTypeScriptは補助ツールの開発依存。通常APKへベンチ/testライブラリを含めない。サイズ差分は同エンジン・arm64・R8構成のminimal-geckoview比較で測る。
9. Adaptive OFFでも2ライブ上限は維持。制限解除で安全性と比較条件を変えない。OFFは時間/低メモリ回収停止の実験フラグ。純粋な無制限タブブラウザとの比較は別途公平条件を記録する。
10. M5: Startup Cold/Warm/Hot Macrobenchmarkを実装。Bunは計測JSON検証、設定ごと分離、p50/p90/p95、失敗数、外れ値保持、PSS全process合計、APK差分。CPU/電力/ページ描画性能は実機なしではNOT_MEASURED。releaseの署名は配布者が管理する。

11. ビルド時のAAR metadataでStable157がcompileSdk37/AndroidX core1.19/AGP9.1以上を要求することを確認。repository2-3.xmlの安定platform37.1_r01とAGP9.4.1公式互換表（Gradle9.6.0/Java17/API37）を採用し、AndroidモジュールはAGP built-in Kotlinに移行。Build Tools36.0.0はAGP公式default。Kotlin/JVM plugin2.4.20を併用。旧SDK36構成の失敗は検証履歴に記録。

12. Gecko Stable157のtransitive Kotlin stdlib2.4.20に合わせ、公式Mavenで存在を確認したKGP2.4.20を採用。stdlibを古い版へ強制ダウングレードせずcompilerと揃える。

13. AARのminCompileMinorSdk=1に合わせ、stable platform37.1、compileSdk release(37){minorApiLevel=1}、AGP9.4.1/Gradle9.6.0へ固定。SDK37.0でのdebug生成成功とは別に最終構成を再ビルドする。API33+は標準OnBackInvokedDispatcher、26〜32はlegacy onBackPressedを使い、fallbackだけlint理由を明記。カメラ/マイク/位置hardwareは任意機能としてmanifestへ宣言。

14. v0.2第16章を受領しM6を追加。Lite初期OFF/ETP ON、固定HTTPSリスト手動更新、未対応構文スキップ、privateメモリ設定、AdGuard追加実験、2ライブ維持。詳細はadblock-decisions.md。APKへAdGuardコードや外部フィルターをコピーせず、静的検査と実機互換性を区別する。

15. COLDの手動closeにはengine CloseSession commandが出ないため、dispatch完了後に削除済みtab IDのsnapshot参照も除去する。安全blankの自動休眠stateは復元用に保持し、手動close/失敗と区別する。
