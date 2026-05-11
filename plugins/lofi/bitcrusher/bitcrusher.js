// Bit Crusher — quantises sample depth and downsamples by holding each
// sample for N hops. Both knobs together give the chiptune / Atari
// flavor; just the bit-depth knob is enough for soft lo-fi crunch.

function BitCrusherProcessor() {
  this.holdCounter = 0;
  this.held = 0;
}
BitCrusherProcessor.parameterDescriptors = [
  { name: 'bits', label: 'Bits',       defaultValue: 8, minValue: 1,  maxValue: 16 },
  { name: 'rate', label: 'Hold (smp)', defaultValue: 1, minValue: 1,  maxValue: 64 }
];
BitCrusherProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0];
  var output = outputs[0][0];
  if (!input || !output) return true;
  var bits = Math.max(1, Math.floor(parameters.bits[0]));
  var hold = Math.max(1, Math.floor(parameters.rate[0]));
  var steps = Math.pow(2, bits - 1);
  for (var i = 0; i < input.length; i++) {
    if (this.holdCounter <= 0) {
      // Quantise to `bits`-bit resolution.
      this.held = Math.round(input[i] * steps) / steps;
      this.holdCounter = hold;
    }
    output[i] = this.held;
    this.holdCounter--;
  }
  return true;
};

registerProcessor('bitcrusher', BitCrusherProcessor);
