// Shimmer Reverb — a long reverb tail whose feedback path is pitched up
// an octave before going back into the comb network. Each generation of
// echoes is one octave higher than the last, so a sustained note slowly
// blooms into an upward-rising harmonic cloud. Brian Eno made this
// famous on Ambient 1; Strymon BigSky put it on every pedalboard.
//
// Pitch-up is done with two ping-pong granular taps from a circular
// buffer, each running at 2x read speed, crossfaded to hide the seam.

function ShimmerReverbProcessor() {
  var srScale = sampleRate / 44100;
  this.srScale = srScale;
  // Schroeder-ish core: 4 combs + 2 allpasses, same idea as plate-reverb.
  this.combBase = [1116, 1188, 1277, 1356];
  this.apBase = [225, 556];
  this.combs = [];
  this.combLen = [];
  this.combIdx = [0, 0, 0, 0];
  this.combLp = [0, 0, 0, 0];
  for (var c = 0; c < 4; c++) {
    var L = Math.ceil(this.combBase[c] * 1.6 * srScale);
    var b = new Array(L);
    for (var k = 0; k < L; k++) b[k] = 0;
    this.combs.push(b);
    this.combLen.push(L);
  }
  this.allpasses = [];
  this.apLen = [];
  this.apIdx = [0, 0];
  for (var a = 0; a < 2; a++) {
    var La = Math.ceil(this.apBase[a] * 1.6 * srScale);
    var ab = new Array(La);
    for (var k = 0; k < La; k++) ab[k] = 0;
    this.allpasses.push(ab);
    this.apLen.push(La);
  }
  // Granular pitch-shifter buffer — needs ratio*grainSize past room.
  // At 2x (oct-up) and 80 ms grains, 250 ms is comfortable headroom.
  var grainBufLen = Math.ceil(sampleRate * 0.25);
  this.gBuf = new Array(grainBufLen);
  for (var k = 0; k < grainBufLen; k++) this.gBuf[k] = 0;
  this.gBufLen = grainBufLen;
  this.gWrite = 0;
  // Two grain read pointers, 180° out of phase, crossfaded.
  this.grainSize = Math.floor(sampleRate * 0.08); // 80 ms grains
  this.gPhase = 0;
}

ShimmerReverbProcessor.parameterDescriptors = [
  { name: 'decay',   label: 'Decay',     defaultValue: 0.85, minValue: 0.5,  maxValue: 0.97 },
  { name: 'shimmer', label: 'Shimmer',   defaultValue: 0.5,  minValue: 0,    maxValue: 1 },
  { name: 'damping', label: 'Damping',   defaultValue: 0.35, minValue: 0,    maxValue: 1 },
  { name: 'mix',     label: 'Mix',       defaultValue: 0.4,  minValue: 0,    maxValue: 1 }
];

ShimmerReverbProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var decay = parameters.decay[0];
  var shimmer = parameters.shimmer[0];
  var damping = parameters.damping[0];
  var mix = parameters.mix[0];
  var lpA = 1 - damping * 0.85;
  var apG = 0.5;
  var cl0 = this.combLen[0] - 2, cl1 = this.combLen[1] - 2;
  var cl2 = this.combLen[2] - 2, cl3 = this.combLen[3] - 2;
  // Use the full pre-allocated length so size matches buffer.
  var al0 = this.apLen[0] - 2, al1 = this.apLen[1] - 2;
  var grainSize = this.grainSize;
  var grainInc = 2; // octave up (read at 2x speed)

  for (var i = 0; i < input.length; i++) {
    var x = input[i];

    // --- Granular octave-up of the previous block's reverb feedback. ---
    // Grain A and B chase each other at half a window apart; the envelope
    // crossfades them so the read seam never fires alone.
    var gpA = this.gPhase;
    var gpB = (this.gPhase + grainSize * 0.5) % grainSize;
    var rA = this.gWrite - grainSize * grainInc + gpA * grainInc;
    var rB = this.gWrite - grainSize * grainInc + gpB * grainInc;
    while (rA < 0) rA += this.gBufLen;
    while (rA >= this.gBufLen) rA -= this.gBufLen;
    while (rB < 0) rB += this.gBufLen;
    while (rB >= this.gBufLen) rB -= this.gBufLen;
    var iA = Math.floor(rA), fA = rA - iA;
    var jA = iA + 1; if (jA >= this.gBufLen) jA = 0;
    var sA = this.gBuf[iA] * (1 - fA) + this.gBuf[jA] * fA;
    var iB = Math.floor(rB), fB = rB - iB;
    var jB = iB + 1; if (jB >= this.gBufLen) jB = 0;
    var sB = this.gBuf[iB] * (1 - fB) + this.gBuf[jB] * fB;
    // Hann-window envelope per grain.
    var envA = 0.5 - 0.5 * Math.cos(2 * Math.PI * gpA / grainSize);
    var envB = 0.5 - 0.5 * Math.cos(2 * Math.PI * gpB / grainSize);
    var pitched = sA * envA + sB * envB;
    this.gPhase++;
    if (this.gPhase >= grainSize) this.gPhase = 0;

    // Input into reverb = dry + pitched-up tail (mixed by shimmer knob).
    var rvIn = x + pitched * shimmer * 0.7;

    // --- Schroeder core (same shape as plate-reverb). ---
    var b0 = this.combs[0], rd0 = this.combIdx[0] - cl0; if (rd0 < 0) rd0 += this.combLen[0];
    var d0 = b0[rd0];
    this.combLp[0] = this.combLp[0] + lpA * (d0 - this.combLp[0]);
    b0[this.combIdx[0]] = rvIn + this.combLp[0] * decay;
    this.combIdx[0]++; if (this.combIdx[0] >= this.combLen[0]) this.combIdx[0] = 0;

    var b1 = this.combs[1], rd1 = this.combIdx[1] - cl1; if (rd1 < 0) rd1 += this.combLen[1];
    var d1 = b1[rd1];
    this.combLp[1] = this.combLp[1] + lpA * (d1 - this.combLp[1]);
    b1[this.combIdx[1]] = rvIn + this.combLp[1] * decay;
    this.combIdx[1]++; if (this.combIdx[1] >= this.combLen[1]) this.combIdx[1] = 0;

    var b2 = this.combs[2], rd2 = this.combIdx[2] - cl2; if (rd2 < 0) rd2 += this.combLen[2];
    var d2 = b2[rd2];
    this.combLp[2] = this.combLp[2] + lpA * (d2 - this.combLp[2]);
    b2[this.combIdx[2]] = rvIn + this.combLp[2] * decay;
    this.combIdx[2]++; if (this.combIdx[2] >= this.combLen[2]) this.combIdx[2] = 0;

    var b3 = this.combs[3], rd3 = this.combIdx[3] - cl3; if (rd3 < 0) rd3 += this.combLen[3];
    var d3 = b3[rd3];
    this.combLp[3] = this.combLp[3] + lpA * (d3 - this.combLp[3]);
    b3[this.combIdx[3]] = rvIn + this.combLp[3] * decay;
    this.combIdx[3]++; if (this.combIdx[3] >= this.combLen[3]) this.combIdx[3] = 0;

    var combSum = (this.combLp[0] + this.combLp[1] + this.combLp[2] + this.combLp[3]) * 0.25;

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

    // Feed wet tail into the pitch-shift buffer for next time around.
    this.gBuf[this.gWrite] = wet;
    this.gWrite++;
    if (this.gWrite >= this.gBufLen) this.gWrite = 0;

    output[i] = x * (1 - mix) + wet * mix;
  }
  return true;
};

registerProcessor('shimmer-reverb', ShimmerReverbProcessor);
