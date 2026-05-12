// Transient Shaper — independent control of attack and sustain. Two
// parallel envelope followers chase the input: one fast, one slow. The
// difference between them = the transient component (mostly nonzero
// right after a hit and decaying quickly). The slow envelope = the
// sustain component. Multiply each by user-controllable gain and sum.
// SPL Transient Designer reborn — fatten snares, soften plosive Ps,
// add bite to a vocal, take edges off a guitar.

function TransientShaperProcessor() {
  this.fastEnv = 0;
  this.slowEnv = 0;
  this.fastGain = 1;
  this.slowGain = 1;
}

TransientShaperProcessor.parameterDescriptors = [
  { name: 'attack',  label: 'Attack',  defaultValue: 0,   minValue: -1, maxValue: 1 },
  { name: 'sustain', label: 'Sustain', defaultValue: 0,   minValue: -1, maxValue: 1 },
  { name: 'mix',     label: 'Mix',     defaultValue: 1,   minValue: 0,  maxValue: 1 }
];

TransientShaperProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var attack = parameters.attack[0];
  var sustain = parameters.sustain[0];
  var mix = parameters.mix[0];

  // Two envelope followers — same attack speed (fast), different release.
  // The fast envelope falls quickly, so it tracks the transient peak;
  // the slow one lingers, so it represents the body of the note.
  var attCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * 0.002));
  var fastRelCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * 0.030));
  var slowRelCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * 0.200));

  // Map -1..+1 → gain multipliers (0.0 .. 4.0). -1 cuts the component
  // completely, 0 leaves it as-is, +1 quadruples it.
  var attGain = attack >= 0 ? 1 + attack * 3 : 1 + attack * 0.95;
  var susGain = sustain >= 0 ? 1 + sustain * 3 : 1 + sustain * 0.95;

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    var rect = x < 0 ? -x : x;

    var fc = rect > this.fastEnv ? attCoef : fastRelCoef;
    this.fastEnv = this.fastEnv + fc * (rect - this.fastEnv);
    var sc = rect > this.slowEnv ? attCoef : slowRelCoef;
    this.slowEnv = this.slowEnv + sc * (rect - this.slowEnv);

    // Transient signal = how much the fast envelope is currently above
    // the slow one (a positive bump right after each onset).
    var trans = this.fastEnv - this.slowEnv;
    if (trans < 0) trans = 0;
    // Convert to a multiplier riding over 1.0. With unit gains, output
    // equals input; with attack>0, transients are boosted, etc.
    var origAmp = this.slowEnv > 1e-6 ? this.slowEnv : 1e-6;
    var transMul = 1 + (attGain - 1) * (trans / (origAmp + trans));
    var susMul = susGain;
    // Apply transient multiplier proportional to where in the envelope
    // we are. Simplest blend: gain = susMul during sustain region,
    // gain = susMul * transMul at the transient peak. Sample-by-sample
    // weight = trans / (trans + slow).
    var w = trans / (trans + origAmp);
    var gain = susMul * (1 - w) + susMul * transMul * w;
    var wet = x * gain;
    output[i] = x * (1 - mix) + wet * mix;
  }
  return true;
};

registerProcessor('transient-shaper', TransientShaperProcessor);
