// Vocal Monitor — shared Compose UI (Compose Multiplatform).
//
// Sibling of :shared (pure-Kotlin audio engine).  Where :shared
// holds the data + DSP, :shared-ui holds the Compose composables —
// AudioGraphSheet, NodeEffectCard, ChainPresetBar, Theme — that
// both slim Android and the desktop DAW render verbatim.
//
// Two targets:
//   - jvm     → consumed by daw/ (Compose Desktop)
//   - android → consumed by slim's app/ (Compose for Android), once
//               slim migrates its `dependencies { implementation }`
//               line over.  Currently disabled because this repo
//               doesn't have the Android SDK plumbing — enable by
//               adding the Android Gradle plugin + sdk.dir to
//               local.properties.
//
// Platform-specific bits (e.g. ComposePluginCanvas) live in
// jvmMain / androidMain via `expect`/`actual`.

plugins {
    kotlin("multiplatform") version "2.0.20"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
}

group = "com.vocalmonitor"
version = "0.1.0"

kotlin {
    jvm()
    // androidTarget()   // ← uncomment once Android SDK is configured
    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            dependencies {
                // :shared comes in via composite-build (settings.gradle
                // .kts includeBuild) under its Maven coordinate.
                api("com.vocalmonitor:shared")
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
        val jvmMain by getting {
            dependencies {
                // Desktop-side platform extras live here.  Currently
                // empty — Skia bindings come transitively through
                // compose.ui's jvm artefact.
            }
        }
    }
}
