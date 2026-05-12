// Convolver (JS) — 256-tap FIR convolution implemented in pure JavaScript.
// Same DSP as the native sibling plugin "Convolver Native" — installs both
// and A/B the two to feel the speed gap between the Rhino interpreter and
// pre-compiled DEX.
//
// Per-sample math: 256 multiply-adds + 1 wrap-around index decrement. At
// 44.1 kHz that's ~11 million ops/sec just from this one plugin — Rhino's
// interpreter can sustain it but barely; add a second instance and the
// preview re-render visibly slows.

function ConvolverJsProcessor() {
  // Pre-compute the impulse response — decaying noise burst seeded from a
  // deterministic LCG so this plugin and the native sibling produce
  // identical IRs sample-for-sample.
  var TAPS = 256;
  this.ir = new Array(TAPS);
  var seed = 1;
  var sum = 0;
  for (var i = 0; i < TAPS; i++) {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    var noise = (seed / 0x7fffffff) * 2 - 1;
    this.ir[i] = noise * Math.exp(-i / 80);
    sum += this.ir[i] < 0 ? -this.ir[i] : this.ir[i];
  }
  // Normalize so |sum(ir)| ≈ 1 — output level stays sane regardless of
  // the random IR shape.
  for (var i = 0; i < TAPS; i++) this.ir[i] /= sum;

  this.history = new Array(TAPS);
  for (var i = 0; i < TAPS; i++) this.history[i] = 0;
  this.historyIdx = 0;
  this.taps = TAPS;
}

ConvolverJsProcessor.parameterDescriptors = [
  { name: 'mix', label: 'Mix', defaultValue: 0.4, minValue: 0, maxValue: 1 }
];

ConvolverJsProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0];
  var output = outputs[0][0];
  if (!input || !output) return true;

  var mix = parameters.mix[0];
  var ir = this.ir;
  var history = this.history;
  var taps = this.taps;
  var hIdx = this.historyIdx;

  // Hot loop. 256 mac per sample × 4096 samples per block = ~1M ops per
  // block, ~24× per second of audio. Pure JS pays the per-op bytecode
  // dispatch cost on every one.
  for (var i = 0; i < input.length; i++) {
    history[hIdx] = input[i];
    var y = 0;
    var idx = hIdx;
    for (var k = 0; k < taps; k++) {
      y += ir[k] * history[idx];
      idx--;
      if (idx < 0) idx += taps;
    }
    hIdx = (hIdx + 1) % taps;
    output[i] = input[i] * (1 - mix) + y * mix;
  }

  this.historyIdx = hIdx;
  return true;
};

registerProcessor('convolver-js', ConvolverJsProcessor);
