// Chromium/Cefrium is the primary Velocity Android application.
// The original Gecko implementation remains in git history and source files,
// but is intentionally excluded from the root production build.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        // 0.18.0 is initially available on the official Codeberg registry.
        maven("https://codeberg.org/api/packages/cefrium/maven")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://codeberg.org/api/packages/cefrium/maven")
    }
}
rootProject.name = "VelocityBrowser"
include(":app")
project(":app").projectDir = file("experiments/chromium-013/app")
