// Frequency Shifter — Bode-style single-sideband shifter. NOT the same
// as a pitch-shifter: a pitch shifter preserves harmonic ratios (2x →
// up-octave), this one shifts every frequency component by the SAME
// number of Hz (a 200 Hz partial and a 400 Hz partial shifted by +100 Hz
// become 300 Hz and 500 Hz — no longer harmonically related). That
// inharmonic shift is what gives the effect its trademark metallic,
// alien, utterly non-musical character.
//
// Implementation: a pair of Niemitalo polyphase Hilbert all-pass
// cascades generate a 90°-shifted "imaginary" copy of the input. Each
// section is a 2nd-order all-pass of the form
//   y[n] = a² · (x[n] + y[n-2]) − x[n-2]
// (note the z⁻² delays). Branch A is fed the live sample, branch B is
// fed the sample one frame older; their outputs are I and Q. SSB:
//   wet = I·cos(ωt) − Q·sin(ωt)
// gives the upper sideband; negate ω for the lower.

function FrequencyShifterProcessor() {
  // Eight sections per branch, coefficients pre-squared. Total stopband
  // attenuation > 60 dB from ~80 Hz to 20 kHz — plenty for vocals.
  this.aSqA = [0.00247361031,  0.0314082443,   0.124170815,    0.319773729,
               0.554763139,    0.752066439,    0.890916286,    0.961945129];
  this.aSqB = [0.0103096966,   0.0671292540,   0.214825889,    0.453241851,
               0.674534273,    0.842446744,    0.939413728,    0.984098284];
  this.xA_p = []; this.xA_pp = []; this.yA_p = []; this.yA_pp = [];
  this.xB_p = []; this.xB_pp = []; this.yB_p = []; this.yB_pp = [];
  for (var i = 0; i < 8; i++) {
    this.xA_p.push(0); this.xA_pp.push(0);
    this.yA_p.push(0); this.yA_pp.push(0);
    this.xB_p.push(0); this.xB_pp.push(0);
    this.yB_p.push(0); this.yB_pp.push(0);
  }
  this.phase = 0;
  this.bInDelay = 0; // branch B input is delayed by 1 sample
}

FrequencyShifterProcessor.parameterDescriptors = [
  { name: 'shift', label: 'Shift (Hz)', defaultValue: 100, minValue: -1000, maxValue: 1000 },
  { name: 'mix',   label: 'Mix',        defaultValue: 1,   minValue: 0,     maxValue: 1 }
];

FrequencyShifterProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var shift = parameters.shift[0];
  var mix = parameters.mix[0];
  var phaseInc = 2 * Math.PI * shift / sampleRate;

  for (var i = 0; i < input.length; i++) {
    var x = input[i];

    // Branch A — live sample.
    var vA = x;
    for (var s = 0; s < 8; s++) {
      var y = this.aSqA[s] * (vA + this.yA_pp[s]) - this.xA_pp[s];
      this.xA_pp[s] = this.xA_p[s]; this.xA_p[s] = vA;
      this.yA_pp[s] = this.yA_p[s]; this.yA_p[s] = y;
      vA = y;
    }

    // Branch B — sample delayed by 1.
    var vB = this.bInDelay;
    this.bInDelay = x;
    for (var s = 0; s < 8; s++) {
      var y2 = this.aSqB[s] * (vB + this.yB_pp[s]) - this.xB_pp[s];
      this.xB_pp[s] = this.xB_p[s]; this.xB_p[s] = vB;
      this.yB_pp[s] = this.yB_p[s]; this.yB_p[s] = y2;
      vB = y2;
    }

    // I = vA (real), Q = vB (90° lagged). SSB upper-sideband mixer.
    var co = Math.cos(this.phase), si = Math.sin(this.phase);
    var wet = vA * co - vB * si;

    output[i] = x * (1 - mix) + wet * mix;

    this.phase += phaseInc;
    while (this.phase > 2 * Math.PI) this.phase -= 2 * Math.PI;
    while (this.phase < -2 * Math.PI) this.phase += 2 * Math.PI;
  }
  return true;
};

registerProcessor('frequency-shifter', FrequencyShifterProcessor);
