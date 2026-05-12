# How plugins actually run

Three runtimes share the plugin slot in the Vocal Monitor app. Knowing which
one your plugin uses is the difference between *"works fine"* and *"this
plugin pegs CPU as soon as I open a second instance."*

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Vocal Monitor app                              │
│                                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐   │
│  │  JS engine   │  │  Native DSP  │  │  Native plugin engine    │   │
│  │  (Rhino)     │  │  primitives  │  │  (DexClassLoader)        │   │
│  │              │  │   (host.*)   │  │                          │   │
│  │   .js src    │  │   exposed    │  │   .dex bytecode          │   │
│  │              │  │   to JS      │  │                          │   │
│  └──────┬───────┘  └──────┬───────┘  └────────────┬─────────────┘   │
│         │                 │                       │                  │
│         ▼                 ▼                       ▼                  │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │           EffectGraphEngine — routes per node              │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

## Layer 1 — JS plugins (most common)

**Use when:** Your plugin is < 100 lines of math, you want to ship in minutes,
or your DSP isn't a tight per-sample loop.

- Source: `<id>.js`, plain ES5 prototype JavaScript.
- Loaded by Mozilla [Rhino](https://github.com/mozilla/rhino) in interpreter
  mode. Every `+`, `*`, `[i]` runs as a bytecode-dispatch step.
- Bridge: per-sample read/write across the JS↔Kotlin boundary via Rhino's
  `NativeArray` property API.
- Block size: 4096 samples (~93 ms @ 44.1 kHz).
- Speed: usable for amplitude-mod effects (tremolo, chorus, ring mod) and
  most filters. Falls over when the inner loop has dozens of mac per sample.

See [PLUGIN_API.md](PLUGIN_API.md), `plugins/modulation/tremolo/` for an
example.

## Layer 2 — JS plugins with `host.*` primitives

**Use when:** Your DSP is a textbook biquad / delay / LFO and you'd rather
not write the per-sample loop in JS.

The host exposes pre-compiled DSP modules through a `host` global. Your JS
plugin creates a primitive once (per instance), then per-block calls
`host.biquadProcess(handle, input, output)` to dispatch the whole block to
Kotlin. The per-sample inner loop runs as JVM bytecode, not interpreted JS.

Same JS source format as Layer 1 — you just import the primitives in your
constructor and call them from `process()`. Speed: roughly 10–20× faster
than the equivalent pure-JS inner loop. See `plugins/filter/fast-eq/` for
the reference plugin.

## Layer 3 — Native plugins (DEX)

**Use when:** Your DSP doesn't fit Layer 2 (FFT-heavy effects, dense filter
networks, long convolutions, custom envelope detectors) and you need every
cycle.

- Source: Java/Kotlin → `.class` → `.dex` via `d8`. CI will eventually do
  this automatically from Faust `.dsp` sources; right now contributors run
  the toolchain locally and commit the `.dex`.
- Plugin class implements
  [`VocalMonitorNativePlugin`](https://github.com/Redsky48/vocal-monitor-slim/blob/main/app/src/main/java/com/vocalmonitor/plugin/VocalMonitorNativePlugin.java).
- Loaded at install time via Android's `DexClassLoader`. Parented to the
  app's classloader so it can reference the interface.
- No JS bridge at all — `process(float[] in, float[] out)` is a plain
  JVM method call. Per-sample math runs at the same speed as the app's
  built-in effects (EQ, compressor).
- Block size: 4096 samples.
- Speed: roughly 50–200× faster than the same algorithm in Layer 1.

See [NATIVE_PLUGIN_API.md](NATIVE_PLUGIN_API.md) for the full contract and
the build pipeline (Faust or hand-written Kotlin/Java). The reference plugin
pair `plugins/filter/convolver-js/` + `plugins/filter/convolver-native/`
implements *identical* DSP — a 256-tap FIR convolution — in both layers, so
you can install both and A/B the speed gap directly.

## Picking a layer

```
                Is it < 100 lines of math?
                ┌──────┐
            ┌── │ yes  │ ──→  Layer 1 (plain JS)
            │   └──────┘
            │   ┌──────┐                              Is the inner loop
            └── │ no   │ ──→  Is it standard DSP? ──→ dozens of ops/sample?
                └──────┘            │  no                         │ yes
                                    ▼                             ▼
                              Layer 1 (still)              Layer 3 (DEX)
                              ───────                      ───────
                                    │  yes
                                    ▼
                              Layer 2 (host.* primitives)
```

When in doubt: ship Layer 1 first. Promote to Layer 2 or Layer 3 when
profiling shows it. The plugin's user-visible behaviour is identical across
all three — only the speed differs.

## What the app guarantees

- All three layers see the same `init(sampleRate)` lifecycle.
- All three accept k-rate (one value per block) parameter updates.
- All three render **offline** — preview / save build the output through the
  full effect graph in one pass, so a slow plugin lengthens the
  re-render time rather than dropping samples.
- All three are sandboxed at the API level: no plugin can touch the
  filesystem, network, or microphone directly. Layer 3 runs as in-process
  JVM code so it could in theory consume unbounded CPU — the registry's
  review process is the gate.
