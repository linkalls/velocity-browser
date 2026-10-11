plugins { alias(libs.plugins.android.test) }
android {
    namespace = "dev.velocity.benchmark"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "36.0.0"
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig { minSdk = 26; targetSdk = 37; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildTypes { create("benchmark") { isDebuggable = true; matchingFallbacks += "release" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }
dependencies { implementation(libs.macrobenchmark); implementation(libs.uiautomator); implementation(libs.test.ext) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
