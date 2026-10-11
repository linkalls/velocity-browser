plugins { alias(libs.plugins.android.application) }
android {
    namespace = "dev.velocity.minimal"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "dev.velocity.minimal"; minSdk = 26; targetSdk = 37; versionCode = 1; versionName = "0.1" }
    buildTypes { release { isMinifyEnabled = true; isShrinkResources = true; ndk { abiFilters += "arm64-v8a" }; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation(libs.geckoview) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
