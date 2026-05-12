# Building a native plugin — exact recipe

This is the **step-by-step compile recipe** for native (DEX) plugins, written
so an AI agent or first-time contributor can follow it without having to read
the rest of the docs first. If you want the *why* behind any of this, read
[NATIVE_PLUGIN_API.md](NATIVE_PLUGIN_API.md) and [ARCHITECTURE.md](ARCHITECTURE.md)
after the first successful build.

Total time: ~15 minutes on a clean machine.

## Prerequisites

| Tool | Why | Install |
|---|---|---|
| JDK 17 | javac targets Android-compatible bytecode | [Microsoft JDK 17](https://learn.microsoft.com/en-us/java/openjdk/download) or any OpenJDK 17 |
| Android SDK build-tools 34.0.0 (or newer) | provides `d8` for JVM → DEX conversion | [Android Studio command-line tools](https://developer.android.com/studio#command-line-tools-only) → `sdkmanager "build-tools;34.0.0"` |

Verify both are on PATH:

```sh
javac -version          # → javac 17.x
d8 --version            # → D8 8.x  (or newer)
```

On Windows the d8 script is `d8.bat`; the standard `build-tools/<ver>/` folder
is usually `C:\Android\build-tools\34.0.0` or `%LOCALAPPDATA%\Android\Sdk\build-tools\34.0.0`.

## The five-step recipe

We'll build a new native plugin called `my-fuzz` under the `distortion`
category. Substitute names freely.

### 1. Scaffold the folder

```sh
mkdir -p plugins/distortion/my-fuzz
cd plugins/distortion/my-fuzz
```

### 2. Write `plugin.json`

Native plugins need two extra fields versus JS plugins: `engine: "native"`
and `className: "<fully.qualified.class.name>"`.

```jsonc
{
  "id":          "my-fuzz",
  "name":        "My Fuzz",
  "author":      "Your Name",
  "version":     "1.0.0",
  "description": "Aggressive transistor fuzz with asymmetric clipping.",
  "tags":        ["distortion", "fuzz"],
  "engine":      "native",
  "className":   "com.example.MyFuzz"
}
```

The folder name (`my-fuzz`), the `id` field, and the resulting `<id>.dex`
filename MUST agree. The `className` is whatever you choose for the Java
class — it has its own namespace.

### 3. Write the Java source

Implement [`VocalMonitorNativePlugin`](https://github.com/Redsky48/vocal-monitor-slim/blob/main/app/src/main/java/com/vocalmonitor/plugin/VocalMonitorNativePlugin.java)
— a tiny 8-method interface. Save as `MyFuzz.java` next to `plugin.json`:

```java
package com.example;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

public final class MyFuzz implements VocalMonitorNativePlugin {

    private float drive = 0.5f;
    private float mix = 1.0f;

    @Override public void init(int sampleRate) {
        // Allocate buffers / state here. Empty for a stateless effect.
    }

    @Override public String[] parameterNames() {
        return new String[] { "drive", "mix" };
    }

    @Override public float parameterMin(String n)     { return 0f; }
    @Override public float parameterMax(String n)     { return 1f; }
    @Override public float parameterDefault(String n) {
        return "drive".equals(n) ? 0.5f : 1.0f;
    }
    @Override public String parameterLabel(String n) {
        return n.substring(0, 1).toUpperCase() + n.substring(1);
    }
    @Override public void setParameter(String n, float v) {
        if ("drive".equals(n)) drive = v;
        else if ("mix".equals(n)) mix = v;
    }

    @Override public void process(float[] input, float[] output) {
        // Inner loop runs at full JVM speed — no JS bridge.
        final int n = input.length;
        final float g = 1f + 30f * drive;
        for (int i = 0; i < n; i++) {
            float x = input[i] * g;
            // Asymmetric tanh — adds 2nd harmonic, fuzzy character.
            float y = x >= 0 ? (float) Math.tanh(x) : (float) Math.tanh(x * 0.7f);
            output[i] = input[i] * (1f - mix) + y * 0.4f * mix;
        }
    }
}
```

Rules the runtime enforces:

- Class must be `public` and have a `public` no-arg constructor.
- `process(float[], float[])` must NOT allocate per call — pre-allocate in
  `init(int sampleRate)` and reuse.
- Stay inside `java.*`, `javax.*`, `kotlin.*`, `com.vocalmonitor.plugin.*`.
  No `android.*`, no JNI, no filesystem, no network.
- Be deterministic — the host renders offline, so the same input must
  produce the same output across runs.

### 4. Compile + DEX

This is the bit that trips people up. Three sub-steps, all run from the
plugin folder (`plugins/distortion/my-fuzz/`):

```sh
# (a) Copy the interface stub into a build/ tree alongside our source.
#     The interface ships in the main app repo — we copy it here only so
#     javac has it on the classpath. Don't commit the build/ tree; it's
#     listed in .gitignore.
mkdir -p build/src/com/vocalmonitor/plugin
mkdir -p build/src/com/example
curl -sL https://raw.githubusercontent.com/Redsky48/vocal-monitor-slim/main/app/src/main/java/com/vocalmonitor/plugin/VocalMonitorNativePlugin.java \
     -o build/src/com/vocalmonitor/plugin/VocalMonitorNativePlugin.java
cp MyFuzz.java build/src/com/example/

# (b) javac — target Java 8 bytecode (ART runs that fine; newer versions
#     work too, but 8 keeps min-api compatible with older Androids the
#     app supports).
javac --release 8 -d build/classes $(find build/src -name '*.java')

# (c) d8 — pack just YOUR class into a DEX. The interface is provided by
#     the app at runtime via the parent classloader, so we leave it out
#     of the DEX (it just needs to be on the --classpath so d8 can
#     resolve symbols).
d8 build/classes/com/example/MyFuzz.class \
   --classpath build/classes \
   --min-api 26 \
   --output .

# (d) Rename the produced classes.dex to match the plugin id.
mv classes.dex my-fuzz.dex
rm -rf build
```

Result: `plugins/distortion/my-fuzz/my-fuzz.dex` (typically 2–5 KB for a
small plugin). Verify the DEX header:

```sh
head -c 8 my-fuzz.dex | od -c | head -1
# → 0000000   d   e   x  \n   0   3   8  \0
```

A valid DEX magic is `dex\n035` through `dex\n041`. Anything else means d8
didn't run.

### 5. Validate + commit

Back at the registry repo root:

```sh
node scripts/validate-plugins.mjs        # catches missing className,
                                         # bad DEX magic, oversized files
git add plugins/distortion/my-fuzz/
git commit -m "Add my-fuzz native plugin"
```

Open a PR. CI re-runs the validator and rebuilds `manifest.json`. Once
merged, every app pointed at this registry sees the new plugin on next
refresh.

## Common mistakes

| Symptom | Cause |
|---|---|
| `error: package com.vocalmonitor.plugin does not exist` | You skipped step 4(a). javac needs `VocalMonitorNativePlugin.java` either on classpath or in the source tree. |
| `D8: file does not start with the magic bytes` | You passed a `.java` to `d8`. d8 takes `.class` files — run javac first. |
| App shows "Plugin not loaded" after install | The `className` in `plugin.json` doesn't match the class's actual fully-qualified name. Open the `.dex` with `javap -p -classpath my-fuzz.dex` to verify the FQN. |
| `Writable dex file is not allowed` at install time | Android 14+ rejects writable DEX. The app calls `File.setReadOnly()` before loading — if you see this error, you're running an old app build (≤ v7). Update to v8+. |
| DEX file > 5 MB | The app caps at 5 MB per plugin. Trim dependencies or split the plugin into multiple smaller ones. |
| Plugin works in isolation but crashes when stacked | You probably allocated inside `process()`. Move all `new` calls into `init()`. |

## What this looks like end-to-end

Reference implementation: [`plugins/filter/convolver-native/`](plugins/filter/convolver-native/) —
a 256-tap FIR convolver written in 70 lines of Java, compiled to a 2 KB DEX
that runs ~30-80× faster than the JS sibling. The `Convolver.java` source
is checked in alongside the DEX so you can copy-paste the build flow.

## For AI agents specifically

If you're an LLM agent helping a user contribute a plugin:

1. Always check the user has both `javac` (JDK 17) and `d8` available
   before suggesting compilation. Use the `--version` invocations from
   the prerequisites table.
2. Generate the Java source FIRST, get the user to confirm the DSP looks
   right, THEN run javac + d8. Compilation errors are slow to feedback
   because they only surface after the user runs the script.
3. The `--release 8` flag on javac is non-negotiable. Higher targets work
   on modern Android but break for users on API 26-29. Keep it at 8.
4. After producing the `.dex`, always run the header check (`head -c 8 …
   | od -c`) and confirm `dex\n0XX\0`. If you can't see the magic in your
   output, the user should re-run d8 with `--verbose`.
5. The `className` in `plugin.json` must match the Java class's FQN
   EXACTLY (package + class). A typo here gives a silent install
   ("Plugin X not loaded") with no actionable error.

A complete back-and-forth where the user says "make me a tape saturation
native plugin" should result in:

- `plugins/lofi/tape-sat/plugin.json` with `engine: "native"` + correct
  className
- `plugins/lofi/tape-sat/TapeSat.java` with the DSP
- `plugins/lofi/tape-sat/tape-sat.dex` compiled locally and verified
- A PR description explaining the algorithm + linking a sound sample
