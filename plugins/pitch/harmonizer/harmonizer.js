// Harmonizer — two parallel granular pitch shifters tuned to musical
// intervals (default: minor third + perfect fifth, a classic Stevie
// Wonder / Eventide harmonizer setting) mixed under the dry voice. Drop
// it on a lead vocal for instant three-part harmony.

function HarmonizerProcessor() {
  // At ratio=2 (max +12 semis), each grain reads 2*grainSize past samples,
  // so buffer must cover at least that. 200 ms is enough for ±octave.
  var bufLen = Math.ceil(sampleRate * 0.2);
  this.buf = new Array(bufLen);
  for (var k = 0; k < bufLen; k++) this.buf[k] = 0;
  this.bufLen = bufLen;
  this.write = 0;
  this.grainSize = Math.floor(sampleRate * 0.08);
  this.phase1 = 0;
  this.phase2 = Math.floor(this.grainSize * 0.33); // start offset so the
                                                    // two voices don't both
                                                    // hit their seams together
}

HarmonizerProcessor.parameterDescriptors = [
  { name: 'voice1', label: 'V1 semi',  defaultValue: 3,   minValue: -12, maxValue: 12 },
  { name: 'voice2', label: 'V2 semi',  defaultValue: 7,   minValue: -12, maxValue: 12 },
  { name: 'spread', label: 'Spread',   defaultValue: 0.7, minValue: 0,   maxValue: 1 },
  { name: 'mix',    label: 'Mix',      defaultValue: 0.5, minValue: 0,   maxValue: 1 }
];

function readGrain(buf, write, bufLen, phase, ratio, grainSize) {
  var r = write - grainSize * ratio + phase * ratio;
  while (r < 0) r += bufLen;
  while (r >= bufLen) r -= bufLen;
  var i = Math.floor(r), f = r - i;
  var j = i + 1; if (j >= bufLen) j = 0;
  var env = 0.5 - 0.5 * Math.cos(2 * Math.PI * phase / grainSize);
  return (buf[i] * (1 - f) + buf[j] * f) * env;
}

HarmonizerProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var v1 = parameters.voice1[0];
  var v2 = parameters.voice2[0];
  var spread = parameters.spread[0];
  var mix = parameters.mix[0];
  var r1 = Math.pow(2, v1 / 12);
  var r2 = Math.pow(2, v2 / 12);
  var grainSize = this.grainSize;
  var halfGrain = grainSize * 0.5;

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    this.buf[this.write] = x;

    // Each voice = two grain reads half-window apart, summed.
    var pA1 = this.phase1;
    var pB1 = (this.phase1 + halfGrain) % grainSize;
    var pA2 = this.phase2;
    var pB2 = (this.phase2 + halfGrain) % grainSize;
    var voice1 = readGrain(this.buf, this.write, this.bufLen, pA1, r1, grainSize)
               + readGrain(this.buf, this.write, this.bufLen, pB1, r1, grainSize);
    var voice2 = readGrain(this.buf, this.write, this.bufLen, pA2, r2, grainSize)
               + readGrain(this.buf, this.write, this.bufLen, pB2, r2, grainSize);

    var wet = (voice1 + voice2) * 0.5 * spread;

    this.phase1++; if (this.phase1 >= grainSize) this.phase1 = 0;
    this.phase2++; if (this.phase2 >= grainSize) this.phase2 = 0;
    this.write++;  if (this.write  >= this.bufLen)  this.write  = 0;

    output[i] = x * (1 - mix * 0.5) + wet * mix;
  }
  return true;
};

registerProcessor('harmonizer', HarmonizerProcessor);
