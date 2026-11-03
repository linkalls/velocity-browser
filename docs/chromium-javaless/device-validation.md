# Pixel 7 Pro / Android 17 — NOT TESTED

**現在はnative APKがないため、この手順は将来用。**
`dev.velocity.browser.chromiumjavaless` は予約例で、まだ実装されたapplicationIdではない。
現在ビルドできる既存プロジェクトはJava版 `dev.velocity.browser.chromiumtest` のみ。

native engine export、初期化、IPC、ABI、ManifestのGo条件が通った後、
同じ端末にJava版と別IDのnative版を新規インストールする。既存アプリのデータは消さない。
API36 emulator成功やservice名文字列だけをnative成功の証拠にしない。

| 試験 | Java | Native | 合格条件 |
|---|---|---|---|
| Cold start ×30 / Warm start ×30 | 未再測定 | NOT TESTED | 毎回画面/ページ表示、crash/ANR 0 |
| Yahoo!ニュース20回フリング | 未再測定 | NOT TESTED | 操作不能・逆戻りなし |
| Wikipedia / Google / Reddit | 未再測定 | NOT TESTED | JS/画像/フォーム表示と操作 |
| cross-site / iframe / cross-origin | 未再測定 | NOT TESTED | renderer隔離、service lookup/IPC正常 |
| renderer終了後の回復 | 未再測定 | NOT TESTED | browser生存、復元/再読み込み可能 |
| 複数タブ | 未実装 | NOT TESTED | UI側の対応も別途必要 |
| background→foreground | 未再測定 | NOT TESTED | 黒画面・surface loss・ANRなし |
| 60Hz / 120Hz | 未再測定 | NOT TESTED | 同条件で実測比較 |
| API36以下 | Java版を使用 | native版未実装 | 将来native variantはminSdk37で分離 |

将来のPowerShell採取例（applicationIdは完成時に再確認）:

```powershell
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb logcat -c
# Native APKを起動してページ描画・JS・cross-siteを確認
adb shell ps -A -o UID,PID,PPID,NAME > chromium-javaless-processes.txt
adb shell dumpsys activity services > chromium-javaless-services.txt
adb shell dumpsys activity exit-info dev.velocity.browser.chromiumjavaless > chromium-javaless-exits.txt
adb shell dumpsys meminfo dev.velocity.browser.chromiumjavaless > chromium-javaless-meminfo.txt
adb logcat -d -b main,system,crash -v threadtime > chromium-javaless-logcat.txt
```

renderer PIDをservice binding/allocatorと照合し、browser UIDと異なるisolated UID、
実際のNativeOnlySandboxedProcessServiceインスタンス、native thread、IPCを確認。
必要ならnative tombstoneを取得する（アクセス可否は端末設定による）。
renderer forceKillは識別した実験版childのみを対象にし、main/GPU/他appは対象にしない。
unrooted端末のshell killが拒否される場合は、安全な実験版debug操作の追加が必要。
caller checks、外部appからのservice起動不可も別途検査する。

収集したlogcat、services、process一覧等はローカル保持し、CIへ自動uploadしない。
共有する前に閲覧URL、アカウント、cookie、token、他アプリ情報を除去する。
API37/isolated UID/ページ・JSが確認できなければ結果を失敗または未確認として残す。
