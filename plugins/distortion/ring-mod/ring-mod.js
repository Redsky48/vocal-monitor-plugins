// Ring Modulator — multiplies the input by a sine carrier. Output spectrum
// is the sum-and-difference of every input partial with the carrier, so
// harmonic content scrambles into inharmonic sidebands. At low carrier
// frequencies this is a tremolo; in the speech-band it produces the
// metallic Dalek voice; above 1 kHz it dissolves the source into bell-like
// inharmonic textures.

function RingModProcessor() {
  this.phase = 0;
}
RingModProcessor.parameterDescriptors = [
  { name: 'frequency', label: 'Carrier (Hz)', defaultValue: 220, minValue: 1, maxValue: 2000 },
  { name: 'mix',       label: 'Mix',          defaultValue: 1,   minValue: 0, maxValue: 1 }
];
RingModProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var freq = parameters.frequency[0];
  var mix = parameters.mix[0];
  var phaseInc = (2 * Math.PI * freq) / sampleRate;
  for (var i = 0; i < input.length; i++) {
    var mod = Math.sin(this.phase);
    var wet = input[i] * mod;
    output[i] = input[i] * (1 - mix) + wet * mix;
    this.phase += phaseInc;
    if (this.phase > 2 * Math.PI) this.phase -= 2 * Math.PI;
  }
  return true;
};

registerProcessor('ring-mod', RingModProcessor);
