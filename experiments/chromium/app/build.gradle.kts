plugins {
    id("com.android.application")
    id("com.cefrium")
}
android {
    namespace = "dev.velocity.chromium"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.velocity.browser.chromiumtest"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-cefrium-experiment"
        ndk { abiFilters += "arm64-v8a" }
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
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}
configurations.all {
    // The Chromium AAR bundles a real Guava ListenableFuture implementation.
    exclude(group = "com.google.guava", module = "listenablefuture")
}
dependencies {
    // Full Chromium via an Android CEF port, not the Android OS WebView.
    implementation("com.cefrium:cefrium-sdk:0.9.4")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.browser:browser:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
