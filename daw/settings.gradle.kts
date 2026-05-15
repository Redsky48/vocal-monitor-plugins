// Compose Multiplatform / Compose Desktop plugin repository config.
// Kept at the root of the daw project so the wrapper is self-contained
// and the user can `./gradlew run` without touching their global init.
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

rootProject.name = "vocal-monitor-daw"

// Pull in the shared cross-platform core as a sibling Gradle build.
// Composite build means the consumer sees `:shared` as a normal
// dependency (declared as `implementation("com.vocalmonitor:shared")`
// below) but edits in shared/ recompile incrementally — no need to
// publish artifacts.  Slim consumes the same module the same way.
includeBuild("../shared")
includeBuild("../shared-ui")

