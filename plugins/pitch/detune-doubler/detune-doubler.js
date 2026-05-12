// Detune Doubler — two pitch-shifted voices ±cents away from the
// original, with a few ms of delay each, mixed under the dry. Fakes
// the ADT (Artificial Double Tracking) trick the Beatles' engineers
// invented to thicken John Lennon's voice without having him sing the
// take twice. Subtle settings glue a thin vocal into the mix; pushed
// hard it's the trademark Bee Gees / Imogen Heap shimmer.

function DetuneDoublerProcessor() {
  var bufLen = Math.ceil(sampleRate * 0.1);
  this.buf = new Array(bufLen);
  for (var k = 0; k < bufLen; k++) this.buf[k] = 0;
  this.bufLen = bufLen;
  this.write = 0;
  this.grainSize = Math.floor(sampleRate * 0.06);
  this.phaseLo = 0;
  this.phaseHi = Math.floor(this.grainSize * 0.5);
}

DetuneDoublerProcessor.parameterDescriptors = [
  { name: 'detune', label: 'Detune (¢)', defaultValue: 18,   minValue: 1,  maxValue: 60 },
  { name: 'delay',  label: 'Delay (ms)', defaultValue: 12,   minValue: 0,  maxValue: 40 },
  { name: 'width',  label: 'Width',      defaultValue: 0.8,  minValue: 0,  maxValue: 1 },
  { name: 'mix',    label: 'Mix',        defaultValue: 0.45, minValue: 0,  maxValue: 1 }
];

DetuneDoublerProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var cents = parameters.detune[0];
  var delayMs = parameters.delay[0];
  var width = parameters.width[0];
  var mix = parameters.mix[0];
  var rLo = Math.pow(2, -cents / 1200);
  var rHi = Math.pow(2,  cents / 1200);
  var delaySamp = Math.max(1, Math.floor(delayMs * sampleRate / 1000));
  var grainSize = this.grainSize;
  var halfGrain = grainSize * 0.5;
  var twoPi = 2 * Math.PI;

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    this.buf[this.write] = x;

    // Voice low (slightly flat). Two-grain read, sum.
    var pA = this.phaseLo;
    var pB = (this.phaseLo + halfGrain) % grainSize;
    var rA = this.write - grainSize * rLo - delaySamp + pA * rLo;
    var rB = this.write - grainSize * rLo - delaySamp + pB * rLo;
    while (rA < 0) rA += this.bufLen; while (rA >= this.bufLen) rA -= this.bufLen;
    while (rB < 0) rB += this.bufLen; while (rB >= this.bufLen) rB -= this.bufLen;
    var iA = Math.floor(rA), fA = rA - iA, jA = iA + 1; if (jA >= this.bufLen) jA = 0;
    var iB = Math.floor(rB), fB = rB - iB, jB = iB + 1; if (jB >= this.bufLen) jB = 0;
    var eA = 0.5 - 0.5 * Math.cos(twoPi * pA / grainSize);
    var eB = 0.5 - 0.5 * Math.cos(twoPi * pB / grainSize);
    var lo = (this.buf[iA] * (1 - fA) + this.buf[jA] * fA) * eA
           + (this.buf[iB] * (1 - fB) + this.buf[jB] * fB) * eB;

    // Voice high (slightly sharp), slightly different delay.
    var pC = this.phaseHi;
    var pD = (this.phaseHi + halfGrain) % grainSize;
    var dHi = Math.floor(delaySamp * 1.6);
    var rC = this.write - grainSize * rHi - dHi + pC * rHi;
    var rD = this.write - grainSize * rHi - dHi + pD * rHi;
    while (rC < 0) rC += this.bufLen; while (rC >= this.bufLen) rC -= this.bufLen;
    while (rD < 0) rD += this.bufLen; while (rD >= this.bufLen) rD -= this.bufLen;
    var iC = Math.floor(rC), fC = rC - iC, jC = iC + 1; if (jC >= this.bufLen) jC = 0;
    var iD = Math.floor(rD), fD = rD - iD, jD = iD + 1; if (jD >= this.bufLen) jD = 0;
    var eC = 0.5 - 0.5 * Math.cos(twoPi * pC / grainSize);
    var eD = 0.5 - 0.5 * Math.cos(twoPi * pD / grainSize);
    var hi = (this.buf[iC] * (1 - fC) + this.buf[jC] * fC) * eC
           + (this.buf[iD] * (1 - fD) + this.buf[jD] * fD) * eD;

    var wet = (lo + hi) * 0.5 * (0.5 + 0.5 * width);
    output[i] = x * (1 - mix * 0.5) + wet * mix;

    this.phaseLo++; if (this.phaseLo >= grainSize) this.phaseLo = 0;
    this.phaseHi++; if (this.phaseHi >= grainSize) this.phaseHi = 0;
    this.write++;   if (this.write   >= this.bufLen) this.write = 0;
  }
  return true;
};

registerProcessor('detune-doubler', DetuneDoublerProcessor);
