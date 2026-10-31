pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "VelocityChromium013Experiment"
include(":app")

// Disposable Android audio-focus fixtures are never part of normal Preview builds.
if (providers.gradleProperty("audioFocusProbe").orNull == "true") include(":audio-probe")
