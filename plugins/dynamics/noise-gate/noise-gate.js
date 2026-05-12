// Noise Gate — opens when the input exceeds an open threshold, closes
// when it drops below a (lower) close threshold. The gap between the
// two thresholds is the hysteresis — without it, a signal hovering
// right at threshold would chatter the gate open/closed every sample.
// Attack/hold/release shape the envelope so the gate opening doesn't
// click and brief dips during a held note don't drop the gate.

function NoiseGateProcessor() {
  this.env = 0;
  this.gain = 0;
  this.state = 0;           // 0 = closed, 1 = open
  this.holdSamples = 0;
}

NoiseGateProcessor.parameterDescriptors = [
  { name: 'threshold',  label: 'Thresh (dB)',  defaultValue: -45, minValue: -80, maxValue: 0 },
  { name: 'hysteresis', label: 'Hyst (dB)',    defaultValue: 6,   minValue: 0,   maxValue: 24 },
  { name: 'attack',     label: 'Att (ms)',     defaultValue: 2,   minValue: 0.1, maxValue: 50 },
  { name: 'hold',       label: 'Hold (ms)',    defaultValue: 30,  minValue: 0,   maxValue: 500 },
  { name: 'release',    label: 'Rel (ms)',     defaultValue: 80,  minValue: 5,   maxValue: 1000 }
];

NoiseGateProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var threshDb = parameters.threshold[0];
  var hystDb = parameters.hysteresis[0];
  var attMs = parameters.attack[0];
  var holdMs = parameters.hold[0];
  var relMs = parameters.release[0];

  var openLin = Math.pow(10, threshDb / 20);
  var closeLin = Math.pow(10, (threshDb - hystDb) / 20);
  var envCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * 0.005));
  var attCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * attMs / 1000));
  var relCoef = 1 - Math.exp(-1 / Math.max(1, sampleRate * relMs / 1000));
  var holdN = Math.floor(holdMs * sampleRate / 1000);

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    var rect = x < 0 ? -x : x;
    this.env = this.env + envCoef * (rect - this.env);

    // State machine with hysteresis. Open on rising-edge of openLin,
    // close only after env drops below closeLin AND hold has elapsed.
    if (this.state === 0) {
      if (this.env > openLin) {
        this.state = 1;
        this.holdSamples = holdN;
      }
    } else {
      if (this.env < closeLin) {
        if (this.holdSamples > 0) this.holdSamples--;
        else this.state = 0;
      } else {
        this.holdSamples = holdN;
      }
    }
    var target = this.state;
    var coef = target > this.gain ? attCoef : relCoef;
    this.gain = this.gain + coef * (target - this.gain);

    output[i] = x * this.gain;
  }
  return true;
};

registerProcessor('noise-gate', NoiseGateProcessor);
