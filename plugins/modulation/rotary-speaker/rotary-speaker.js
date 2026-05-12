// Rotary Speaker — Leslie cabinet sim. Two independently-rotating
// elements (the bass-rotor drum, the horn-rotor treble) each create
// pitch (Doppler) modulation as they swing toward and away from the
// virtual mic, plus an amplitude tremolo from the directional pattern.
// Crossover splits the signal — the bass drum gets the lows, the horn
// the highs — and each is modulated at its own rate. Two-speed switching
// (slow "chorale", fast "tremolo") with smooth ramp-up like the real
// motor inertia. The trademark Procol Harum / Hammond B-3 sound.

function RotarySpeakerProcessor() {
  // Crossover at ~800 Hz, LR2 (one-pole each).
  this.lpState = 0;
  this.hpPrev = 0;
  this.hpOut = 0;
  // Doppler delay buffers — bass slower, horn faster.
  var bassBufLen = Math.floor(sampleRate * 0.03);
  var hornBufLen = Math.floor(sampleRate * 0.02);
  this.bassBuf = new Array(bassBufLen);
  this.hornBuf = new Array(hornBufLen);
  for (var k = 0; k < bassBufLen; k++) this.bassBuf[k] = 0;
  for (var k = 0; k < hornBufLen; k++) this.hornBuf[k] = 0;
  this.bassLen = bassBufLen; this.hornLen = hornBufLen;
  this.bassIdx = 0; this.hornIdx = 0;
  this.bassPhase = 0; this.hornPhase = Math.PI; // 180° offset for stereo realism
  // Smoothed actual speeds (motor inertia).
  this.bassRate = 0.8;
  this.hornRate = 1.0;
}

RotarySpeakerProcessor.parameterDescriptors = [
  { name: 'speed',  label: 'Speed',  defaultValue: 0,   minValue: 0,  maxValue: 1 },
  { name: 'depth',  label: 'Depth',  defaultValue: 0.7, minValue: 0,  maxValue: 1 },
  { name: 'drive',  label: 'Drive',  defaultValue: 0.2, minValue: 0,  maxValue: 1 },
  { name: 'mix',    label: 'Mix',    defaultValue: 1,   minValue: 0,  maxValue: 1 }
];

RotarySpeakerProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var speedKnob = parameters.speed[0]; // 0 = slow, 1 = fast
  var depth = parameters.depth[0];
  var drive = parameters.drive[0];
  var mix = parameters.mix[0];

  // Target speeds. Slow ("chorale") ≈ 0.7 Hz for bass / 1.0 Hz horn.
  // Fast ("tremolo") ≈ 6.5 Hz bass / 7.5 Hz horn. Real Leslie ramps
  // between them in ~1 second — model that with a per-sample IIR.
  var targetBass = 0.7 + speedKnob * 5.8;
  var targetHorn = 1.0 + speedKnob * 6.5;
  var rampCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * 0.6));

  // One-pole crossover coefficients at ~800 Hz.
  var dt = 1 / sampleRate;
  var rcLp = 1 / (2 * Math.PI * 800);
  var lpA = dt / (rcLp + dt);
  var hpRc = 1 / (2 * Math.PI * 200);
  var hpA = hpRc / (hpRc + dt);

  var bassMaxDepth = sampleRate * 0.004; // 4 ms peak
  var hornMaxDepth = sampleRate * 0.0015; // 1.5 ms peak

  for (var i = 0; i < input.length; i++) {
    var x = input[i];

    // --- Soft preamp drive (Leslie's tube preamp). ---
    var k = 1 + drive * 5;
    var driven = Math.tanh(x * k) / Math.tanh(k);

    // --- Crossover. ---
    this.lpState = this.lpState + lpA * (driven - this.lpState);
    var lows = this.lpState;
    this.hpOut = hpA * (this.hpOut + driven - this.hpPrev);
    this.hpPrev = driven;
    var highs = driven - lows;

    // Smooth motor speed.
    this.bassRate = this.bassRate + rampCoef * (targetBass - this.bassRate);
    this.hornRate = this.hornRate + rampCoef * (targetHorn - this.hornRate);

    // --- Bass rotor: amplitude tremolo + Doppler delay modulation. ---
    var bassLfo = Math.sin(this.bassPhase);
    var bassAmp = 1 - depth * 0.3 * bassLfo;
    var bassDelay = bassMaxDepth * depth * (1 + bassLfo) * 0.5;
    this.bassBuf[this.bassIdx] = lows;
    var bRead = this.bassIdx - bassDelay;
    while (bRead < 0) bRead += this.bassLen;
    var bI = Math.floor(bRead), bF = bRead - bI;
    var bJ = bI + 1; if (bJ >= this.bassLen) bJ = 0;
    var bassOut = (this.bassBuf[bI] * (1 - bF) + this.bassBuf[bJ] * bF) * bassAmp;
    this.bassIdx++; if (this.bassIdx >= this.bassLen) this.bassIdx = 0;

    // --- Horn rotor. ---
    var hornLfo = Math.sin(this.hornPhase);
    var hornAmp = 1 - depth * 0.45 * hornLfo;
    var hornDelay = hornMaxDepth * depth * (1 + hornLfo) * 0.5;
    this.hornBuf[this.hornIdx] = highs;
    var hRead = this.hornIdx - hornDelay;
    while (hRead < 0) hRead += this.hornLen;
    var hI = Math.floor(hRead), hF = hRead - hI;
    var hJ = hI + 1; if (hJ >= this.hornLen) hJ = 0;
    var hornOut = (this.hornBuf[hI] * (1 - hF) + this.hornBuf[hJ] * hF) * hornAmp;
    this.hornIdx++; if (this.hornIdx >= this.hornLen) this.hornIdx = 0;

    var wet = bassOut + hornOut;
    output[i] = x * (1 - mix) + wet * mix;

    this.bassPhase += 2 * Math.PI * this.bassRate / sampleRate;
    if (this.bassPhase > 2 * Math.PI) this.bassPhase -= 2 * Math.PI;
    this.hornPhase += 2 * Math.PI * this.hornRate / sampleRate;
    if (this.hornPhase > 2 * Math.PI) this.hornPhase -= 2 * Math.PI;
  }
  return true;
};

registerProcessor('rotary-speaker', RotarySpeakerProcessor);
