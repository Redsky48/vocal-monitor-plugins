// Spring Reverb — recreates the dispersive, twangy character of a real
// spring tank by cascading a chain of allpass sections (which delay
// different frequencies by different amounts — that's "dispersion", the
// thing that gives a struck spring its chirpy "boing" before the tail
// settles). Two short delay lines with cross-feedback simulate the two
// springs of a classic Fender / Hammond tank. Add some highpass on the
// way in and lowpass in the loop so the tail sounds metallic-but-warm,
// the way an old guitar amp's tank does.

function SpringReverbProcessor() {
  var srScale = sampleRate / 44100;
  // Allpass dispersion chain — 8 sections with prime-ish lengths so the
  // group-delay curve isn't periodic.
  this.apLens = [37, 53, 71, 89, 107, 131, 149, 167];
  this.aps = [];
  this.apIdx = [];
  for (var a = 0; a < this.apLens.length; a++) {
    var L = Math.ceil(this.apLens[a] * srScale);
    var b = new Array(L);
    for (var k = 0; k < L; k++) b[k] = 0;
    this.aps.push(b);
    this.apIdx.push(0);
  }
  // Two parallel delay lines — the "springs". Long enough for ~120 ms.
  var d1Len = Math.ceil(sampleRate * 0.13);
  var d2Len = Math.ceil(sampleRate * 0.16);
  this.d1 = new Array(d1Len); this.d2 = new Array(d2Len);
  for (var k = 0; k < d1Len; k++) this.d1[k] = 0;
  for (var k = 0; k < d2Len; k++) this.d2[k] = 0;
  this.d1Len = d1Len; this.d2Len = d2Len;
  this.d1Idx = 0; this.d2Idx = 0;
  this.lp1 = 0; this.lp2 = 0;
  this.hp1Prev = 0; this.hp1Out = 0;
}

SpringReverbProcessor.parameterDescriptors = [
  { name: 'decay',  label: 'Decay',  defaultValue: 0.65, minValue: 0,   maxValue: 0.92 },
  { name: 'boing',  label: 'Boing',  defaultValue: 0.7,  minValue: 0,   maxValue: 1 },
  { name: 'tone',   label: 'Tone',   defaultValue: 0.45, minValue: 0,   maxValue: 1 },
  { name: 'mix',    label: 'Mix',    defaultValue: 0.4,  minValue: 0,   maxValue: 1 }
];

SpringReverbProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var decay = parameters.decay[0];
  var boing = parameters.boing[0];
  var tone = parameters.tone[0];
  var mix = parameters.mix[0];

  // Allpass coefficient — higher = more dispersion = more boing.
  var apG = 0.3 + 0.5 * boing;
  // Lowpass in loop (tone knob): closed = dark, open = bright.
  var lpA = 0.05 + 0.85 * tone;
  // Pre-emphasis highpass to keep the rumble out of the springs.
  var hpA = 0.99;

  var numAps = this.apLens.length;

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    // Highpass the input.
    this.hp1Out = hpA * (this.hp1Out + x - this.hp1Prev);
    this.hp1Prev = x;
    var hp = this.hp1Out;

    // Cross-feedback from both springs into the input of the AP chain.
    var d1R = this.d1Idx - (this.d1Len - 1); if (d1R < 0) d1R += this.d1Len;
    var d2R = this.d2Idx - (this.d2Len - 1); if (d2R < 0) d2R += this.d2Len;
    var s1Out = this.d1[d1R];
    var s2Out = this.d2[d2R];

    var v = hp + s1Out * decay * 0.7 + s2Out * decay * 0.3;

    // Run through allpass dispersion chain.
    for (var a = 0; a < numAps; a++) {
      var L = this.aps[a].length;
      var idx = this.apIdx[a];
      var r = idx - (L - 1); if (r < 0) r += L;
      var del = this.aps[a][r];
      var inAp = v + del * apG;
      this.aps[a][idx] = inAp;
      v = del - inAp * apG;
      idx++; if (idx >= L) idx = 0;
      this.apIdx[a] = idx;
    }

    // Damping LP, then write into both springs (cross-coupled).
    this.lp1 = this.lp1 + lpA * (v - this.lp1);
    this.lp2 = this.lp2 + lpA * (v - this.lp2);
    this.d1[this.d1Idx] = this.lp1 + s2Out * decay * 0.2;
    this.d2[this.d2Idx] = this.lp2 + s1Out * decay * 0.2;
    this.d1Idx++; if (this.d1Idx >= this.d1Len) this.d1Idx = 0;
    this.d2Idx++; if (this.d2Idx >= this.d2Len) this.d2Idx = 0;

    var wet = (s1Out + s2Out) * 0.5;
    output[i] = x * (1 - mix) + wet * mix;
  }
  return true;
};

registerProcessor('spring-reverb', SpringReverbProcessor);
