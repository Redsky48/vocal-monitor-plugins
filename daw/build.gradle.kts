// Vocal-Monitor DAW — Compose Desktop application.
//
// Single-module Kotlin/JVM project that shares Kotlin source files
// with the slim Android app via direct file copy (the audio engine,
// graph data model, DSP primitives and the AudioGraphSheet composable
// all live as pure Kotlin in slim/app/src/main/kotlin/com/vocalmonitor
// and have NO Android dependencies — see ARCHITECTURE.md in daw/).
//
// Two platform adapters live alongside (com.vocalmonitor.desktop.*):
//   - DesktopPluginEngine: URLClassLoader replacement for Android's
//     DexClassLoader so the .java sources of the 84 plugins compile +
//     load on the JVM.
//   - JavaSoundIO: TargetDataLine/SourceDataLine replacement for
//     Android's AudioRecord/AudioTrack.
//
// Build + run:
//   cd daw
//   ./gradlew run                  (Linux/macOS)
//   gradlew.bat run                (Windows)
//
// Or open as a Gradle project in IntelliJ / Android Studio and use
// the "Main" run configuration.

plugins {
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    // Compose Desktop's plugin already creates a `run` task — adding
    // the standard `application` plugin would conflict.
}

group = "com.vocalmonitor"
version = "0.1.0"

// Repositories declared in settings.gradle.kts (FAIL_ON_PROJECT_REPOS
// is on — duplicating them here is a build-time error).

dependencies {
    // Cross-platform shared core: graph data model, audio engine, DSP
    // primitives, plugin interface stubs.  Single source of truth —
    // slim Android app consumes the same module via composite build.
    implementation("com.vocalmonitor:shared")

    // Cross-platform shared Compose UI — AudioGraphSheet + friends
    // ported from slim, depended on by both Android and Desktop hosts.
    // Composite-build resolves to the KMP metadata module; Gradle's
    // variant matching picks the `jvm` target for this JVM consumer.
    implementation("com.vocalmonitor:shared-ui")

    // Compose Desktop core (runtime, foundation, ui, ui-graphics +
    // the desktop windowing backend) — material3 + icons-extended
    // come as separate artefacts.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines (used by the shared engine for graph state flows).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

// Plugin interface stubs (PluginCanvas, PluginPaint, etc.) come from
// the :shared module's Java sources — they're on this module's
// classpath via the composite-build dependency above and are visible
// to both the DAW's own code and the dynamically-compiled plugin
// .java files that load through DesktopPluginEngine at runtime.

compose.desktop {
    application {
        mainClass = "com.vocalmonitor.app.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "Vocal Monitor DAW"
            // Native installer formats (.dmg / .msi / .exe / .deb)
            // require MAJOR ≥ 1.  The Gradle project version stays
            // 0.x for "pre-1.0 unstable" semantics; the installer
            // bumps to 1.0.0 on its first cut.
            packageVersion = "1.0.0"
        }
    }
}
