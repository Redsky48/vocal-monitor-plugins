// Stand-alone settings so this module can be consumed by daw or
// slim via Gradle composite-build (matches :shared's pattern).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "shared-ui"

// shared-ui depends on :shared — composite-include that build so
// `project(":shared")` resolves to the sibling module's artefact.
includeBuild("../shared")
