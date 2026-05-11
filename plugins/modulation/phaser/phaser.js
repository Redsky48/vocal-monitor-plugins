// Phaser — 4-stage allpass cascade whose cutoff is swept by a sine LFO,
// then fed back into itself. Each allpass introduces a 90° phase shift
// at its cutoff; mixed back with the dry signal, the cumulative shift
// creates the deep notch-and-sweep characteristic of the MXR / Small
// Stone-style sound. Feedback sharpens the notches into resonant peaks.

function PhaserProcessor() {
  this.s = [0, 0, 0, 0];   // per-stage delay element
  this.fb = 0;
  this.phase = 0;
}
PhaserProcessor.parameterDescriptors = [
  { name: 'rate',     label: 'Rate (Hz)', defaultValue: 0.4, minValue: 0.05, maxValue: 4 },
  { name: 'depth',    label: 'Depth',     defaultValue: 0.8, minValue: 0,    maxValue: 1 },
  { name: 'feedback', label: 'Feedback',  defaultValue: 0.5, minValue: 0,    maxValue: 0.95 },
  { name: 'mix',      label: 'Mix',       defaultValue: 0.5, minValue: 0,    maxValue: 1 }
];
PhaserProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var rate = parameters.rate[0];
  var depth = parameters.depth[0];
  var feedback = parameters.feedback[0];
  var mix = parameters.mix[0];
  var phaseInc = (2 * Math.PI * rate) / sampleRate;
  for (var i = 0; i < input.length; i++) {
    var lfo = 0.5 + 0.5 * Math.sin(this.phase);
    // Sweep 250 .. 2500 Hz, scaled by depth.
    var cutoff = 250 + depth * 2250 * lfo;
    var w = Math.tan(Math.PI * cutoff / sampleRate);
    var a = (1 - w) / (1 + w);
    var x = input[i] + this.fb * feedback;
    for (var s = 0; s < 4; s++) {
      var y = a * x + this.s[s];
      this.s[s] = x - a * y;
      x = y;
    }
    this.fb = x;
    output[i] = input[i] * (1 - mix) + x * mix;
    this.phase += phaseInc;
    if (this.phase > 2 * Math.PI) this.phase -= 2 * Math.PI;
  }
  return true;
};

registerProcessor('phaser', PhaserProcessor);
