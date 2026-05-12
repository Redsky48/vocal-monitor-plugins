// Exciter — Aphex Aural Exciter in spirit. Splits off the highs with a
// 2nd-order Butterworth HP, runs them through a soft asymmetric
// nonlinearity to generate harmonics two octaves above the source, and
// mixes those harmonics back under the dry. The trick: only the highs
// see the saturator, so the body of the voice/mix stays clean — you
// hear the sparkle, not the dirt. Drop it on dull vocal takes or to
// re-air a cassette transfer.

function ExciterProcessor() {
  this.hpA = [0, 0]; this.hpB = [0, 0];
}

ExciterProcessor.parameterDescriptors = [
  { name: 'frequency', label: 'Freq (Hz)', defaultValue: 3500, minValue: 1000, maxValue: 12000 },
  { name: 'drive',     label: 'Drive',     defaultValue: 0.6,  minValue: 0,    maxValue: 1 },
  { name: 'amount',    label: 'Amount',    defaultValue: 0.4,  minValue: 0,    maxValue: 1 }
];

function bqHP(fc, sr) {
  var w = 2 * Math.PI * fc / sr;
  var c = Math.cos(w), s = Math.sin(w);
  var alpha = s / Math.sqrt(2);
  var a0 = 1 + alpha;
  var b0 = (1 + c) * 0.5 / a0;
  var b1 = -(1 + c) / a0;
  var b2 = b0;
  var a1 = -2 * c / a0;
  var a2 = (1 - alpha) / a0;
  return [b0, b1, b2, a1, a2];
}

ExciterProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var fc = parameters.frequency[0];
  var drive = parameters.drive[0];
  var amount = parameters.amount[0];

  var hp = bqHP(fc, sampleRate);
  // Drive scales how hard we hit the curve; 0..1 → 1..15.
  var k = 1 + drive * 14;
  var normFactor = 1 / Math.tanh(k);

  for (var i = 0; i < input.length; i++) {
    var x = input[i];

    // Split off highs.
    var hp1 = hp[0]*x + hp[1]*this.hpA[0] + hp[2]*this.hpA[1] - hp[3]*this.hpB[0] - hp[4]*this.hpB[1];
    this.hpA[1] = this.hpA[0]; this.hpA[0] = x;
    this.hpB[1] = this.hpB[0]; this.hpB[0] = hp1;

    // Asymmetric soft-clip: tanh on positive half, scaled-tanh on
    // negative half. Even-order harmonics > odd-order = airy not gritty.
    var d;
    if (hp1 >= 0) {
      d = Math.tanh(hp1 * k) * normFactor;
    } else {
      d = Math.tanh(hp1 * k * 0.6) * normFactor;
    }
    // The "exciter" component is the difference between the saturated
    // and clean highs — that's the pure harmonic generator output.
    var excited = d - hp1;
    output[i] = x + excited * amount;
  }
  return true;
};

registerProcessor('exciter', ExciterProcessor);
