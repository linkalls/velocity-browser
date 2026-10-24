plugins {
    id("com.android.application")
    id("com.cefrium")
}
// Override with -PchromiumAbi=x86_64 for CI emulator smoke tests.
val chromiumAbi = providers.gradleProperty("chromiumAbi").orElse("arm64-v8a").get()
require(chromiumAbi == "arm64-v8a" || chromiumAbi == "x86_64") { "Unsupported ABI: $chromiumAbi" }
android {
    namespace = "dev.velocity.chromium"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.velocity.browser.chromium013"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.2-cefrium-013-experiment"
        ndk { abiFilters += chromiumAbi }
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }
    packaging {
        // CEF native shared libraries must be extracted for Android loading.
        jniLibs.useLegacyPackaging = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    // Full Chromium via an Android CEF port, not the Android OS WebView.
    implementation("com.cefrium:cefrium-sdk:0.13.0")
    testImplementation("junit:junit:4.13.2")
}
