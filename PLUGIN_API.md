# Plugin API

The host implements a deliberately small subset of the [W3C AudioWorklet](https://developer.mozilla.org/en-US/docs/Web/API/AudioWorklet) contract — enough to run any pure-DSP processor that follows the standard pattern.

## Host globals

Available at the top of every plugin file:

| Global                  | Type     | What it is                                    |
| ----------------------- | -------- | --------------------------------------------- |
| `sampleRate`            | `Number` | Engine sample rate (44100 or 48000)           |
| `AudioWorkletProcessor` | function | Stub constructor — used by `instanceof` only  |
| `registerProcessor`     | function | Register a processor class under a name       |

That's it. No `currentFrame`, no `currentTime`, no `MessagePort`. If you find yourself reaching for one, the algorithm probably belongs in the host, not the plugin.

## Minimal plugin

```javascript
function MyProcessor() {
  // Constructor — allocate any state you need to persist across blocks.
  this.phase = 0;
}

// Static array of parameter descriptors. Each entry gets a slider in the
// app's UI, auto-generated from these min/max/default values.
MyProcessor.parameterDescriptors = [
  { name: 'rate',  label: 'Rate (Hz)', defaultValue: 5,   minValue: 0.1, maxValue: 20 },
  { name: 'depth', label: 'Depth',     defaultValue: 0.5, minValue: 0,   maxValue: 1  }
];

// process() runs once per audio block. inputs[0][0] is the mono input
// channel; outputs[0][0] is the mono output channel to fill. Both are
// plain Arrays of Numbers, same length, ~1024 samples.
MyProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input  = inputs[0][0];
  var output = outputs[0][0];
  if (!input || !output) return true;   // host gave us silence — bail.

  var rate  = parameters.rate[0];        // k-rate: one value per block.
  var depth = parameters.depth[0];
  var phaseInc = (2 * Math.PI * rate) / sampleRate;

  for (var i = 0; i < input.length; i++) {
    var lfo = 1 - depth * 0.5 * (1 - Math.cos(this.phase));
    output[i] = input[i] * lfo;
    this.phase += phaseInc;
    if (this.phase > 2 * Math.PI) this.phase -= 2 * Math.PI;
  }

  // Return true to keep the processor alive. false would tell the host
  // to remove it — there's no good reason to do that in a vocal effect.
  return true;
};

registerProcessor('my-processor', MyProcessor);
```

## `parameterDescriptors`

Always a plain array (not a `static get` — Rhino interpreter can't parse that). Each descriptor:

| Field          | Type     | Required | Notes                                |
| -------------- | -------- | -------- | ------------------------------------ |
| `name`         | `String` | yes      | Parameter id; used by `parameters[<name>][0]` |
| `label`        | `String` | no       | UI label; defaults to `name`         |
| `defaultValue` | `Number` | no       | Initial value when the user adds the plugin |
| `minValue`     | `Number` | no       | Slider lower bound; defaults to 0    |
| `maxValue`     | `Number` | no       | Slider upper bound; defaults to 1    |

There's no `automationRate` — every parameter is k-rate (one value per block).

## `process(inputs, outputs, parameters)`

- `inputs[0][0]` — mono input channel. Length matches the block size.
- `outputs[0][0]` — mono output channel. **Write to this in-place**. The host reads it back, doesn't allocate a new one each block.
- `parameters[<name>][0]` — the value of parameter `<name>` for this block.

If you don't write to `output[i]`, the host treats that sample as 0.

Return `true` to stay alive.

## State

Anything you put on `this` in the constructor persists across `process()` calls for that processor instance. Use it for:

- Delay buffers (pre-allocate in the constructor — never allocate per-block).
- LFO phase.
- Filter state (one-pole capacitor, biquad delays, etc.).
- Envelope follower state.

Two processors of the same kind in a chain are two independent instances — their `this` does not bleed across.

## What the host does NOT support

- **Multiple inputs / outputs.** `inputs.length === outputs.length === 1`, both mono.
- **Channels other than `[0][0]`.** No stereo.
- **`port.postMessage()` / `MessagePort`.** No main-thread communication.
- **`AudioParam` automation curves.** Parameters are k-rate constants.
- **`currentFrame` / `currentTime`.** Track your own time if you need it (`this.frameCount += input.length`).

## Performance notes

- The audio bridge transports samples as `Float32Array` — indexed access is a real array load, not a property lookup. Loops over `input[i]` / `output[i]` run as fast as compiled JS can go.
- Rhino runs in **compiled mode** (`optimizationLevel = 9`) on Android via `rhino-android` — every function gets JVM bytecode, no interpreter dispatch. Tight DSP loops run roughly 10-20× faster than the original interpreter setup.
- Blocks are **4096 samples** (~93 ms @ 44.1 kHz) — the offline renderer doesn't need realtime granularity, and the larger block amortises bridge overhead 4× versus the old 1024.
- For *really* hot DSP — biquad cascades, long delay lines with feedback — use the **native primitives** below. Per-sample math runs in pure JVM/Kotlin and is roughly 50-200× faster than the same code in JS.

## Native DSP primitives (`host.*`)

The `host` global exposes opaque handles into pre-compiled Kotlin DSP code. Use these for any tight inner loop you'd otherwise write per-sample in JS.

### Biquad filter

```javascript
function MyFilter() {
  this.bq = host.createBiquad('lowpass');   // 'lowpass' | 'highpass' | 'bandpass'
}
MyFilter.prototype.process = function (inputs, outputs, parameters) {
  host.biquadSetLowpass(this.bq, /* freq */ 800, /* q */ 0.707);
  host.biquadProcess(this.bq, inputs[0][0], outputs[0][0]);
  return true;
};
```

API:
- `host.createBiquad(type)` → handle (integer)
- `host.biquadSetLowpass(handle, freqHz, q)` / `biquadSetHighpass(...)` / `biquadSetBandpass(...)`
- `host.biquadProcess(handle, inputFloat32, outputFloat32)` — fills output in-place. Output may equal input (in-place processing).

Filter state (`z⁻¹`, `z⁻²`) persists across blocks, so re-configuring the coefficients per block doesn't click.

### Feedback delay line

```javascript
function MyDelay() {
  this.delay = host.createDelayLine(/* max ms */ 2000);
}
MyDelay.prototype.process = function (inputs, outputs, parameters) {
  host.delayProcess(
    this.delay,
    inputs[0][0], outputs[0][0],
    /* time ms */ 350,
    /* feedback */ 0.4,
    /* mix */ 0.5,
  );
  return true;
};
```

API:
- `host.createDelayLine(maxMs)` → handle. Buffer is pre-allocated; subsequent `delayProcess` calls can use any time ≤ maxMs.
- `host.delayProcess(handle, input, output, timeMs, feedback, mix)` — single-tap feedback delay with mix.

### LFO

```javascript
function MyTremolo() {
  this.lfo = host.createLfo('sine', /* rate Hz */ 4);
  // Scratch buffer for the LFO output. Sized to match the engine's block.
  // We re-allocate on first process() once we know the block length.
  this.lfoBuf = null;
}
MyTremolo.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!this.lfoBuf || this.lfoBuf.length !== input.length) {
    this.lfoBuf = new Float32Array(input.length);
  }
  host.lfoSetRate(this.lfo, parameters.rate[0]);
  host.lfoBlock(this.lfo, this.lfoBuf);
  // The LFO buffer is now filled with values in [-1..1]. JS can do the
  // sample-by-sample modulation (cheap) or pass it to other primitives.
  for (var i = 0; i < input.length; i++) {
    output[i] = input[i] * (1 + parameters.depth[0] * this.lfoBuf[i]);
  }
  return true;
};
```

API:
- `host.createLfo(type, rateHz)` → handle. type is `'sine'`, `'triangle'`, `'saw'`, or `'square'`.
- `host.lfoSetRate(handle, rateHz)`
- `host.lfoBlock(handle, outputFloat32)` — writes `output.length` LFO samples into the buffer, advancing phase.

### When to use native primitives

Use them when:
- Your plugin is a filter, delay, or modulation effect.
- You'd otherwise be writing a per-sample inner loop in JS.
- You need to fit several instances of the same plugin into one chain without dropping samples.

Skip them when:
- Your DSP is something exotic the host doesn't expose (rectifier, pitch tracker, custom envelope detector). Write it in JS; it'll still be fast enough thanks to Rhino-compiled mode.
- You're prototyping. Pure JS is the shortest path; you can swap in primitives later if profiling shows you need them.

See [`plugins/filter/fast-eq/`](plugins/filter/fast-eq/) for a complete reference plugin using `host.createBiquad`.
