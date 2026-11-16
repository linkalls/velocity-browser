pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Use the official Maven repository for pinned Cefrium releases.
        maven("https://maven.cefrium.com") {
            content { includeGroup("com.cefrium") }
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.cefrium.com") {
            content { includeGroup("com.cefrium") }
        }
    }
}
rootProject.name = "VelocityChromium013Experiment"
include(":app")

// Disposable Android audio-focus fixtures are never part of normal Preview builds.
if (providers.gradleProperty("audioFocusProbe").orNull == "true") include(":audio-probe")
