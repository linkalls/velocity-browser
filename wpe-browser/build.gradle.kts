plugins { alias(libs.plugins.android.application) }

android {
    namespace = "dev.velocity.wpe"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.velocity.browser.wpe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-wpe-preview"
    }
    buildTypes {
        debug {
            // First-stage Pixel 7 Pro physical-device experiment.
            // WPE bundled native libraries can make universal APKs extremely large.
            ndk { abiFilters += "arm64-v8a" }
        }
        release {
            isMinifyEnabled = false
            ndk { abiFilters += "arm64-v8a" }
        }
    }
    packaging {
        // Upstream WPE Android v0.3.3 minibrowser requires extracted native .so.
        jniLibs.useLegacyPackaging = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // IMPORTANT: 0.3.3 uses WPEView/WPEViewClient/WPEChromeClient.
    // Upstream main uses WebView/WebViewClient/WebChromeClient instead.
    implementation("org.wpewebkit.wpeview:wpeview:0.3.3")
    testImplementation(libs.junit)
}
