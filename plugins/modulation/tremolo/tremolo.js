// Tremolo — amplitude modulated by a sine LFO. Classic surf-rock /
// vintage amp "wobble". The LFO phase is held on the instance so the
// modulation continues smoothly across process() blocks within one
// render — without that the wobble would reset every 1024 samples and
// you'd hear a clicky chop instead of a glide.

function TremoloProcessor() {
  this.phase = 0;
}
TremoloProcessor.parameterDescriptors = [
  { name: 'rate',  label: 'Rate (Hz)', defaultValue: 5,    minValue: 0.1, maxValue: 20 },
  { name: 'depth', label: 'Depth',     defaultValue: 0.6,  minValue: 0,   maxValue: 1 }
];
TremoloProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0];
  var output = outputs[0][0];
  if (!input || !output) return true;
  var rate = parameters.rate[0];
  var depth = parameters.depth[0];
  var phaseInc = (2 * Math.PI * rate) / sampleRate;
  for (var i = 0; i < input.length; i++) {
    // LFO range: 1 - depth .. 1 (never inverts polarity — preserves
    // formants and avoids the comb-filtery sound of through-zero mod).
    var lfo = 1 - depth * 0.5 * (1 - Math.cos(this.phase));
    output[i] = input[i] * lfo;
    this.phase += phaseInc;
    if (this.phase > 2 * Math.PI) this.phase -= 2 * Math.PI;
  }
  return true;
};

registerProcessor('tremolo', TremoloProcessor);
