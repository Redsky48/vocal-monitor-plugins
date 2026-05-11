// Saturator — soft-clip distortion with drive + dry/wet mix.
// AudioWorklet-style plugin written in ES5 prototype syntax (the host
// JS engine on mobile doesn't parse ES6 `class`). The contract is the
// same: registerProcessor('id', Ctor) where Ctor has a `process` method
// and an optional `parameterDescriptors` static array.

function SaturatorProcessor() {}
SaturatorProcessor.parameterDescriptors = [
  { name: 'drive', label: 'Drive', defaultValue: 0.5, minValue: 0, maxValue: 1 },
  { name: 'mix',   label: 'Mix',   defaultValue: 1.0, minValue: 0, maxValue: 1 }
];
SaturatorProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0];
  var output = outputs[0][0];
  if (!input || !output) return true;
  var drive = parameters.drive[0];
  var mix = parameters.mix[0];
  var k = 1 + drive * 8;
  for (var i = 0; i < input.length; i++) {
    var wet = Math.tanh(input[i] * k);
    output[i] = input[i] * (1 - mix) + wet * mix;
  }
  return true;
};

registerProcessor('saturator', SaturatorProcessor);
