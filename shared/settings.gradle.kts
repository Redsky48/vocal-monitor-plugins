// Stand-alone settings so this module can be consumed by other
// projects via Gradle composite-build:
//
//     // in the consumer's settings.gradle.kts
//     includeBuild("path/to/vocal-monitor-plugins/shared")
//
// and then in the consumer's build.gradle.kts:
//
//     dependencies {
//         implementation("com.vocalmonitor:shared")
//     }
//
// The composite-build wiring substitutes the published-artifact
// coordinate with the local Gradle project, so consumers see the
// shared module as a normal dependency and edits in slim / daw
// recompile incrementally just like a normal multi-module setup.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "shared"
