plugins {
    id("com.android.application")
    id("com.cefrium")
}
// Override with -PchromiumAbi=x86_64 for CI emulator smoke tests.
val chromiumAbi = providers.gradleProperty("chromiumAbi").orElse("arm64-v8a").get()
require(chromiumAbi == "arm64-v8a" || chromiumAbi == "x86_64") { "Unsupported ABI: $chromiumAbi" }
// A signed preview uses the same key across releases. Secrets are supplied
// via the CI environment; no signing material belongs in source control.
val previewKeystorePath = providers.environmentVariable("VELOCITY_CEF_KEYSTORE_PATH").orNull
val previewSigningPassword = providers.environmentVariable("VELOCITY_CEF_SIGNING_PASSWORD").orNull
val previewCanSign = !previewKeystorePath.isNullOrBlank() && !previewSigningPassword.isNullOrBlank()
// Opt-in R8 experiment: signed Preview remains non-minified by default
// until renderer isolation, lifecycle and app UX are validated.
val velocityMinify = providers.gradleProperty("velocityMinify")
    .orElse("false").get().toBooleanStrict()
// This resource is true ONLY in the disposable x86_64 CI comparison APK.
// Real signed Preview binaries and default builds always ship with false.
val benchmarkFastAttach = providers.gradleProperty("velocityBenchmarkFastAttach")
    .orElse("false").get().toBooleanStrict()
// Experimental APK-only load path: ELF bytes are directly mapped from the
// zip instead of extracted to nativeLibraryDir. Never change signed Preview
// defaults before proving CEF can bootstrap and load pages on-device.
val velocityMmapNative = providers.gradleProperty("velocityMmapNative")
    .orElse("false").get().toBooleanStrict()
val velocityNativePrefetchMiB = providers.gradleProperty("velocityNativePrefetchMiB")
    .orElse("0").get().toInt()
require(velocityNativePrefetchMiB in 0..32 &&
        (velocityNativePrefetchMiB == 0 ||
         (chromiumAbi == "x86_64" && velocityMinify && !previewCanSign && !velocityMmapNative))) {
    "Bounded native ELF prefetch is restricted to unsigned x86_64, R8, legacy JNI benchmarks"
}
require(!velocityMmapNative || (chromiumAbi == "x86_64" && velocityMinify && !previewCanSign)) {
    "Native-mmap experiment is limited to unsigned x86_64 R8 CI builds"
}
require(!benchmarkFastAttach || (chromiumAbi == "x86_64" && velocityMinify && !previewCanSign)) {
    "Early-start benchmark build requires x86_64, R8 release and NO permanent Preview signing key"
}

android {
    namespace = "dev.velocity.chromium"
    // Required for per-build benchmark-only resValue() flags on AGP 9.
    buildFeatures { resValues = true }
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.velocity.browser.chromium013"
        minSdk = 29
        targetSdk = 35
        // Signed builds use a monotonic CI run number, never a mutable constant.
        // Legacy diagnostic/debug builds retain versionCode 1 by default.
        versionCode = providers.gradleProperty("velocityVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("velocityVersionName")
            .orElse("0.14.0-experiment").get()
        ndk { abiFilters += chromiumAbi }
        resValue("bool", "velocity_benchmark_fast_attach", benchmarkFastAttach.toString())
        resValue("integer", "velocity_native_prefetch_mib", velocityNativePrefetchMiB.toString())
    }
    signingConfigs {
        if (previewCanSign) {
            create("velocityPreview") {
                storeType = "pkcs12"
                storeFile = file(requireNotNull(previewKeystorePath))
                storePassword = previewSigningPassword
                keyAlias = "velocity-cefrium"
                keyPassword = previewSigningPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = velocityMinify
            isShrinkResources = velocityMinify
            if (velocityMinify) {
                // Respect Cefrium SDK's published consumer ProGuard rules.
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            }
            if (previewCanSign) signingConfig = signingConfigs.getByName("velocityPreview")
        }
        debug { isMinifyEnabled = false }
    }
    packaging {
        // CEF native shared libraries must be extracted for Android loading.
        jniLibs.useLegacyPackaging = !velocityMmapNative
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    // Full Chromium via an Android CEF port, not the Android OS WebView.
    implementation("com.cefrium:cefrium-sdk:0.14.0")
    testImplementation("junit:junit:4.13.2")
}
