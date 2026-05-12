// Gated Reverb — the trademark 80s drum sound (Phil Collins "In The Air
// Tonight", every snare on Peter Gabriel III). A dense plate-style
// reverb is followed by an envelope-triggered noise gate: while the
// input is loud, the reverb tail is wide open; when the input drops
// below a threshold, the wet path is hard-cut after a short hold time.
// The result is huge, dense, then suddenly silent — no natural decay.

function GatedReverbProcessor() {
  var srScale = sampleRate / 44100;
  this.srScale = srScale;
  // Schroeder core. Bigger combs than plate-reverb for that ambient bigness.
  this.combBase = [1687, 1601, 2053, 2251, 1499, 1789];
  this.combs = [];
  this.combLen = [];
  this.combIdx = [0, 0, 0, 0, 0, 0];
  this.combLp = [0, 0, 0, 0, 0, 0];
  for (var c = 0; c < 6; c++) {
    var L = Math.ceil(this.combBase[c] * srScale);
    var b = new Array(L);
    for (var k = 0; k < L; k++) b[k] = 0;
    this.combs.push(b);
    this.combLen.push(L);
  }
  this.apBase = [347, 113, 41];
  this.aps = []; this.apLen = []; this.apIdx = [0, 0, 0];
  for (var a = 0; a < 3; a++) {
    var L = Math.ceil(this.apBase[a] * srScale);
    var b = new Array(L);
    for (var k = 0; k < L; k++) b[k] = 0;
    this.aps.push(b);
    this.apLen.push(L);
  }
  // Envelope-follower state, gate state machine ("open"/"hold"/"closing").
  this.env = 0;
  this.gateGain = 0;
  this.holdSamples = 0;
  // Read-pointer offset into a small ringbuffer so the gate looks at the
  // INPUT not the reverb tail (otherwise the tail re-triggers itself).
}

GatedReverbProcessor.parameterDescriptors = [
  { name: 'decay',     label: 'Decay',     defaultValue: 0.85, minValue: 0.5, maxValue: 0.97 },
  { name: 'threshold', label: 'Threshold', defaultValue: 0.03, minValue: 0.001, maxValue: 0.3 },
  { name: 'hold',      label: 'Hold (ms)', defaultValue: 220,  minValue: 30,  maxValue: 800 },
  { name: 'release',   label: 'Rel (ms)',  defaultValue: 30,   minValue: 1,   maxValue: 300 },
  { name: 'mix',       label: 'Mix',       defaultValue: 0.5,  minValue: 0,   maxValue: 1 }
];

GatedReverbProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var decay = parameters.decay[0];
  var threshold = parameters.threshold[0];
  var holdMs = parameters.hold[0];
  var relMs = parameters.release[0];
  var mix = parameters.mix[0];

  var envAttack = 1 - Math.exp(-1 / (sampleRate * 0.005));
  var envRelease = 1 - Math.exp(-1 / (sampleRate * 0.05));
  var holdN = Math.floor(holdMs * sampleRate / 1000);
  var relCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * relMs / 1000));
  var apG = 0.5;
  // Comb size, full buffer.
  var cls = [this.combLen[0]-2, this.combLen[1]-2, this.combLen[2]-2, this.combLen[3]-2, this.combLen[4]-2, this.combLen[5]-2];

  for (var i = 0; i < input.length; i++) {
    var x = input[i];

    // --- Envelope follower on the DRY input. ---
    var rect = x < 0 ? -x : x;
    var coef = rect > this.env ? envAttack : envRelease;
    this.env = this.env + coef * (rect - this.env);

    // Gate state machine: open while above threshold, then hold, then
    // close exponentially. This is what makes it sound 80s.
    var target;
    if (this.env > threshold) {
      target = 1;
      this.holdSamples = holdN;
    } else if (this.holdSamples > 0) {
      target = 1;
      this.holdSamples--;
    } else {
      target = 0;
    }
    this.gateGain = this.gateGain + relCoef * (target - this.gateGain);

    // --- Reverb core (6 combs + 3 allpasses). ---
    var rvIn = x;
    var sum = 0;
    for (var c = 0; c < 6; c++) {
      var b = this.combs[c];
      var idx = this.combIdx[c];
      var r = idx - cls[c]; if (r < 0) r += this.combLen[c];
      var d = b[r];
      this.combLp[c] = this.combLp[c] + 0.35 * (d - this.combLp[c]);
      b[idx] = rvIn + this.combLp[c] * decay;
      idx++; if (idx >= this.combLen[c]) idx = 0;
      this.combIdx[c] = idx;
      sum += this.combLp[c];
    }
    var v = sum * (1 / 6);
    for (var a = 0; a < 3; a++) {
      var ab = this.aps[a];
      var aIdx = this.apIdx[a];
      var aL = this.apLen[a] - 2;
      var ar = aIdx - aL; if (ar < 0) ar += this.apLen[a];
      var ad = ab[ar];
      var ai = v + ad * apG;
      ab[aIdx] = ai;
      v = ad - ai * apG;
      aIdx++; if (aIdx >= this.apLen[a]) aIdx = 0;
      this.apIdx[a] = aIdx;
    }

    var wet = v * this.gateGain;
    output[i] = x * (1 - mix) + wet * mix;
  }
  return true;
};

registerProcessor('gated-reverb', GatedReverbProcessor);
