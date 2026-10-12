plugins { alias(libs.plugins.android.application) }
android {
    buildFeatures { buildConfig = true }
    namespace = "dev.velocity.browser"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.velocity.browser"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "0.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk { abiFilters += "arm64-v8a" }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core"))
    implementation(libs.geckoview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext)
    androidTestImplementation(libs.uiautomator)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
