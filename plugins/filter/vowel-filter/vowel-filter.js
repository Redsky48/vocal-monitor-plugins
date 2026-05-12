// Vowel Filter — three parallel bandpass resonators, each tuned to one
// of the three principal formant frequencies of a sung vowel (F1, F2,
// F3). Sliding the "vowel" parameter morphs the centre frequencies
// between A → E → I → O → U; an LFO can sweep the position automatically
// for that classic talk-wah / robot-mouth effect. Same idea as the
// Mutable Instruments "Plaits" speech synth model.

function VowelFilterProcessor() {
  // Per-resonator biquad state (direct-form-1 history).
  this.s = [[0,0,0,0],[0,0,0,0],[0,0,0,0]];
  this.phase = 0;
}

// Formant tables (F1, F2, F3) at the five canonical vowels. Numbers
// from Praat / standard linguistics references; close enough to sound
// vocally correct without burning samples on a vocoder analysis bank.
VowelFilterProcessor.parameterDescriptors = [
  { name: 'vowel',    label: 'Vowel',   defaultValue: 0,   minValue: 0,  maxValue: 4 },
  { name: 'rate',     label: 'Rate (Hz)', defaultValue: 0, minValue: 0,  maxValue: 8 },
  { name: 'depth',    label: 'Depth',   defaultValue: 0.5, minValue: 0,  maxValue: 1 },
  { name: 'q',        label: 'Q',       defaultValue: 8,   minValue: 2,  maxValue: 30 },
  { name: 'mix',      label: 'Mix',     defaultValue: 1,   minValue: 0,  maxValue: 1 }
];

var VOWEL_F1 = [700, 500, 270, 450, 325];   // A, E, I, O, U
var VOWEL_F2 = [1220, 2300, 2300, 800, 700];
var VOWEL_F3 = [2600, 3000, 3000, 2830, 2530];

function bpCoefs(fc, q, sr) {
  var w = 2 * Math.PI * fc / sr;
  var c = Math.cos(w), s = Math.sin(w);
  var alpha = s / (2 * q);
  var a0 = 1 + alpha;
  return [
    alpha / a0,         // b0
    0,                  // b1
    -alpha / a0,        // b2
    -2 * c / a0,        // a1
    (1 - alpha) / a0    // a2
  ];
}

VowelFilterProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var vowelKnob = parameters.vowel[0];
  var rate = parameters.rate[0];
  var depth = parameters.depth[0];
  var q = parameters.q[0];
  var mix = parameters.mix[0];

  // LFO sweeps the vowel index when rate > 0; when rate is 0 the knob
  // is the pure controller. Range 0..4 = A..U.
  var phaseInc = rate > 0 ? (2 * Math.PI * rate / sampleRate) : 0;
  var pos = vowelKnob;
  if (rate > 0) {
    pos = vowelKnob + depth * 2 * Math.sin(this.phase);
  }
  if (pos < 0) pos = 0;
  if (pos > 4) pos = 4;

  // Linear interp between two adjacent vowels in the table.
  var idx = Math.floor(pos);
  if (idx > 3) idx = 3;
  var frac = pos - idx;
  var f1 = VOWEL_F1[idx] * (1 - frac) + VOWEL_F1[idx + 1] * frac;
  var f2 = VOWEL_F2[idx] * (1 - frac) + VOWEL_F2[idx + 1] * frac;
  var f3 = VOWEL_F3[idx] * (1 - frac) + VOWEL_F3[idx + 1] * frac;

  // Per-block biquad coefficients — vowel index is k-rate, plenty fast.
  var c1 = bpCoefs(f1, q, sampleRate);
  var c2 = bpCoefs(f2, q, sampleRate);
  var c3 = bpCoefs(f3, q, sampleRate);
  // Relative loudness of each formant. F1 is the loudest in real vowels,
  // F2 and F3 progressively quieter.
  var g1 = 1.0, g2 = 0.75, g3 = 0.5;
  var s1 = this.s[0], s2 = this.s[1], s3 = this.s[2];

  for (var i = 0; i < input.length; i++) {
    var x = input[i];

    var y1 = c1[0]*x + c1[1]*s1[0] + c1[2]*s1[1] - c1[3]*s1[2] - c1[4]*s1[3];
    s1[1] = s1[0]; s1[0] = x; s1[3] = s1[2]; s1[2] = y1;
    var y2 = c2[0]*x + c2[1]*s2[0] + c2[2]*s2[1] - c2[3]*s2[2] - c2[4]*s2[3];
    s2[1] = s2[0]; s2[0] = x; s2[3] = s2[2]; s2[2] = y2;
    var y3 = c3[0]*x + c3[1]*s3[0] + c3[2]*s3[1] - c3[3]*s3[2] - c3[4]*s3[3];
    s3[1] = s3[0]; s3[0] = x; s3[3] = s3[2]; s3[2] = y3;

    var wet = y1 * g1 + y2 * g2 + y3 * g3;
    output[i] = x * (1 - mix) + wet * mix;

    if (phaseInc > 0) {
      this.phase += phaseInc;
      if (this.phase > 2 * Math.PI) this.phase -= 2 * Math.PI;
    }
  }
  return true;
};

registerProcessor('vowel-filter', VowelFilterProcessor);
