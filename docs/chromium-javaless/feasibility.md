# Android 17 Native-only Renderer — Phase A / NO-GO

調査日: 2026-10-13。**Native Rendererは未実装・未ビルド・未起動。実験APKは作成していない。**
仕様書の「Goできない場合はここで止まり」に従い、Phase B/Cは開始しない。
ソース入手は成功したが、ビルド資源とCEF固有の初期化対応が未解決。

## 1. Implementation summary / pins

作業ブランチ: `experiment/chromium-javaless-native-renderer`。
取得時のJava版リモートHEADは指定の
`8a223d6b57fbfa7cc978914eb3e51d170aa7bc08` と一致した。
`ChromiumTestActivity.java` はAPI 37以上で、browser作成前に
`javaless-renderers=disabled` を付け、service名がJava版であることを確認する。
diagnostic Application/Activity、Manifest、Gradle、`boot-smoke.sh`、既存CIも確認した。
これらの動作ファイルは変更していない。

| 対象 | 確認した固定点 | 一次資料 |
|---|---|---|
| Cefrium 0.9.4 | tag object `f382bcd246986051beb447c03cbadcb58127f1d7` → commit `1e2367bd80ce762fe75383455b5b1cd7e5e019a5` | [v0.9.4](https://codeberg.org/cefrium/cef-android/src/tag/v0.9.4) |
| CEF | branch 8037、Cefrium changelogとcompatibility fileで確認 | [compatibility](https://codeberg.org/cefrium/cef-android/src/commit/1e2367bd80ce762fe75383455b5b1cd7e5e019a5/CHROMIUM_BUILD_COMPATIBILITY.txt) |
| Chromium | `154.0.8037.98` → `b859317bf11f6be47f9b7799ec690a0a42a1fb33` | [tag](https://chromium.googlesource.com/chromium/src/+/refs/tags/154.0.8037.98) |
| Android frameworks/base | 調査時 `android17-release` → `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d` | [commit](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d) |

33ファイルのURL・decoded SHA256、AARのURL・SHA256を
[`upstream-lock.json`](../../experiments/chromium/native-engine/upstream-lock.json) に記録した。
取得ツールは必ずcommit URLを使い、hashが違えば失敗する。
タグ署名の検証は行っていない。全Chromium/DEPSのcheckoutではない。

追加ファイル:

- `experiments/chromium/native-engine/`: ソース取得、AAR/ELF監査、監査ゲートのテスト、export専用パッチ、再開手順。
- `.github/workflows/chromium-javaless-experiment.yml`: **audit-only / NO APK**。
- `docs/chromium-javaless/`: feasibility、実測監査JSON、実機手順、未測定の性能表。

## 2. Root cause / feasibility

以下のパスは上表の固定commitのもの。最新mainを根拠にはしていない。

| ソース | 確認結果 |
|---|---|
| Cefrium `BUILD.gn: libcef` / `libcef_dll/libcef.lst` | Androidにもversion scriptを適用。globalは `cef_*`, `Java_*`, `JNI_OnLoad` のみ、残りは `local: *`。Native入口は公開対象外。 |
| Chromium `content/app/android/javaless_child_process_service.cc` | 本物の入口は `extern "C"`・default visibilityの `void NativeChildProcessService_onCreate(ANativeService*)`。JNI関数ではない。 |
| Chromium `content/app/BUILD.gn: app` / `content/public/app/BUILD.gn` | Androidでは同translation unitとnative AIDL依存を組み込む。単独 `.so` ラッパーを追加する設計ではない。CEFの最終GN/link graphは未生成。 |
| Cefrium `libcef/browser/android/cef_jni_onload.cc` | `JNI_OnLoad` にVM登録、`OnJNIOnLoadInit`、locale pak設定、子プロセスの `ChromeMainDelegateCef` 設定がある。native起動ではこの入口が呼ばれない。 |
| Chromium `javaless_child_process_service.cc: Run` | command line初期化後に `NativeInitializationHook(process_type)` を呼び、falseならFATAL。弱いデフォルト実装はfalse。 |
| Chromium `chrome/browser/android/chrome_entry_point.cc` | Chromeは強いC++ hookを定義。`chrome/app/android/chrome_jni_onload.cc` を経由してChrome固有delegateを設定する。CEFにそのまま流用できない。 |
| Cefrium native source | Cefrium独自の `NativeInitializationHook` 定義は検索で見つからなかった。Java起動相当のVMなし初期化とCEF rendererのJNI依存を調査・実装する必要がある。 |
| Cefrium `cefrium_sdk/BUILD.gn` | `cef:libcef`、JNI registration、Java classes、pak/ICU/V8 resourcesを同一ビルドのAARに格納。 |
| Cefrium `cefrium_sdk/AndroidManifest.xml` | 40 Java sandbox serviceを直接列挙するstatic Manifest。Native service生成はない。 |
| Cefrium `build-all-variants.sh` / `slim_aar.py` | GN→ninja `cef:libcef cef/cefrium_sdk:cefrium_aar`→slim AAR。既存バイナリへの注入ではない。 |

**確定できる原因:** version scriptはNative入口を隠す。公式AARの両ABIでも
dynamic symbol非公開を再現した。
**未確定:** published buildでobjectが残るか、LTO/dead-stripで消えるか、どの強いhookが
最終的にリンクされるか。strip済み配布物には対応するlink mapがなく、独自リンクも未実行。
「exportリスト変更だけで完成」「追加GN flagだけでenable可能」とは結論しない。

### 選択・評価タイミング

固定版 `ChildProcessCreationParamsImpl.isNativeSandboxedServiceEnabled()` は、
明示的なforce設定があればそれを使い、なければAPI 37以上・非incremental install・
primary ABIと一致する実行ABI、および `JavalessRenderersFeatureList.isEnabled()` で判定する。
`getSandboxedServicesName()` はその結果でNative/Java名を返す。

`JavalessRenderersFeatureList` は**初回評価をstatic Booleanにキャッシュ**する。
`--javaless-renderers=enabled/disabled` は初回評価時だけ有効で、未指定なら
CachedFlagの既定値trueを使う。Activityの後で変更しても有効とは限らない。
将来のnative variantはSDK providerとallocatorより前に選択を固定し、実際のbinder/service/PIDも照合する。
既存Java版の起動時service名チェックを維持した。

Cefriumのvendored `BuildConfig.java` は `JAVALESS_RENDERERS_AVAILABLE=true` だが、
**固定版の上記selectorはその値を参照しない**。定数変更をnative実装と見なさない。
調査した固定版のGN config/rulesとCefrium build scriptには旧
`enable_javaless_renderers` は存在しなかった。未確認のtoggleは追加しない。
完全な `gn args --list` はビルドtreeがないため未実行。

## 3. Native build evidence

**ネイティブビルドは未実行。** actual GN args、compiler revision、link map、独自AAR/APK checksumはない。
upstream scriptのcommon argsは `is_official_build=true`, `is_component_build=false`,
`is_debug=false`, `enable_jni_multiplexing=false`, `use_hashed_jni_names=false`,
`chrome_pgo_phase=0`, `v8_use_external_startup_data=false` 等。
全argsと再開コマンドは固定版scriptと [native-engine README](../../experiments/chromium/native-engine/README.md) を参照。

この環境: Debian/Linux、空きディスク約30 GB、RAM約9.7 GiB、swapなし、OpenJDK 21.0.12.1。
JDK 25、Gradle、Android SDK/ADB、Chromium Android build toolchainは未インストール。
Cefrium [build guide](https://codeberg.org/cefrium/cef-android/src/commit/1e2367bd80ce762fe75383455b5b1cd7e5e019a5/BUILD-GUIDE-CEFRIUM.md)
は約400 GB、RAM最低32 GB（64 GB推奨）を要求する。全checkoutとリンクはこの環境に収まらない。
consumerはJDK 25 / Gradle 9.7.1 / AGP 9.4.1 / SDK 37.1を既存独立プロジェクトで維持する。
build guideの古いChromium 150例とJDK 21一般要件は0.9.4のconsumer要件と区別した。

実施した公式配布物監査（GNU binutils 2.44）:

```text
Artifact: com.cefrium:cefrium-sdk:0.9.4 (Maven Central)
AAR SHA256: 4ec9aac59f2ef2663170f684be78463acede1fff8cbc25500a34daa20f5d4623
Maven SHA1 sidecar: 082cb37b2f66a536f677f1f77cf6c47ddae4f0cf (一致)
arm64 libcef.so SHA256: 9f57d15af50f38d2fe4a25f1e6de3873f1090471045bde9b0bb1e19735bd5b8f
x86_64 libcef.so SHA256: e8ce70f5cadc5fea429c20aff7cc90adf4b1342a4f5d2f0b72a72853adf410f7
readelf --dyn-syms -W (arm64):
  2876: 00000000032747b8 324 FUNC GLOBAL DEFAULT 18 JNI_OnLoad
  NativeChildProcessService_onCreate: NOT FOUND
nm -D -C (arm64):
  00000000032747b8 T JNI_OnLoad
  NativeChildProcessService_onCreate: NOT FOUND
x86_64: NativeChildProcessService_onCreate NOT FOUND (両ツール)
AAR Manifest: Java sandbox services=40, native services=0
--require-native-export: exit 2 (正しいNo-Go拒否)
```

[`official-0.9.4-audit.json`](official-0.9.4-audit.json) に実測結果。
AAR SHA256は自分で計算した固定値。Maven `.sha256` は404で、上流署名検証とは異なる。
AArch64 ELF64 shared objectを確認。NEEDEDはAndroid system librariesで、
`libandroid.so`, `libdl.so`, `libc.so` 等。API 37のweak Native Service API解決、
loaderからの入口解決、binder互換性は実機未確認。

## 4. Test matrix

| 項目 | 結果 / 証拠 |
|---|---|
| 固定ソース33ファイル取得・SHA256照合 | ローカル成功 |
| export-onlyパッチ `git apply --check` | v0.9.4相当の取得ソースで成功。GN/compile/linkは未試験 |
| ELF export gate unit tests | 4テスト成功。UND/local/hidden/object/mangled/類似名を拒否 |
| 公式AAR arm64 / x86_64監査 | 取得・監査成功、Native exportゲートは両方FAIL |
| Javaコード/Manifest/Gradle/既存CI不変 | baselineとの差分なし |
| Javaビルド・単体テスト・API 36 emulator | baseline [run 38348032541](https://github.com/linkalls/velocity-browser/actions/runs/38348032541) のsuccessをGitHub APIで確認。今回の再実行結果はPRのCI参照 |
| 新規audit-only GitHub Actions | workflow追加。実行結果はPR/Actions参照。緑でもnative成功を意味しない |
| 新規native engine/AAR/APK・merged Manifest | NOT BUILT / NOT TESTED |
| API 37 native emulator / Pixel 7 Pro | NOT TESTED |
| HTML/JS、isolated UID、cross-site、renderer回復 | NOT TESTED |

既存Java CIはPRの `experiments/chromium/**` 変更で引き続き起動する。
新workflowは監査資料だけをuploadし、native APKを生成・公開しない。
手動の `require_native_export=true` で欠落をハード失敗にできる。

## 5. Safety / Android lifecycle

- Java版/mainのremote branchを変更しない。native flavorやapp ID変更も行わない。
- WebView/Gecko/WPE fallback、sandbox無効化、Java別名serviceは追加していない。
- API 37 headerのABIは `void(ANativeService*)`。onCreateでcallbacks登録、onBindでnative AIDL binderとrenderer thread作成、onUnbindでtoken削除・最後のservice参照解放、onRebind/onDestroyは固定upstreamでは空処理。
- Binder返却時の `AIBinder_incStrong`、caller PID/class check、setupConnection、FD duplication/registration、clean exit通知・forceKillをupstreamが実装。変更・無効化していない。CEFとの実際のIPC/終了/再起動は未検証。
- Android `PackageManager.java` のlibrary propertyは完全な `lib*.so` 名、function propertyは入口名。`ServiceInfo.FLAG_NATIVE_SERVICE` はisolatedProcessがなければ無視される。OS feature flagsとnative zygote対応も実機で確認する必要がある。
- 固定Chrome Manifestは40 Java＋**2 native**（zygote/non-zygote）、isolated=true、exported=false、CHILD_SERVICE permissionを使用。WebViewのnative **1件**はexternal/exported=true。独立ブラウザにWebViewの外部service属性を無条件に持ち込まない。
- 同じsandbox process名を両方式が使うのはupstream設計だが、service選択・bind flags・slot count・zygote挙動を一致させる必要がある。現在はJava版のみ。
- 未解決のsecurity検証: native childの実UID/isolated UID、caller binding拒否、外部appからの起動不可、サイト分離、renderer crash recovery。静的export成功でも安全性を保証しない。
- 監査artifactは公開ソースhash、公開AAR Manifest/ELF情報のみ。閲覧ログ・URL・cookieは収集しない。

Android17 frameworks/baseのproperties/ServiceInfo/ActiveServicesとChromium固定headerは照合したが、
OS側native loader/lifecycle実装の全経路は未調査。存在しなかった
`core/java/android/app/NativeService.java` と `core/jni/android_app_NativeService.cpp` を
実装根拠として引用しない。ここもGo条件として残す。

## 6. Performance

**NOT TESTED。** 起動・CPU・PSS・FPS改善を主張しない。
比較条件と空の結果表は [performance.md](performance.md)。

## 7. Next blockers / Go条件

1. 400 GB以上・RAM32 GB以上のLinux build hostで固定Chromium/CEF/DEPSを取得する。標準hosted runnerでengine buildしたように見せない。
2. export専用パッチを適用し、Cefrium専用のVMなしstrong initialization hookとJNI依存対応を実装・レビューする。既存JNI_OnLoad経路は保つ。
3. `gn desc`/link mapでreal service objectとhookを確認し、同一ビルドのJava/resources/AARを生成。dynamic symbolを定義済みFUNCとして検査し、API 37 loaderで解決する。
4. OS native loader/zygote経路、CEF IPC/FD/sandboxと終了回復を確認。matching AAR checksum、対応ソース・LGPL改変表示・NOTICEを準備する。
5. ここでGo再判定。通った後のみ別applicationId/flavorとChrome固定テンプレートのManifest、API37選択、merged APK auditを追加する。
6. API37 emulator/Pixelで実ページ・JS・isolated UIDを確認して初めて検証用native APKを配布する。

## 8. Deliverable

このブランチのPhase Aパッチ・ソースlock・監査ツール・audit-only CI・docs。
export-onlyパッチは検討用で、native Service実装完成を意味しない。
**Native APK、独自AAR、SBOM付きバイナリ、実機成功の証拠はない。**
作業PRはJava実験ブランチ向けdraftとし、Java/mainへ自動mergeしない。
