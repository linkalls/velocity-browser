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
rootProject.name = "VelocityChromium013Experiment"
include(":app")

// Disposable Android audio-focus fixtures are never part of normal Preview builds.
if (providers.gradleProperty("audioFocusProbe").orNull == "true") include(":audio-probe")
