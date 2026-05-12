// De-esser — sibilance ducker. A split-band design: the input is fed to
// a sidechain high-pass (around 5–8 kHz, the sssss region) and an
// envelope follower on that band drives a gain-reduction multiplier that
// is then applied only to the high-frequency band of the main signal.
// Low/mid stays untouched, so the voice keeps its body — only the
// harsh "s"es get tamed. Much more transparent than a wideband compressor.

function DeEsserProcessor() {
  // Linkwitz-Riley 2nd-order crossover state (highpass + lowpass at the
  // split frequency, summed back together = flat). We keep separate
  // biquad delay elements for the main band and sidechain detector.
  this.hpA = [0, 0]; this.hpB = [0, 0]; // main path HP biquad
  this.lpA = [0, 0]; this.lpB = [0, 0]; // main path LP biquad
  this.scA = [0, 0]; this.scB = [0, 0]; // sidechain HP biquad (steeper)
  this.scA2 = [0, 0]; this.scB2 = [0, 0]; // second SC stage
  this.env = 0;
}

DeEsserProcessor.parameterDescriptors = [
  { name: 'frequency', label: 'Freq (Hz)',  defaultValue: 6500, minValue: 2000, maxValue: 12000 },
  { name: 'threshold', label: 'Thresh (dB)', defaultValue: -28,  minValue: -60, maxValue: 0 },
  { name: 'reduction', label: 'Max GR (dB)', defaultValue: 12,   minValue: 0,   maxValue: 24 },
  { name: 'release',   label: 'Rel (ms)',    defaultValue: 60,   minValue: 5,   maxValue: 400 }
];

// Biquad helper: compute coefficients for a 2nd-order Butterworth HP/LP.
// Returns [b0, b1, b2, a1, a2] for the direct-form-1 step.
function bqLP(fc, sr) {
  var w = 2 * Math.PI * fc / sr;
  var c = Math.cos(w), s = Math.sin(w);
  var alpha = s / Math.sqrt(2);
  var a0 = 1 + alpha;
  var b0 = (1 - c) * 0.5 / a0;
  var b1 = (1 - c) / a0;
  var b2 = b0;
  var a1 = -2 * c / a0;
  var a2 = (1 - alpha) / a0;
  return [b0, b1, b2, a1, a2];
}
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

DeEsserProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var fc = parameters.frequency[0];
  var threshDb = parameters.threshold[0];
  var maxGrDb = parameters.reduction[0];
  var relMs = parameters.release[0];

  var lp = bqLP(fc, sampleRate);
  var hp = bqHP(fc, sampleRate);

  // Time constants.
  var attCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * 0.001)); // 1 ms
  var relCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * relMs / 1000));
  var threshLin = Math.pow(10, threshDb / 20);
  var maxGrLin = Math.pow(10, -maxGrDb / 20);

  for (var i = 0; i < input.length; i++) {
    var x = input[i];

    // --- Main path: 2nd-order LR crossover. ---
    var lpOut = lp[0]*x + lp[1]*this.lpA[0] + lp[2]*this.lpA[1] - lp[3]*this.lpB[0] - lp[4]*this.lpB[1];
    this.lpA[1] = this.lpA[0]; this.lpA[0] = x;
    this.lpB[1] = this.lpB[0]; this.lpB[0] = lpOut;

    var hpOut = hp[0]*x + hp[1]*this.hpA[0] + hp[2]*this.hpA[1] - hp[3]*this.hpB[0] - hp[4]*this.hpB[1];
    this.hpA[1] = this.hpA[0]; this.hpA[0] = x;
    this.hpB[1] = this.hpB[0]; this.hpB[0] = hpOut;

    // --- Sidechain: two cascaded HPs = 24 dB/oct, very selective. ---
    var sc1 = hp[0]*x + hp[1]*this.scA[0] + hp[2]*this.scA[1] - hp[3]*this.scB[0] - hp[4]*this.scB[1];
    this.scA[1] = this.scA[0]; this.scA[0] = x;
    this.scB[1] = this.scB[0]; this.scB[0] = sc1;
    var sc = hp[0]*sc1 + hp[1]*this.scA2[0] + hp[2]*this.scA2[1] - hp[3]*this.scB2[0] - hp[4]*this.scB2[1];
    this.scA2[1] = this.scA2[0]; this.scA2[0] = sc1;
    this.scB2[1] = this.scB2[0]; this.scB2[0] = sc;

    var rect = sc < 0 ? -sc : sc;
    var coef = rect > this.env ? attCoef : relCoef;
    this.env = this.env + coef * (rect - this.env);

    // Gain on the HF band only.
    var gain = 1;
    if (this.env > threshLin) {
      gain = threshLin / this.env;
      if (gain < maxGrLin) gain = maxGrLin;
    }

    output[i] = lpOut + hpOut * gain;
  }
  return true;
};

registerProcessor('de-esser', DeEsserProcessor);
