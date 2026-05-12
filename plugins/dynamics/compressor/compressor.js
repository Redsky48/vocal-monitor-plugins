// Compressor — feedforward dynamics processor with smooth attack/release
// envelope detection and a soft knee. The detector uses an absolute-value
// peak follower with separate time constants for rising vs falling
// signals (the classic "attack" and "release" knobs); the gain reduction
// is computed in dB so the ratio knob behaves linearly. Add some makeup
// gain afterwards to bring the level back up.

function CompressorProcessor() {
  this.env = 0;     // detector state
  this.gain = 1;    // smoothed gain-reduction multiplier
}

CompressorProcessor.parameterDescriptors = [
  { name: 'threshold', label: 'Thresh (dB)', defaultValue: -18, minValue: -60, maxValue: 0 },
  { name: 'ratio',     label: 'Ratio',       defaultValue: 4,   minValue: 1,   maxValue: 20 },
  { name: 'attack',    label: 'Att (ms)',    defaultValue: 8,   minValue: 0.1, maxValue: 100 },
  { name: 'release',   label: 'Rel (ms)',    defaultValue: 120, minValue: 5,   maxValue: 1000 },
  { name: 'knee',      label: 'Knee (dB)',   defaultValue: 6,   minValue: 0,   maxValue: 24 },
  { name: 'makeup',    label: 'Makeup (dB)', defaultValue: 0,   minValue: 0,   maxValue: 24 }
];

CompressorProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var thresh = parameters.threshold[0];
  var ratio = parameters.ratio[0];
  var attMs = parameters.attack[0];
  var relMs = parameters.release[0];
  var knee = parameters.knee[0];
  var makeup = parameters.makeup[0];

  // Time constants → one-pole smoothing coefficients.
  var attCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * attMs / 1000));
  var relCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * relMs / 1000));
  var makeupLin = Math.pow(10, makeup / 20);
  var halfKnee = knee * 0.5;
  var invRatio = 1 / ratio;

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    var rect = x < 0 ? -x : x;
    // Peak-follow detector with split time constants.
    var coef = rect > this.env ? attCoef : relCoef;
    this.env = this.env + coef * (rect - this.env);

    // Convert to dB (clamped at -120 to avoid log(0)).
    var envDb = this.env > 1e-6 ? 20 * Math.log(this.env) / Math.LN10 : -120;

    // Soft-knee gain computer. Below thresh - halfKnee: no compression.
    // Above thresh + halfKnee: full ratio compression. In between: smooth
    // quadratic blend so the curve has no audible corner.
    var gr = 0;
    var diff = envDb - thresh;
    if (diff > -halfKnee) {
      if (diff < halfKnee && knee > 0) {
        var t = (diff + halfKnee) / knee; // 0..1 inside knee
        gr = (1 - invRatio) * t * t * halfKnee;
      } else {
        gr = (envDb - thresh) * (1 - invRatio);
      }
    }
    // gr is the amount to subtract from envDb (in dB). Convert to linear.
    var targetGain = Math.pow(10, -gr / 20);
    // Smooth gain in the linear domain (already smoothed via env).
    this.gain = targetGain;

    output[i] = x * this.gain * makeupLin;
  }
  return true;
};

registerProcessor('compressor', CompressorProcessor);
