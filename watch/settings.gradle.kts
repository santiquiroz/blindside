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

rootProject.name = "blindside-watch"

include(":radar-core")
// Plan 03 creates wear-app in parallel; including a missing directory breaks the build.
if (file("wear-app/build.gradle.kts").exists()) include(":wear-app")
