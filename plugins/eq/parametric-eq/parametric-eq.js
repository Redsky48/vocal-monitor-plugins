// Parametric EQ — three independent bands: low shelf, peaking bell, and
// high shelf. Each is a single RBJ biquad (the "audio EQ cookbook"
// formulas everyone uses). Direct-form-1 storage so a change in
// coefficients between blocks doesn't pop. Good 3-band channel-strip
// flavour, useful as a sound-shaping companion to the compressor.

function ParametricEqProcessor() {
  // Per-band biquad delays (x[n-1], x[n-2], y[n-1], y[n-2]).
  this.ls = [0, 0, 0, 0];
  this.pk = [0, 0, 0, 0];
  this.hs = [0, 0, 0, 0];
}

ParametricEqProcessor.parameterDescriptors = [
  { name: 'lowFreq',  label: 'Low Hz',  defaultValue: 120,  minValue: 30,  maxValue: 500 },
  { name: 'lowGain',  label: 'Low dB',  defaultValue: 0,    minValue: -18, maxValue: 18 },
  { name: 'midFreq',  label: 'Mid Hz',  defaultValue: 1000, minValue: 200, maxValue: 6000 },
  { name: 'midGain',  label: 'Mid dB',  defaultValue: 0,    minValue: -18, maxValue: 18 },
  { name: 'midQ',     label: 'Mid Q',   defaultValue: 1.0,  minValue: 0.3, maxValue: 8 },
  { name: 'highFreq', label: 'High Hz', defaultValue: 8000, minValue: 1500, maxValue: 16000 },
  { name: 'highGain', label: 'High dB', defaultValue: 0,    minValue: -18, maxValue: 18 }
];

// RBJ biquad cookbook — low shelf.
function lowShelf(fc, gainDb, sr) {
  var A = Math.pow(10, gainDb / 40);
  var w = 2 * Math.PI * fc / sr;
  var c = Math.cos(w), s = Math.sin(w);
  var S = 1;
  var alpha = s / 2 * Math.sqrt((A + 1/A) * (1/S - 1) + 2);
  var beta = 2 * Math.sqrt(A) * alpha;
  var a0 = (A + 1) + (A - 1) * c + beta;
  var b0 = A * ((A + 1) - (A - 1) * c + beta) / a0;
  var b1 = 2 * A * ((A - 1) - (A + 1) * c) / a0;
  var b2 = A * ((A + 1) - (A - 1) * c - beta) / a0;
  var a1 = -2 * ((A - 1) + (A + 1) * c) / a0;
  var a2 = ((A + 1) + (A - 1) * c - beta) / a0;
  return [b0, b1, b2, a1, a2];
}
// Peaking bell.
function peaking(fc, gainDb, q, sr) {
  var A = Math.pow(10, gainDb / 40);
  var w = 2 * Math.PI * fc / sr;
  var c = Math.cos(w), s = Math.sin(w);
  var alpha = s / (2 * q);
  var a0 = 1 + alpha / A;
  var b0 = (1 + alpha * A) / a0;
  var b1 = (-2 * c) / a0;
  var b2 = (1 - alpha * A) / a0;
  var a1 = b1;
  var a2 = (1 - alpha / A) / a0;
  return [b0, b1, b2, a1, a2];
}
// High shelf.
function highShelf(fc, gainDb, sr) {
  var A = Math.pow(10, gainDb / 40);
  var w = 2 * Math.PI * fc / sr;
  var c = Math.cos(w), s = Math.sin(w);
  var S = 1;
  var alpha = s / 2 * Math.sqrt((A + 1/A) * (1/S - 1) + 2);
  var beta = 2 * Math.sqrt(A) * alpha;
  var a0 = (A + 1) - (A - 1) * c + beta;
  var b0 = A * ((A + 1) + (A - 1) * c + beta) / a0;
  var b1 = -2 * A * ((A - 1) + (A + 1) * c) / a0;
  var b2 = A * ((A + 1) + (A - 1) * c - beta) / a0;
  var a1 = 2 * ((A - 1) - (A + 1) * c) / a0;
  var a2 = ((A + 1) - (A - 1) * c - beta) / a0;
  return [b0, b1, b2, a1, a2];
}

ParametricEqProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var lf = parameters.lowFreq[0],  lg = parameters.lowGain[0];
  var mf = parameters.midFreq[0],  mg = parameters.midGain[0], mq = parameters.midQ[0];
  var hf = parameters.highFreq[0], hg = parameters.highGain[0];

  var lc = lowShelf(lf, lg, sampleRate);
  var mc = peaking(mf, mg, mq, sampleRate);
  var hc = highShelf(hf, hg, sampleRate);
  var ls = this.ls, pk = this.pk, hs = this.hs;

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    // Low shelf
    var y1 = lc[0]*x + lc[1]*ls[0] + lc[2]*ls[1] - lc[3]*ls[2] - lc[4]*ls[3];
    ls[1] = ls[0]; ls[0] = x;
    ls[3] = ls[2]; ls[2] = y1;
    // Peaking
    var y2 = mc[0]*y1 + mc[1]*pk[0] + mc[2]*pk[1] - mc[3]*pk[2] - mc[4]*pk[3];
    pk[1] = pk[0]; pk[0] = y1;
    pk[3] = pk[2]; pk[2] = y2;
    // High shelf
    var y3 = hc[0]*y2 + hc[1]*hs[0] + hc[2]*hs[1] - hc[3]*hs[2] - hc[4]*hs[3];
    hs[1] = hs[0]; hs[0] = y2;
    hs[3] = hs[2]; hs[2] = y3;
    output[i] = y3;
  }
  return true;
};

registerProcessor('parametric-eq', ParametricEqProcessor);
