// Chromium/Cefrium is the primary Velocity Android application.
// The original Gecko implementation remains in git history and source files,
// but is intentionally excluded from the root production build.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "VelocityBrowser"
include(":app")
project(":app").projectDir = file("experiments/chromium-013/app")
