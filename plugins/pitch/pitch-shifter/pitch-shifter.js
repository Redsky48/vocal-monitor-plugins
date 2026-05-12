// Pitch Shifter — granular pitch shifter, ±24 semitones. Reads two
// overlapping grains from a circular buffer at speed = 2^(semi/12),
// Hann-windowed and crossfaded so the seam where each grain wraps is
// inaudible. Same family as SoundTouch / Eventide H910 — not a phase
// vocoder, so transients stay sharp at the cost of slight chorusing
// at large shifts. Perfect for vocal harmonies, octave-down growls,
// chipmunk effects, demon voices.

function PitchShifterProcessor() {
  // At 2x speed (octave up) the grain reads 2 grainSizes of source per
  // grainSize of output, so the buffer must hold ratio×grainSize of past.
  // 350 ms covers ratio = 4x (= +24 semitones) at the default 80 ms grain.
  var bufLen = Math.ceil(sampleRate * 0.35);
  this.buf = new Array(bufLen);
  for (var k = 0; k < bufLen; k++) this.buf[k] = 0;
  this.bufLen = bufLen;
  this.write = 0;
  // 80 ms grain size — short enough to track transients, long enough
  // for low-end pitch tracking down to ~50 Hz.
  this.grainSize = Math.floor(sampleRate * 0.08);
  this.phase = 0;
}

PitchShifterProcessor.parameterDescriptors = [
  { name: 'semitones', label: 'Semitones', defaultValue: 0,   minValue: -24, maxValue: 24 },
  { name: 'cents',     label: 'Cents',     defaultValue: 0,   minValue: -100, maxValue: 100 },
  { name: 'mix',       label: 'Mix',       defaultValue: 1,   minValue: 0,   maxValue: 1 }
];

PitchShifterProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var semitones = parameters.semitones[0];
  var cents = parameters.cents[0];
  var mix = parameters.mix[0];
  var ratio = Math.pow(2, (semitones + cents * 0.01) / 12);
  var grainSize = this.grainSize;
  var halfGrain = grainSize * 0.5;
  var twoPi = 2 * Math.PI;

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    this.buf[this.write] = x;

    // Two read pointers, offset by half a grain. Each reads at `ratio`
    // speed within the grain, then loops back. The Hann envelope on
    // each makes them sum to a near-flat amplitude when overlapped.
    // Read starts at write - grainSize*ratio and ends right at write,
    // so the grain is entirely in the past regardless of ratio.
    var pA = this.phase;
    var pB = (this.phase + halfGrain) % grainSize;
    var rA = this.write - grainSize * ratio + pA * ratio;
    var rB = this.write - grainSize * ratio + pB * ratio;
    while (rA < 0) rA += this.bufLen;
    while (rA >= this.bufLen) rA -= this.bufLen;
    while (rB < 0) rB += this.bufLen;
    while (rB >= this.bufLen) rB -= this.bufLen;
    var iA = Math.floor(rA), fA = rA - iA;
    var jA = iA + 1; if (jA >= this.bufLen) jA = 0;
    var sA = this.buf[iA] * (1 - fA) + this.buf[jA] * fA;
    var iB = Math.floor(rB), fB = rB - iB;
    var jB = iB + 1; if (jB >= this.bufLen) jB = 0;
    var sB = this.buf[iB] * (1 - fB) + this.buf[jB] * fB;
    var envA = 0.5 - 0.5 * Math.cos(twoPi * pA / grainSize);
    var envB = 0.5 - 0.5 * Math.cos(twoPi * pB / grainSize);
    var wet = sA * envA + sB * envB;

    this.phase++;
    if (this.phase >= grainSize) this.phase = 0;
    this.write++;
    if (this.write >= this.bufLen) this.write = 0;

    output[i] = x * (1 - mix) + wet * mix;
  }
  return true;
};

registerProcessor('pitch-shifter', PitchShifterProcessor);
