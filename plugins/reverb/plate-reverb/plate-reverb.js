// Plate Reverb — Schroeder topology: four parallel feedback comb filters
// (decorrelated prime-ish lengths so their modes don't reinforce into a
// flutter echo) summed and routed through two series allpasses for
// diffusion. Each comb has a one-pole low-pass in its feedback path —
// that's the damping knob: high frequencies decay faster than lows, the
// way a real plate behaves. Classic dense, shimmery 60s/70s plate sound.

function PlateReverbProcessor() {
  // Base comb / allpass lengths in samples. Tuned at 44.1 kHz; the
  // sample-rate factor below scales them so the perceived room size is
  // independent of engine rate. Sized 1.5x to leave room for the "size"
  // knob to push delays longer without re-allocating.
  this.combBase = [1687, 1601, 2053, 2251];
  this.apBase = [347, 113];
  var srScale = sampleRate / 44100;
  this.combs = [];
  this.combLen = [];
  this.combIdx = [0, 0, 0, 0];
  this.combLp = [0, 0, 0, 0];
  for (var c = 0; c < 4; c++) {
    var maxLen = Math.ceil(this.combBase[c] * 1.6 * srScale);
    var b = new Array(maxLen);
    for (var k = 0; k < maxLen; k++) b[k] = 0;
    this.combs.push(b);
    this.combLen.push(maxLen);
  }
  this.allpasses = [];
  this.apLen = [];
  this.apIdx = [0, 0];
  for (var a = 0; a < 2; a++) {
    var maxA = Math.ceil(this.apBase[a] * 1.6 * srScale);
    var ab = new Array(maxA);
    for (var k = 0; k < maxA; k++) ab[k] = 0;
    this.allpasses.push(ab);
    this.apLen.push(maxA);
  }
  this.srScale = srScale;
  this.preBuf = new Array(Math.ceil(sampleRate * 0.1));
  for (var k = 0; k < this.preBuf.length; k++) this.preBuf[k] = 0;
  this.preBufLen = this.preBuf.length;
  this.preWrite = 0;
}

PlateReverbProcessor.parameterDescriptors = [
  { name: 'size',     label: 'Size',      defaultValue: 0.75, minValue: 0.3, maxValue: 1.0 },
  { name: 'decay',    label: 'Decay',     defaultValue: 0.82, minValue: 0,   maxValue: 0.97 },
  { name: 'damping',  label: 'Damping',   defaultValue: 0.4,  minValue: 0,   maxValue: 1 },
  { name: 'preDelay', label: 'Pre (ms)',  defaultValue: 0,    minValue: 0,   maxValue: 100 },
  { name: 'mix',      label: 'Mix',       defaultValue: 0.32, minValue: 0,   maxValue: 1 }
];

PlateReverbProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var size = parameters.size[0];
  var decay = parameters.decay[0];
  var damping = parameters.damping[0];
  var preMs = parameters.preDelay[0];
  var mix = parameters.mix[0];

  // Damping = how much treble bleeds away per pass. Coefficient form:
  // lpA=0 means full damping (totally lowpassed), lpA=1 means no damping.
  var lpA = 1 - damping * 0.85;
  // Pre-delay tap.
  var preSamp = Math.max(0, Math.min(this.preBufLen - 1, Math.floor(preMs * sampleRate / 1000)));
  // Effective lengths scaled by size knob.
  var cl0 = Math.max(8, Math.floor(this.combBase[0] * this.srScale * (0.6 + 0.4 * size)));
  var cl1 = Math.max(8, Math.floor(this.combBase[1] * this.srScale * (0.6 + 0.4 * size)));
  var cl2 = Math.max(8, Math.floor(this.combBase[2] * this.srScale * (0.6 + 0.4 * size)));
  var cl3 = Math.max(8, Math.floor(this.combBase[3] * this.srScale * (0.6 + 0.4 * size)));
  var al0 = Math.max(4, Math.floor(this.apBase[0] * this.srScale));
  var al1 = Math.max(4, Math.floor(this.apBase[1] * this.srScale));
  var apG = 0.5; // classic Schroeder allpass gain

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    // Pre-delay line.
    this.preBuf[this.preWrite] = x;
    var preRead = this.preWrite - preSamp;
    if (preRead < 0) preRead += this.preBufLen;
    var pre = this.preBuf[preRead];
    this.preWrite++;
    if (this.preWrite >= this.preBufLen) this.preWrite = 0;

    // Four feedback combs with damping LP inside the loop.
    var b0 = this.combs[0], r0 = this.combIdx[0] - cl0; if (r0 < 0) r0 += this.combLen[0];
    var d0 = b0[r0];
    this.combLp[0] = this.combLp[0] + lpA * (d0 - this.combLp[0]);
    b0[this.combIdx[0]] = pre + this.combLp[0] * decay;
    this.combIdx[0]++; if (this.combIdx[0] >= this.combLen[0]) this.combIdx[0] = 0;

    var b1 = this.combs[1], r1 = this.combIdx[1] - cl1; if (r1 < 0) r1 += this.combLen[1];
    var d1 = b1[r1];
    this.combLp[1] = this.combLp[1] + lpA * (d1 - this.combLp[1]);
    b1[this.combIdx[1]] = pre + this.combLp[1] * decay;
    this.combIdx[1]++; if (this.combIdx[1] >= this.combLen[1]) this.combIdx[1] = 0;

    var b2 = this.combs[2], r2 = this.combIdx[2] - cl2; if (r2 < 0) r2 += this.combLen[2];
    var d2 = b2[r2];
    this.combLp[2] = this.combLp[2] + lpA * (d2 - this.combLp[2]);
    b2[this.combIdx[2]] = pre + this.combLp[2] * decay;
    this.combIdx[2]++; if (this.combIdx[2] >= this.combLen[2]) this.combIdx[2] = 0;

    var b3 = this.combs[3], r3 = this.combIdx[3] - cl3; if (r3 < 0) r3 += this.combLen[3];
    var d3 = b3[r3];
    this.combLp[3] = this.combLp[3] + lpA * (d3 - this.combLp[3]);
    b3[this.combIdx[3]] = pre + this.combLp[3] * decay;
    this.combIdx[3]++; if (this.combIdx[3] >= this.combLen[3]) this.combIdx[3] = 0;

    var combSum = (this.combLp[0] + this.combLp[1] + this.combLp[2] + this.combLp[3]) * 0.25;

    // Two series Schroeder allpasses for diffusion.
    var ap0 = this.allpasses[0], aR0 = this.apIdx[0] - al0; if (aR0 < 0) aR0 += this.apLen[0];
    var aD0 = ap0[aR0];
    var aIn0 = combSum + aD0 * apG;
    ap0[this.apIdx[0]] = aIn0;
    var ap0Out = aD0 - aIn0 * apG;
    this.apIdx[0]++; if (this.apIdx[0] >= this.apLen[0]) this.apIdx[0] = 0;

    var ap1 = this.allpasses[1], aR1 = this.apIdx[1] - al1; if (aR1 < 0) aR1 += this.apLen[1];
    var aD1 = ap1[aR1];
    var aIn1 = ap0Out + aD1 * apG;
    ap1[this.apIdx[1]] = aIn1;
    var wet = aD1 - aIn1 * apG;
    this.apIdx[1]++; if (this.apIdx[1] >= this.apLen[1]) this.apIdx[1] = 0;

    output[i] = x * (1 - mix) + wet * mix;
  }
  return true;
};

registerProcessor('plate-reverb', PlateReverbProcessor);
