# Bun benchmark tools

Bun 1.4.3: `bun install --frozen-lockfile`, `bun test`, `bun run typecheck`。

- `bun src/cli.ts aggregate input.json output-prefix` → JSON/CSV。入力例 `fixtures/synthetic.json` は **SYNTHETIC_TEST_ONLY**。4つのblockerMode（ALL_OFF / ETP_ONLY / LITE / ADGUARD）を必須条件として別集計。CpuTime(ms)、PageLoad(ms)、TransferredBytes(bytes)も対応。
- `bun src/cli.ts memory dev.velocity.browser pss.json` → Android app/Gecko child PSS。PID変化は失敗。
- `bun src/cli.ts cpu dev.velocity.browser 30000 cpu.json` → proc stat差分CPU時間/平均CPU%。計測中に固定操作。stock端末で/procアクセスが拒否される場合は失敗し、Perfettoで測る。短命プロセスの捕捉にはPerfettoが必要。アクセス成功でも完全な電力測定ではない。
- `bun src/cli.ts size browser.apk minimal.apk size.json` → APKファイル長。インストール容量はNOT_MEASURED。
- `bun src/https-fixture.ts cert.pem key.pem 8443` → 広告遮断試験用HTTPS fixture。

HTTPS fixtureは実機から到達可能な専用LANで実行し、両方のホスト名をfixtureマシンにDNS解決する。有効な証明書、またはテスト専用ブラウザprofileに正しく信頼設定された専用CAが必要。Velocity側でTLSエラーを隠す仕組みは提供しない。端末のCA登録だけではGeckoが信頼するとは限らない。信頼を確認できなければHTTPS遮断試験は未検証。

通常モードのユーザー規則へ `||ads.velocity.test^` と `site.velocity.test##.ad-slot` を設定。例外なし/Lite ONでは`AD_FAILED CONTROL_OK`かつ`/counts`のadRequests=0、ETP_ONLYまたはサイト例外では`AD_ALLOWED CONTROL_OK`かつadRequests>0を確認。**先に許可条件で両ホストのTLS/DNS到達成功を確認し、単なるDNS/TLS失敗を遮断成功にしない。** 各条件前に`POST /reset`。CSS広告枠だけが消え、フォーム付き枠と入力が残ることを目視/UiAutomatorで確認。

countsの転送量はfixtureレスポンス本文だけでTLS/header/キャッシュを含まない。実サイト総転送量は端末/サーバー側の正式計測を用いる。秘密鍵をリポジトリへ置かない。テストserverは0.0.0.0で待ち受けるため専用テストネットワークで使用する。
