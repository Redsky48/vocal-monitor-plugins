// Fast EQ — reference plugin demonstrating the host.* native DSP primitives.
//
// Two biquad filters cascaded: a high-pass to clean low-end rumble, then a
// low-pass to soften top-end harshness. Both filters live in JVM-native code
// (host.createBiquad returns an opaque handle into a Kotlin DSP engine);
// per-sample math runs at full Java speed, the JS only orchestrates.
//
// Compare with a pure-JS biquad: a hand-coded JS version of the same two
// filters runs ~50-100× slower because every sample crosses the JS bytecode
// dispatch loop. Here, process() makes just two calls per block — one per
// filter — and the inner sample loop is native.

function FastEqProcessor() {
  // host is a global injected by the engine. createBiquad returns an
  // integer handle; the Kotlin side owns the actual filter state.
  this.hp = host.createBiquad('highpass');
  this.lp = host.createBiquad('lowpass');
  // Scratch buffer between the two filter stages. Allocated once on the
  // Kotlin side via the same typed-array bridge process() uses for in/out.
  this.scratch = null;
}

FastEqProcessor.parameterDescriptors = [
  { name: 'lowCut',  label: 'Low Cut (Hz)',  defaultValue: 80,   minValue: 20,   maxValue: 800 },
  { name: 'highCut', label: 'High Cut (Hz)', defaultValue: 8000, minValue: 1500, maxValue: 18000 },
  { name: 'q',       label: 'Q',             defaultValue: 0.707, minValue: 0.3, maxValue: 4 }
];

FastEqProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0];
  var output = outputs[0][0];
  if (!input || !output) return true;

  var lowCut = parameters.lowCut[0];
  var highCut = parameters.highCut[0];
  var q = parameters.q[0];

  // Re-configure both filters per block. setLowpass / setHighpass don't
  // reset the state, so even fast modulation doesn't click.
  host.biquadSetHighpass(this.hp, lowCut, q);
  host.biquadSetLowpass(this.lp, highCut, q);

  // Stage 1: input -> output via highpass.
  host.biquadProcess(this.hp, input, output);
  // Stage 2: output -> output via lowpass (in-place).
  host.biquadProcess(this.lp, output, output);

  return true;
};

registerProcessor('fast-eq', FastEqProcessor);
