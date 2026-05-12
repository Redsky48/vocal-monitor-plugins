// Limiter — brickwall peak limiter with a short lookahead. We delay the
// audio path by N samples and scan that same window for the worst
// upcoming peak; the gain envelope drops fast enough to clamp it before
// the sample reaches the output, then releases smoothly. This is the
// last-stage tool you put on a master bus to glue everything to 0 dBFS
// without audible pumping. Ceiling sets the absolute output ceiling
// (typically -0.3 dB so consumer DACs don't inter-sample-clip).

function LimiterProcessor() {
  // Lookahead buffer. 8 ms at 48 kHz = 384 samples — well below block size.
  var lookaheadSamples = Math.floor(sampleRate * 0.008);
  this.buf = new Array(lookaheadSamples);
  for (var k = 0; k < lookaheadSamples; k++) this.buf[k] = 0;
  this.bufLen = lookaheadSamples;
  this.idx = 0;
  this.env = 0;
  this.gain = 1;
}

LimiterProcessor.parameterDescriptors = [
  { name: 'ceiling', label: 'Ceil (dB)', defaultValue: -0.3, minValue: -12, maxValue: 0 },
  { name: 'release', label: 'Rel (ms)',  defaultValue: 60,   minValue: 5,   maxValue: 500 }
];

LimiterProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var ceilDb = parameters.ceiling[0];
  var relMs = parameters.release[0];
  var ceilLin = Math.pow(10, ceilDb / 20);
  // Fast attack — almost instantaneous (one block of lookahead).
  var attCoef = 1 - Math.exp(-1 / Math.max(1, this.bufLen * 0.25));
  var relCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * relMs / 1000));

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    // Write the new sample into the lookahead buffer; read the one that's
    // been waiting `bufLen` samples to be played.
    var played = this.buf[this.idx];
    this.buf[this.idx] = x;
    this.idx++; if (this.idx >= this.bufLen) this.idx = 0;

    // Detector sees the freshly-arrived sample (i.e. the peak that will
    // emerge at the output in `bufLen` samples). This is what gives the
    // limiter its lookahead — gain reduction is in place by the time the
    // sample is played.
    var rect = x < 0 ? -x : x;
    var coef = rect > this.env ? attCoef : relCoef;
    this.env = this.env + coef * (rect - this.env);

    // Required gain: ceiling / envelope, clamped at 1 (never boost).
    var target = this.env > ceilLin ? ceilLin / this.env : 1;
    // The gain itself rides the same envelope smoothing.
    this.gain = this.gain + (target < this.gain ? attCoef : relCoef) * (target - this.gain);

    output[i] = played * this.gain;
  }
  return true;
};

registerProcessor('limiter', LimiterProcessor);
