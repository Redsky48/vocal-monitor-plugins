# Vocal Monitor DAW — Desktop

Compose Desktop DAW that hosts the 84-plugin Vocal Monitor catalogue
and reuses the slim Android app's audio engine, graph data model
and DSP primitives via a **single shared Gradle module**.

## Architecture

```
vocal-monitor-plugins/                ← THIS repo
├─ plugins/                            (84 audio + visual plugins, .java + .dex)
├─ scripts/native-stub/                (legacy stub copy for tools/test-app)
├─ shared/                             ★ cross-platform core (Kotlin/JVM)
│  ├─ build.gradle.kts                 → published as com.vocalmonitor:shared
│  └─ src/main/
│     ├─ kotlin/com/vocalmonitor/audio/    (EffectGraph, DSP, codecs)
│     └─ java/com/vocalmonitor/plugin/     (PluginCanvas, VocalMonitorNativePlugin, …)
├─ tools/test-app/                     (Swing JEP 330 plugin sandbox — unchanged)
└─ daw/                                ★ Compose Desktop application
   ├─ build.gradle.kts                 (depends on com.vocalmonitor:shared)
   ├─ settings.gradle.kts              (includeBuild ../shared)
   └─ src/main/kotlin/com/vocalmonitor/
      ├─ desktop/                      (URLClassLoader plugin engine, JavaSoundIO)
      ├─ ui/                           (Compose Desktop UI — 4b)
      └─ app/Main.kt                   (entry point)

vocal-monitor-slim/                   ← Android app, SEPARATE repo
├─ settings.gradle.kts                 (will includeBuild ../vocal-monitor-plugins/shared)
├─ app/
│  ├─ build.gradle.kts                 (depends on com.vocalmonitor:shared)
│  └─ src/main/
│     ├─ java/com/vocalmonitor/plugin/   (DELETE once slim is migrated — moves to shared)
│     └─ kotlin/com/vocalmonitor/
│        ├─ audio/                       (Android-coupled files stay here)
│        └─ ui/AudioGraphSheet.kt        (Compose UI — Phase 4b)
```

The `:shared` module is **the single source of truth** for:

- Graph data model — `EffectGraph`, `GraphNode`, `GraphEdge`, `NodeId`, `NodeKind`, `EffectKind`
- Audio engine — `EffectGraphEngine`, `EffectGraphCodec`, `EffectHistory`, `EffectState`
- DSP primitives — `BiquadEqualizer`, `Compressor`, `Reverb`, `NoiseGate`, `NoiseFilter`, `PitchCorrector`, `PitchDetector`, `PhaseVocoder`, `Fft`, `Notes`, `BpmDetector`, `KeyDetector`, …
- Plugin runtime — `JsPlugin`, `JsPluginEngine`, `NativeDspHost`, `ChainPreset`
- Plugin interface contract — `VocalMonitorNativePlugin`, `VocalMonitorVisualPlugin`, `PluginCanvas`, `PluginPaint`, `PluginPath`, `PluginStyle`, `BlendMode`, `PluginHost`, `VoiceProfileBus`

Slim, daw and any future Vocal Monitor surface all see **the exact
same bytes** for these types — improvements made on one side
propagate the moment Gradle recompiles.

## Build & run

You need a JDK 17+ on PATH.  The Gradle wrapper handles the rest.

```bash
cd daw
./gradlew run                # Linux/macOS
gradlew.bat run              # Windows
```

First run downloads Gradle 8.9 + Kotlin 2.0.20 + Compose Desktop
1.7.0 (~150 MB).  Subsequent runs are incremental.

## Consuming `:shared` from any project

The `:shared` module is a normal Gradle Kotlin/JVM library, and there
are **four supported consumption paths** depending on how close the
consumer lives to this repo.  All four are git-based — no Maven
Central account, no GPG keys, no manual artefact uploads required
unless you want full public-library status later.

### 1. Git submodule + composite build  (recommended for org-internal projects)

The consumer adds this repo as a submodule, then Gradle pulls the
shared module straight from the source files — incremental compile,
no published artefacts, IDE jump-to-definition works across both
projects.

```bash
cd path/to/my-other-project
git submodule add https://github.com/Redsky48/vocal-monitor-plugins libs/vocal-monitor-plugins
git submodule update --init
```

```kotlin
// my-other-project/settings.gradle.kts
includeBuild("libs/vocal-monitor-plugins/shared")

// my-other-project/build.gradle.kts (or any module's)
dependencies {
    implementation("com.vocalmonitor:shared")
}
```

Updating to the latest shared is `git submodule update --remote` —
no version bump, no re-publish.

### 2. JitPack  (recommended for third-party / public consumers)

[JitPack.io](https://jitpack.io) builds any GitHub/GitLab project on
demand into a Maven artefact — zero work on our side beyond pushing
a git tag.  It runs `gradlew publishToMavenLocal` on the tagged ref
and serves the resulting JAR.

```kotlin
// consumer's settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// consumer's build.gradle.kts
dependencies {
    implementation("com.github.Redsky48:vocal-monitor-plugins:v0.1.0")
}
```

Anyone with this repo's git URL can use it — no auth tokens, no
publishing setup on our side.  Tag a release: `git tag v0.1.0 && git
push --tags`.  JitPack lazy-builds the first time someone references
the new tag.

### 3. `publishToMavenLocal`  (sibling project on the same machine)

For a project that lives next to this repo but isn't a submodule:

```bash
cd vocal-monitor-plugins/shared
./gradlew publishToMavenLocal       # installs to ~/.m2/repository/com/vocalmonitor/shared/0.1.0/
```

```kotlin
// consumer's settings.gradle.kts
dependencyResolutionManagement { repositories { mavenLocal(); mavenCentral() } }

// consumer's build.gradle.kts
dependencies {
    implementation("com.vocalmonitor:shared:0.1.0")
}
```

Bump the version in `shared/build.gradle.kts` and re-publish for each
iteration.  Less ergonomic than composite build for active dev, but
zero per-consumer setup.

### 4. GitHub Packages  (org-internal, behind GitHub auth)

For private artefact hosting via GitHub's built-in Maven repository,
add a `publishing.repositories.maven { ... }` block referencing
`https://maven.pkg.github.com/Redsky48/vocal-monitor-plugins` and a
GitHub Actions workflow that runs `./gradlew publish` on tag push.
Consumers need a GitHub Personal Access Token with `read:packages`.

Skipped here because options 1+2 already cover both the org-internal
and public-consumption cases without the auth overhead.

---

## Slim integration (one-time)

To migrate slim from its current self-contained copy of the audio
files to consuming `:shared`:

1. Add to `slim/settings.gradle.kts`:
   ```kotlin
   includeBuild("../vocal-monitor-plugins/shared")
   ```
   (or absolute path / git submodule, whatever maps to where this
   repo lives on the build machine)
2. Add to `slim/app/build.gradle.kts`:
   ```kotlin
   dependencies {
       implementation("com.vocalmonitor:shared")
   }
   ```
3. Delete the now-duplicated files from slim:
   - `slim/app/src/main/kotlin/com/vocalmonitor/audio/EffectGraph.kt`
   - `slim/app/src/main/kotlin/com/vocalmonitor/audio/EffectGraphCodec.kt`
   - …(all 30 files now under `shared/src/main/kotlin/com/vocalmonitor/audio/`)
   - `slim/app/src/main/java/com/vocalmonitor/plugin/*.java`
4. Slim's `NativePluginEngine.kt` and `JsPluginLibrary.kt` STAY in
   slim — they have Android dependencies (DexClassLoader, Context).
   They live alongside the rest of slim's Android-specific code.

The Compose UI (`AudioGraphSheet.kt`, `NodeEffectCard.kt`, etc.)
**can also** move to a `:shared-ui` sibling module using Compose
Multiplatform — that's Phase 4b's optional extra so DAW reuses
slim's exact composables.

## Status

- **Phase 4a (this commit):** scaffold complete.  Shared module
  resolves, DAW window opens, plugin engine + audio I/O wired,
  smoke-test buttons prove load/play paths.
- **Phase 4b:** port `AudioGraphSheet.kt` over (drop-in copy with
  composite-build sharing), wire the live audio engine, ship a
  usable DAW v0.1.
