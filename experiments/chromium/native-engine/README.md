# Native renderer preparation — NO-GO / no native APK

This directory contains Phase A source/ABI audits and an **export-only,
unbuilt source patch**, not a working native renderer. See
[feasibility](../../../docs/chromium-javaless/feasibility.md).
The Java app, SDK dependency, app ID, Manifest and Java workflow are unchanged.
Do not install an APK built from the existing project as a native renderer test.

**2026-10-24:** The audit now handles Cefrium's renamed child services and
compares official 0.9.4 and 0.13.0. The latter removes the inherited no-sandbox
settings but still exports no native entry and declares no native service.
See [0.13.0 reassessment](../../../docs/chromium-javaless/cefrium-0.13.0-reassessment.md).

To reproduce the new-version audit:

```bash
python3 experiments/chromium/native-engine/fetch_sources.py \
  --lock experiments/chromium/native-engine/upstream-0.13.0-lock.json \
  --output /tmp/velocity-cefrium013-sources
curl --fail --location --retry 3 \
  https://repo.maven.apache.org/maven2/com/cefrium/cefrium-sdk/0.13.0/cefrium-sdk-0.13.0.aar \
  -o /tmp/cefrium-sdk-0.13.0.aar
python3 experiments/chromium/native-engine/audit.py /tmp/cefrium-sdk-0.13.0.aar \
  --sha256 18f52c00e1e6b75e75668042ad56c1e9e58442cce3800fc4d98d427ac7f40f83 \
  --abi arm64-v8a --abi x86_64 --output /tmp/velocity-cefrium013-audit \
  --require-java-pool --require-native-export
```

The Java pool gate passes (40 declared isolated services), while the native
export gate still exits **2**. `--require-java-pool` validates source XML slot
counts, uniqueness and isolated attributes; it is not runtime sandbox proof.
The old-name count is no longer used for newer SDKs: the audit follows
`SANDBOXED_SERVICES_NAME` metadata with the upstream Chromium default as fallback.
The commands and source patch below remain specific to **0.9.4**.

From the repository root (Python 3.11+, Git, curl, GNU binutils):

```bash
python3 experiments/chromium/native-engine/fetch_sources.py --output /tmp/velocity-native-sources
git -C /tmp/velocity-native-sources/cefrium apply --check \
  "$PWD/experiments/chromium/native-engine/patches/0001-android-native-service-export.patch"
python3 -m unittest discover -s experiments/chromium/native-engine -p '*_test.py' -v
curl --fail --location --retry 3 \
  https://repo.maven.apache.org/maven2/com/cefrium/cefrium-sdk/0.9.4/cefrium-sdk-0.9.4.aar \
  -o /tmp/cefrium-sdk-0.9.4.aar
python3 experiments/chromium/native-engine/audit.py /tmp/cefrium-sdk-0.9.4.aar \
  --sha256 4ec9aac59f2ef2663170f684be78463acede1fff8cbc25500a34daa20f5d4623 \
  --abi arm64-v8a --abi x86_64 --output /tmp/velocity-native-audit --require-native-export
```

The last command currently exits **2**: both official ABIs fail the export gate.
Without `--require-native-export`, zero exit means the **audit** completed;
`audit.json` still records `export_gate: FAIL`, `native_renderer_ready: false`,
`apk: NOT BUILT`, `runtime: NOT TESTED`. The AAR manifest is plain XML;
it is not the final merged APK manifest. No APK audit or device test is claimed.

`upstream-lock.json` records commits and SHA256 of 37 inspected files and the
official AAR. Fetching these files is sufficient to reproduce inspection and
patch applicability, **not** to compile Chromium. Byte hashes provide immutable
comparison with the observed downloads; upstream signature authentication was
not performed. The Maven `.sha1` matched the downloaded AAR; no `.sha256` sidecar
was available, so the SHA256 was calculated locally and pinned here.

## Patch limitations

`0001-android-native-service-export.patch` selects a separate Android export
map with the real upstream entry name. Linux's export map remains unchanged.
The patch was checked against v0.9.4; GN generation, linking, LTO/dead stripping
and dynamic loader resolution **have not been tested**. It does not supply
`NativeInitializationHook`, change the SDK Manifest, or enable native mode.
It must not be used as evidence of Native Service support.

## Continue on a suitable build host

Provision Linux with at least 400 GB free disk and 32 GB RAM (64 GB recommended),
depot_tools and Chromium's pinned Android toolchain. Obtain a complete Android
Chromium checkout at `b859317bf11f6be47f9b7799ec690a0a42a1fb33` (including DEPS),
and place Cefrium commit `1e2367bd80ce762fe75383455b5b1cd7e5e019a5` at `src/cef`.
Use JDK 25 / Gradle 9.7.1 / AGP 9.4.1 / Android SDK 37.1 for the independent
consumer. The source's older build-guide example uses Chromium 150; follow the
locked compatibility file instead.

From the full `src` tree, apply the existing upstream patch set first:

```bash
python3 cef/cefrium_sdk/apply-patches.py
git -C cef apply --check /path/to/0001-android-native-service-export.patch
git -C cef apply /path/to/0001-android-native-service-export.patch
```

Before building, implement and review a Cefrium-specific **strong C++**
`NativeInitializationHook(base::android::LibraryProcessType)` with no JVM
assumption. Set its `ChromeMainDelegateCef`, locale/resource and library-loader
initialization correctly; review every reachable renderer path for JNI calls.
Do not call `JNI_OnLoad` with a fabricated/null JavaVM, or reuse Chrome's delegate
in place of CEF's. Confirm the hook and upstream service translation unit are
linked with `gn desc` and a linker map. No unverified GN toggle is supplied.

The pinned upstream also adds `no-sandbox` in `Cefrium.java:279` and sets
`settings.no_sandbox = true` in `cef_android_jni_bridge.cc:156` and
`cefrium_sdk_jni.cc:3378`. This is an additional security **NO-GO** for the native
experiment. Remove those inherited settings in the separately built experimental
SDK and verify child command lines and sandbox policy. Preserve the existing
Java baseline SDK unchanged; its isolated-process declarations alone do not
establish the effect of these Chromium switches on every sandbox layer.

The existing pinned script supplies its own GN args and builds libcef and the
matching AAR together. Once those source changes are ready, its arm64 command is:

```bash
CEFRIUM_NINJA_JOBS=4 cef/cefrium_sdk/build-all-variants.sh arm64 codecs
readelf --dyn-syms -W out/Official_android_arm64_codecs/libcef.so
nm -D -C out/Official_android_arm64_codecs/libcef.so
```

Record actual `args.gn`, compiler/toolchain revisions, build log, link map,
AAR SHA256 and source/patch SHA256. These are **future commands, not a build
performed in this task**. The script's AAR includes Java bindings/resources from
the same build; do not inject this `.so` into the official AAR. Give it a distinct
`com.velocity.experimental` coordinate and preserve LGPL corresponding source,
modification notices and CEF/Chromium/codec notices before distribution.

Only after export, initialization and ABI compatibility pass may Phase C add
an independent `dev.velocity.browser.chromiumjavaless` variant, minSdk 37,
early fixed renderer selection and a reviewed Chrome-style service template.
Then enforce merged APK properties/library/UID isolation, JS/page rendering,
renderer recovery and API 37 device tests. The new audit workflow does not
pretend to build an engine on an undersized hosted runner.
