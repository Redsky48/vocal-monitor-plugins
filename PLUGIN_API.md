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

- Plain-array bridge has overhead — keep `process()` loops tight, no per-sample function calls if you can avoid it.
- ~1024 samples per block ≈ 23 ms @ 44.1 kHz, so a plugin has well under that to stay realtime-safe even on mid-range Android.
- The host renders **offline** (save / preview), so per-block latency doesn't compound — your effect doesn't have to be realtime, just deterministic.
