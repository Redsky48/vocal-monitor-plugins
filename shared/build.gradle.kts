// Vocal Monitor — shared cross-platform core.
//
// Pure Kotlin/JVM module: audio engine, graph data model, DSP
// primitives, plugin interface stubs.  NO Android dependencies, NO
// UI framework — depends only on Kotlin stdlib + a handful of
// platform-neutral libs.  Consumed by:
//   - vocal-monitor-slim    (Android app)       via composite build
//   - vocal-monitor-daw     (Compose Desktop)   via composite build
//   - tools/test-app        (Swing harness)     classpath only
//
// Any future Vocal Monitor surface (web, plugin auth, CLI tools)
// just adds another consumer here.  Keep this module free of UI
// and platform-specific code — split into :shared-android /
// :shared-desktop sibling modules if such code becomes necessary.

plugins {
    kotlin("jvm") version "2.0.20"
    `java-library`
    `maven-publish`
}

group = "com.vocalmonitor"
version = "0.1.0"

// Repositories declared in settings.gradle.kts (FAIL_ON_PROJECT_REPOS
// is on so any block here would conflict — also forbidden when this
// module is composite-included by daw/slim which set the same flag).

dependencies {
    // Kotlin stdlib comes with the plugin.

    // Coroutines for the graph-engine StateFlows.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // JSON for chain-preset / effect-state serialisation.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Rhino JS engine — same as slim's `rhino-android` but the
    // standard Mozilla build (the Android fork adds DEX hooks we
    // don't need on the JVM).  Used by JsPluginEngine + NativeDspHost
    // to host the JavaScript-engine plugin family.
    api("org.mozilla:rhino:1.7.14.1")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

// Plugin stub interfaces ship as Java sources so a plugin .java file
// (still written against the Java contract) can both COMPILE against
// the same types and be LOADED at runtime through a classloader that
// already has them defined.  No Kotlin wrappers — keeps the plugin
// SDK 1:1 with what's already documented in PLUGIN_UI_API.md.
sourceSets["main"].java.srcDirs("src/main/java")

// Publish sources + the regular jar so consumers (slim, daw, third-
// party apps) can see the source on demand — Kotlin in particular
// benefits from this for inline functions.
java {
    withSourcesJar()
}

publishing {
    publications {
        // `mavenJava` is the convention name; consumers reference
        // this artefact as `com.vocalmonitor:shared:<version>`.
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("Vocal Monitor — shared cross-platform core")
                description.set("Pure Kotlin/JVM audio engine, graph data model, DSP " +
                    "primitives and plugin interface stubs shared between " +
                    "the slim Android app, the desktop DAW and any future " +
                    "Vocal Monitor surface.")
                url.set("https://github.com/Redsky48/vocal-monitor-plugins")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
    // No `repositories {}` block on purpose — leaving it empty means
    // `./gradlew publish` is a no-op (safe default).  Consumers fetch
    // via:
    //   - composite build  (git submodule, dev-time)
    //   - publishToMavenLocal  (sibling project on the same machine)
    //   - JitPack  (any consumer worldwide, builds straight from a
    //     git tag — no publish step needed on our side; JitPack
    //     runs `gradlew publishToMavenLocal` against the tagged ref
    //     and serves the resulting artefact)
    // See daw/README.md "Consuming :shared" for the full matrix.
}
