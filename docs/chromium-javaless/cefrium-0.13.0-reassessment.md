# Cefrium 0.13.0 再監査 — 2026-10-24

**監査のservice名誤判定を修正した。Native-only Rendererは引き続きNO-GO。**
新版でsandbox設定の問題が修正されたことと、Native Serviceを起動できることは
別々に確認する必要がある。今回、公式0.13.0のソースと配布AARを実際に取得した。
既存JavaアプリのSDK依存・applicationId・Manifest・起動コードは変更していない。

## 固定点と供給元

| 対象 | 確認したversion / commit |
|---|---|
| Cefrium | v0.13.0、tag object `61051f554ca395c9ded213499b0a700be78b3a2a`、commit `88a122f9d3fb45b8450359b5c3a820ec4c8811cb` |
| CEF | branch 8078（0.9.4の8037から変更） |
| Chromium | 156.0.8078.17、commit `ab5de11f88f0792d2ad84e22abd460412e915f13`（旧154.0.8037.98と混用不可） |
| 公式SDK | `com.cefrium:cefrium-sdk:0.13.0`、Maven Central |
| AAR SHA256 | `18f52c00e1e6b75e75668042ad56c1e9e58442cce3800fc4d98d427ac7f40f83` |

一次資料は [Cefrium固定commit](https://codeberg.org/cefrium/cef-android/src/commit/88a122f9d3fb45b8450359b5c3a820ec4c8811cb)
と [Chromium固定commit](https://chromium.googlesource.com/chromium/src/+/ab5de11f88f0792d2ad84e22abd460412e915f13)。
26ファイルの取得URLとSHA256は
[`upstream-0.13.0-lock.json`](../../experiments/chromium/native-engine/upstream-0.13.0-lock.json)。
ソースのtag/commitは解決し、各ファイルのbytesを再取得してhash照合した。
AARは公式配布物を変更せず検査した。公開署名の認証、SDKの独自ビルドは実施していない。

## 修正した監査

従来の `audit.py` はJava service名を
`org.chromium.content.app.SandboxedProcessService*` に固定していたため、
0.13.0のAARを実際に監査すると誤って0件と報告した。40件を期待するassertionで再現した。

修正後はupstream launcher patchと同じapplication metadata
`org.chromium.content.browser.SANDBOXED_SERVICES_NAME` を読み、
未指定時のみ旧Chromium名を使用する。宣言されたNUM_SANDBOXED_SERVICESに対し、
番号が連続し重複・欠落がなく、各serviceがisolatedであることを検査する。
`--require-java-pool` はこれが満たされなければ終了コード2で拒否する。
**AAR source XMLの宣言検査であり、merged APKや実行時sandboxの合格ではない。**

別prefixのservice、privileged service、似た名前の不正suffixは数に含めない。
Native serviceは実際の `android:nativeService="true"` 属性で数え、
Javaクラスの名前だけでNative対応を認定しない。exportの必須ゲートは維持した。

## 確認結果

| 項目 | 0.9.4 | 0.13.0 |
|---|---|---|
| Java sandbox service base | `org.chromium.content.app.SandboxedProcessService` | `com.cefrium.ChildServices$Sandboxed` |
| Java service数 / 宣言されたpool | 40 / PASS | 40 / PASS |
| Java serviceのisolated属性 | 全40件true | 全40件true |
| Native Service宣言 | 0 | 0 |
| arm64 Native入口export | FAIL | FAIL |
| x86_64 Native入口export | FAIL | FAIL |
| 0.13.0のzygote指定 | — | Java service 0に `useAppZygote=true` |

実測レポート: [`official-0.13.0-audit.json`](official-0.13.0-audit.json)。
0.9.4についても修正後ツールで両ABIを再監査し、40件とexport欠落を再確認した。
0.13.0の `--require-java-pool` は成功、さらに `--require-native-export` を付けると
終了コード2で失敗した。監査の完了とNative対応の成功を混同しない。

ソースで確認した新版の改善:

- `Cefrium.java` は `no-sandbox` を追加しなくなった。
- `cef_android_jni_bridge.cc` / `cefrium_sdk_jni.cc` は `settings.no_sandbox=true` を設定しなくなった。
- `ChildServices.java` が実在し、Sandboxed0..39はChromium Java serviceのsubclass。
- `AndroidManifest.xml` は独自class prefixをmetadataで指定し、service 0にapp zygoteを指定。
- `patch/patches/android/child_service_names.patch` はChromium selectorのJava名をmetadata対応にする。
  固定Chromium156ファイルへの `git apply -p0 --check` も成功した。

メンテナー報告のNoNewPrivs/seccomp filter数はこの環境で再測定していない。
Android 17実機でのbinder、UID、seccomp、zygote動作は未検証。

## 残るNativeブロッカー

0.13.0の `libcef_dll/libcef.lst` もglobal exportは
`cef_*` / `Java_*` / `JNI_OnLoad` のみ。
公式AARの両ABIで `readelf --dyn-syms -W` と `nm -D -C` を実行し、
`NativeChildProcessService_onCreate` の非公開を再現した。

固定Chromium156には実際の `extern "C" void NativeChildProcessService_onCreate(ANativeService*)`
があり、`Run()` は `NativeInitializationHook` を呼ぶ。weak defaultはfalseで、
JNI_OnLoadはnative-only起動では使われない。Cefriumソースには専用strong hookの定義が
見つからず、delegate/resource初期化は依然JNI経路にある。最終link mapは未生成。
このため、最新版への依存更新だけでNative起動を実装したとは言えない。

既存のexport-only patchは**0.9.4用の検討資料**として保持する。
0.13.0用の完成パッチと称して適用・配布しない。Native Manifestだけも追加しない。

この環境は空き約30 GB・RAM約9.7 GiBで、Chromium全体の約400 GB・RAM32 GB以上の
ビルド要件を引き続き満たさない。対応ソースが全くないという理由ではなく、
Native hook/リンク/IPCの未実装・未検証と、ビルド資源不足が残るNO-GO理由である。

## 互換性と次の実装条件

0.13.0を使うアプリを追加する場合は、旧Java安定版と別ID/variantに分離し、
plugin/SDK/Chromium resourcesを同一リリースで揃える。既存の
`getSandboxedServicesName()` に対する旧class名の比較はそのままでは失敗するため、
新しいmetadataベースの選択に合わせる必要がある。

0.12以降は第三者MavenライブラリをAARからPOM依存へ分離している。
changelogはGuava/Okioの旧excludeを外すよう要求するため、既存GradleのGuava除外も
盲目的にコピーしない。QueryOriginPolicy等のbridge変更と、0.13のmain-frame制限も
必要な使用箇所で検証する。現在のJava baselineには依存更新を適用していない。

Native再開には、156で整合したCefrium strong initialization hook、real service objectの
リンク・公開、API37 loader/IPC/FD/isolated UID/renderer回復の検証が必要。
その後に独立Native APK、merged Manifest監査、Pixel 7 Pro試験へ進む。
バイナリを配布する場合は更新版LGPL対応ソース・改変表示・CEF/Chromium/codec NOTICEを揃える。

## 検証範囲

- 修正前の0.13.0の誤検出を再現、修正後に両version・両ABIを監査。
- 14 unit tests成功。新版名、旧版名、metadataによる選択、relative service属性、欠落/重複/非isolated/不正count、Native属性、空ABI指定とexport拒否を検証。
- 0.13.0の固定ソース26ファイルのhash照合と、upstream service-name patchの適用確認が成功。
- CIは0.9.4/0.13.0のmatrixで独立監査し、監査資料のみを保存する。
- JDK/Gradle/AGP、Javaアプリ、旧Java CIは変更していない。再ビルド/起動結果はPR #9のChecksに記録。
- Native engine/AAR/APK、API37 emulator、Pixel実機、CPU/PSS/FPSは **NOT BUILT / NOT TESTED**。
