// Megaphone — bullhorn / drill-sergeant / radio-comm voice. Three
// ingredients layered in series: (1) a steep mid-band band-pass that
// kills sub-bass and air the way a cheap PA speaker does, (2) hard
// asymmetric clipping for the cone-distortion crunch, (3) a peaking
// resonance around 2 kHz so the result honks like a horn driver
// instead of just sounding muffled. Great for shouted vocal sections,
// in-game radio chatter, riot-cop announcement vibes.

function MegaphoneProcessor() {
  // Three biquads in series — HP, peak (honk), LP. State per biquad.
  this.hpA = [0, 0]; this.hpB = [0, 0];
  this.pkA = [0, 0]; this.pkB = [0, 0];
  this.lpA = [0, 0]; this.lpB = [0, 0];
}

MegaphoneProcessor.parameterDescriptors = [
  { name: 'lowCut',  label: 'Low Hz',  defaultValue: 500,  minValue: 100, maxValue: 1500 },
  { name: 'highCut', label: 'High Hz', defaultValue: 4500, minValue: 1500, maxValue: 8000 },
  { name: 'honk',    label: 'Honk',    defaultValue: 8,    minValue: 0,    maxValue: 18 },
  { name: 'crunch',  label: 'Crunch',  defaultValue: 0.5,  minValue: 0,    maxValue: 1 },
  { name: 'mix',     label: 'Mix',     defaultValue: 1,    minValue: 0,    maxValue: 1 }
];

function bqLP(fc, sr) {
  var w = 2 * Math.PI * fc / sr;
  var c = Math.cos(w), s = Math.sin(w);
  var alpha = s / Math.sqrt(2);
  var a0 = 1 + alpha;
  return [(1 - c) * 0.5 / a0, (1 - c) / a0, (1 - c) * 0.5 / a0, -2 * c / a0, (1 - alpha) / a0];
}
function bqHP(fc, sr) {
  var w = 2 * Math.PI * fc / sr;
  var c = Math.cos(w), s = Math.sin(w);
  var alpha = s / Math.sqrt(2);
  var a0 = 1 + alpha;
  return [(1 + c) * 0.5 / a0, -(1 + c) / a0, (1 + c) * 0.5 / a0, -2 * c / a0, (1 - alpha) / a0];
}
function bqPeak(fc, gainDb, q, sr) {
  var A = Math.pow(10, gainDb / 40);
  var w = 2 * Math.PI * fc / sr;
  var c = Math.cos(w), s = Math.sin(w);
  var alpha = s / (2 * q);
  var a0 = 1 + alpha / A;
  return [(1 + alpha * A) / a0, (-2 * c) / a0, (1 - alpha * A) / a0,
          (-2 * c) / a0, (1 - alpha / A) / a0];
}

MegaphoneProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var lowCut = parameters.lowCut[0];
  var highCut = Math.max(lowCut + 200, parameters.highCut[0]);
  var honkDb = parameters.honk[0];
  var crunch = parameters.crunch[0];
  var mix = parameters.mix[0];

  var hp = bqHP(lowCut, sampleRate);
  var pk = bqPeak(2000, honkDb, 2.5, sampleRate);
  var lp = bqLP(highCut, sampleRate);
  var k = 1 + crunch * 12;
  var normalize = 1 / Math.tanh(k);

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    // HP
    var y1 = hp[0]*x + hp[1]*this.hpA[0] + hp[2]*this.hpA[1] - hp[3]*this.hpB[0] - hp[4]*this.hpB[1];
    this.hpA[1] = this.hpA[0]; this.hpA[0] = x;
    this.hpB[1] = this.hpB[0]; this.hpB[0] = y1;
    // Honk
    var y2 = pk[0]*y1 + pk[1]*this.pkA[0] + pk[2]*this.pkA[1] - pk[3]*this.pkB[0] - pk[4]*this.pkB[1];
    this.pkA[1] = this.pkA[0]; this.pkA[0] = y1;
    this.pkB[1] = this.pkB[0]; this.pkB[0] = y2;
    // Asymmetric clip — positive half hits harder than negative.
    var clipped;
    if (y2 >= 0) clipped = Math.tanh(y2 * k) * normalize;
    else clipped = Math.tanh(y2 * k * 0.55) * normalize;
    // LP after distortion — kill the ultrasonic crud the clipper added.
    var y3 = lp[0]*clipped + lp[1]*this.lpA[0] + lp[2]*this.lpA[1] - lp[3]*this.lpB[0] - lp[4]*this.lpB[1];
    this.lpA[1] = this.lpA[0]; this.lpA[0] = clipped;
    this.lpB[1] = this.lpB[0]; this.lpB[0] = y3;
    output[i] = x * (1 - mix) + y3 * mix;
  }
  return true;
};

registerProcessor('megaphone', MegaphoneProcessor);
