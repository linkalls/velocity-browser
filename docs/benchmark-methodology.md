# ベンチマーク方法

現状の実機性能はNOT_MEASURED。synthetic-example.json/csvとfixtures/synthetic.jsonはSYNTHETIC_TEST_ONLYであり成果数値ではない。

1. Pixel7ProのOS/build/SoC/RAM/refresh rate、ブラウザ各版、回線、温度、電池、計測時刻、fixture labelを記録。URLに秘密情報がある場合は計測JSONへ書かない。configのtimestamp/temperature/battery以外の違いは別group集計。
2. Velocity Adaptive ON/OFF × Preconnect ON/OFF、Firefox Stable、Chrome Stableを交互順で各30回。cache cold/warm/hotとtabKind warm/coldを分離。Chrome側のメモリ節約・破棄状態を記録する。比較不能な操作を無理に混合しない。
3. USB接続して`adb devices`、`./gradlew :benchmark:connectedBenchmarkAndroidTest`。結果JSONとPerfettoを保存。Macrobenchmarkは起動Cold/Warm/Hot+FrameTiming。debuggable=falseのapp benchmark variantを使う。release署名とは別。
4. 固定ページは`python3 -m http.server 8000 --directory tools/fixtures`、`adb reverse tcp:8000 tcp:8000`、端末でhttp://127.0.0.1:8000/を開く。マイク等secure context試験はHTTPSテストoriginまたは端末loopbackの仕様を確認。TLS試験は有効/期限切れ/自己署名の専用サイトで、無断例外を入れないことを確認。
5. 1/5/20タブ項目を作り、開けたlive数・blocked数・保護数・破棄数を必ず記録。Web UNKNOWNにより20項目すべてを同時閲覧できない制約を比較結果に注記。未操作blankと閲覧Webの回収を混同しない。非表示120秒の前後を取得。`bun tools/bench-runner/src/cli.ts memory dev.velocity.browser output.json`はapp UID/name/子process名の合計PSS、採取前後PID変化で失敗。Chrome/Firefoxでも実際のpackage名を指定。psで関連serviceが異名の場合はPerfettoと照合して明示追加し、取りこぼしを調べる。
6. Warm選択→content、Cold復元→有用content、入力消失件数を手動/UiAutomatorとPerfettoで記録。データ損失は0がゲート。アプリ内metrics.jsonはURLを含まない絶対elapsedイベント。UI_READY、RUNTIME_CREATED、SELECT、FIRST_CONTENTを起点と対応づける。FIRST_CONTENTだけでページ全文readyを保証しない。
7. 固定長記事/JS fixtureで一定gestureを反復、Perfetto CPU/GPU/frame、メインスレッドblockを分析。EnergyPerJourneyは同一操作でバッテリー/電力rail実測ができる実機のみ。未対応ならNOT_MEASURED。CPU値をエネルギーへ捏造変換しない。
8. `adb shell perfetto --help`、Android Studio System Trace/Perfetto公式設定を端末に合わせ、アプリとGecko子processを含める。traceはWeb情報を含み得るのでローカル扱い、公開前に機密性確認。
9. APK/AABはarm64/R8の同じ条件でminimal-geckoviewと比較し、追加2MiBを努力目標として報告。Bun size CLIはAPK bytesだけ。AAB download sizeはbundletool、インストール後容量はAndroid Settings/権限を持つ端末のduで別採取。APKファイル長とインストール容量は等しくない。

集計入力はfixtures/synthetic.jsonのschemaを参照し、実測ではkind=MEASURED。metric/unit/value/configが必須。失敗はfailed=true,value=null。外れ値は削除せずp50/p90/p95(n-1の線形補間)、成功数/試行数/失敗数/温度電池rangeを出力。30成功未満はunder30=true。空inputはNOT_MEASURED。

実機安全性試験: 未保存フォームを入力→別タブ→3つ目保留→元タブで同じ入力確認。音声/マイク/カメラ→別タブ→3つ目保留、無断破棄なし。通常とprivateを作り保存I/O完了後force-stop→通常URLのみ復元。回転/背景復帰/trim memoryで同Runtimeと2live確認。権限ダイアログで許可/拒否/背景/回転を試す。HTTPS証明書エラーと外部危険schemeを拒否。20項目/2分待機/繰返し閉じる・選択でクラッシュがないことを確認。

## v0.2: 広告ブロック4条件

全試料のconfig.blockerModeをALL_OFF / ETP_ONLY / LITE / ADGUARDのいずれかとする。既定はETP_ONLY。通常モードの盾「比較用の遮断モード」から設定し、非公開タブをすべて閉じる。ALL_OFFはSafe Browsingを保持。Liteの診断がREADY_CONFIG_ACKになるまで待つが、これはblocking成功を証明しないためHTTPS fixtureの許可/遮断/例外/サーバーカウント確認を先に行う。AdGuardは公式XPIを権限確認付きで導入し、遮断成功を別確認できた場合のみ4条件目の有効試料に含める。未確認/失敗は失敗試料、未導入はNOT_MEASURED。

同一端末・同一engine版・同一fixture群・同一フィルターversion/数、同じETP/キャッシュ/Adaptive/先読み条件を記録。各条件を交互順で最低30成功試料。起動TTUI/FirstContent p50/p95、PageLoad(ms)、MemoryPSS1/5/20(KiB)、CpuTime(ms)、TransferredBytes(bytes)をBunへ投入。20タブが保留される条件は保留数を別記し、20枚すべて表示済みという条件と混ぜない。

CPUはPerfettoのsched durationをapp/Gecko子processで合計するのを優先。SQL例はPerfetto Trace Processorで `SELECT process.name, SUM(sched.dur)/1e6 AS cpu_ms FROM sched JOIN thread ON sched.utid=thread.utid JOIN process ON thread.upid=process.upid WHERE process.name LIKE 'dev.velocity.browser%' GROUP BY process.name;`。計測操作の時間窓をWHEREで限定し、異名の隔離processも実traceの親子関係から追加する。Bun `cpu`は/procが読める端末だけの補助で、短命processが抜ける。proc読み拒否は未検証/測定失敗であり0msにしない。

`tools/bench-runner/README.md`にHTTPS fixture、事前TLS到達確認、source/例外/フォームCSSの試験手順を記載。instrumentationへ `-Pandroid.testInstrumentationRunnerArguments.blockFixtureOrigin=https://site.velocity.test:8443` を追加。CA信頼をアプリの証明書迂回で解決しない。端末未接続の現在、4条件比較のRAM/CPU/PageLoadは未測定。


## v0.3追加条件（未測定）

ライブ目標3/6/12、論理1/5/20/50タブ、ALL_OFF/LITE/ADGUARD、cosmetic hiding/collapse ON/OFFを分離する。Chrome/Firefox/Velocityは同端末・同回線・同fixture・同温度/電源条件で試験し、冷/温起動p50/p95、PSS（子プロセス含む）、CPU、jank、切替時間とSpeedometerを記録する。空白高さと本文位置、CLS、100動的変更、長時間放置も比較する。エミュレーター機能試験から性能優位や実機RAM値を推定しない。未取得の全条件はNOT_MEASURED。
