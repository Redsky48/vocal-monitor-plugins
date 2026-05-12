# Native plugin API

Native plugins are pre-compiled DEX bytecode shipped from this registry. The app loads them at install time via Android's `DexClassLoader` and dispatches audio through a plain JVM interface — no JavaScript engine, no per-sample bridge. Performance is essentially identical to the app's built-in effects (EQ, compressor) which are also plain Kotlin classes.

**Use a native plugin when:**

- You need real DAW-grade speed (FFT-heavy effects, dense filter networks, long delay lines with feedback nests).
- You want to write your DSP in [Faust](https://faustdoc.grame.fr/) and let the compiler take care of the inner loops.
- You're porting an existing C/C++ DSP module by transcoding it to Kotlin or Java.

**Stick with a JS plugin when:**

- You're prototyping, or your effect is < 100 lines of math (JS + the `host.*` native primitives is usually fast enough).
- You don't want to deal with a build pipeline.

## Contract

Your plugin is a single Java/Kotlin class implementing [`VocalMonitorNativePlugin`](https://github.com/Redsky48/vocal-monitor-slim/blob/main/app/src/main/java/com/vocalmonitor/plugin/VocalMonitorNativePlugin.java):

```java
public interface VocalMonitorNativePlugin {
    void init(int sampleRate);
    String[] parameterNames();
    float parameterMin(String name);
    float parameterMax(String name);
    float parameterDefault(String name);
    String parameterLabel(String name);
    void setParameter(String name, float value);
    void process(float[] input, float[] output);
}
```

Same shape as the JS contract, but bigger blocks (4096 samples) and parameters are pushed in by name between blocks instead of via a `parameters` arg.

## Folder layout

```
plugins/<category>/<plugin-id>/
├── plugin.json
└── (one of)
    ├── <plugin-id>.dsp    # Faust source — CI compiles it
    ├── <plugin-id>.java   # hand-written Java
    └── <plugin-id>.kt     # hand-written Kotlin
```

`plugin.json` adds two fields versus JS plugins:

```json
{
  "id": "fft-eq",
  "name": "FFT EQ",
  "author": "Your Name",
  "version": "1.0.0",
  "description": "30-band linear-phase FFT EQ.",
  "engine": "native",
  "className": "com.example.FftEq",
  "tags": ["filter", "eq"]
}
```

`engine` MUST be `"native"`. `className` is the fully-qualified name of the class inside the compiled DEX.

## Build pipelines

### Path A: Faust source → DEX

For DSP-heavy work where Faust shines (filter networks, modular synth components, anything involving recursive signal flows).

```sh
# 1. Compile .dsp to Java
faust -lang java -cn FftEq -o FftEq.java plugins/filter/fft-eq/fft-eq.dsp

# 2. Adapt to VocalMonitorNativePlugin (see "Faust adapter" below — CI does this automatically)

# 3. Compile to .class with javac targeting Android-compatible bytecode
javac -source 1.8 -target 1.8 FftEq.java

# 4. DEX with d8 (ships with Android SDK)
d8 FftEq.class --output plugins/filter/fft-eq/

# Result: plugins/filter/fft-eq/classes.dex (uploaded as the source URL)
```

### Path B: Kotlin or Java direct

For when you'd rather write the DSP yourself in a sane language.

```sh
# Kotlin
kotlinc fft-eq.kt -d FftEq.jar
unzip -o FftEq.jar -d compiled/
d8 compiled/com/example/FftEq.class --output plugins/filter/fft-eq/

# Java
javac fft-eq.java
d8 FftEq.class --output plugins/filter/fft-eq/
```

Either way, the output is a `classes.dex` (or rename it to `<plugin-id>.dex`) that lives in the plugin folder and is referenced as the `source` URL in `manifest.json`.

## Faust adapter

Faust's `-lang java` backend emits a class with these methods:

```java
public int getNumInputs();
public int getNumOutputs();
public void init(int samplingFreq);
public void instanceInit(int samplingFreq);
public void instanceClear();
public void buildUserInterface(UI ui);
public void compute(int count, float[][] inputs, float[][] outputs);
```

To wrap it as a `VocalMonitorNativePlugin`, our CI generates a small adapter class:

```java
public final class FftEq implements VocalMonitorNativePlugin {
    private final mydsp dsp = new mydsp();
    private final java.util.Map<String, float[]> zones = new java.util.HashMap<>();
    private final java.util.List<String> names = new java.util.ArrayList<>();
    // … params metadata harvested from buildUserInterface in <clinit>

    public void init(int sampleRate) {
        dsp.init(sampleRate);
        dsp.buildUserInterface(new ZoneHarvester(zones, names));
    }
    public String[] parameterNames() { return names.toArray(new String[0]); }
    public void setParameter(String n, float v) { zones.get(n)[0] = v; }
    // …
    public void process(float[] in, float[] out) {
        dsp.compute(in.length, new float[][]{in}, new float[][]{out});
    }
}
```

`ZoneHarvester` is a thin `UI` implementation that records every `vslider` / `hslider` declared in the Faust source and exposes them as named float zones the host can write through. The adapter source lives in [`scripts/faust-adapter-template.java`](scripts/faust-adapter-template.java).

## CI integration (planned)

The validate.yml workflow will be extended to:

1. Detect `*.dsp` files in plugins/.
2. Install Faust + Android SDK build-tools (for `d8`).
3. For each .dsp: compile → adapt → DEX → commit the resulting `classes.dex` back to the PR.
4. Re-run validate-plugins.mjs to confirm the manifest references the produced .dex.

Until that workflow lands, native-plugin authors must produce the `.dex` locally and commit it alongside the source.

## Limits

- DEX files cap at **5 MB** (raised from the JS 1 MB cap, but still bounded so a single plugin can't make catalogue install slow).
- The class loader has access to:
  - `com.vocalmonitor.plugin.VocalMonitorNativePlugin`
  - The standard Java SE / Android SDK (only `java.*` / `javax.*` / `kotlin.*` packages — no `android.*` system services).
- No reflection-based access to the app's other classes. Stay inside `java.lang`, `java.util`, `java.nio`, `kotlin.*`, and the standard math libraries.

## Security note

Native plugins run as in-process Java code with full JVM privileges. The class verifier prevents JNI loading and process forking, but a malicious plugin can still consume CPU / RAM / battery indefinitely. The registry's review process is the front line — every PR is listened to and read before merge. **Never install a native plugin from a registry you don't trust.**
