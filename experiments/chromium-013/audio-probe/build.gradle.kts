plugins { id("com.android.application") }
android {
    namespace = "dev.velocity.audiofocusprobe"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.velocity.audiofocusprobe"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "test-only"
    }
    flavorDimensions += "role"
    productFlavors {
        create("player") {
            dimension = "role"
            applicationIdSuffix = ".player"
            resValue("bool", "probe_ducker", "false")
        }
        create("ducker") {
            dimension = "role"
            applicationIdSuffix = ".ducker"
            resValue("bool", "probe_ducker", "true")
        }
    }
    buildFeatures { resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
