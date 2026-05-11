// Octaver — full-wave rectification produces a signal at 2× the input
// fundamental, perceived as one octave UP. The rectified signal is
// DC-blocked (one-pole high-pass) before mixing so it doesn't shove the
// mix off-center. Classic fuzz-octaver flavour — a single voice singing
// in 8va parallel, with the gritty edges that rectification leaves in
// the upper harmonics.

function OctaverProcessor() {
  this.prevX = 0;
  this.prevY = 0;
}
OctaverProcessor.parameterDescriptors = [
  { name: 'octave', label: 'Octave Up', defaultValue: 0.5, minValue: 0, maxValue: 1 },
  { name: 'dry',    label: 'Dry',       defaultValue: 0.7, minValue: 0, maxValue: 1 }
];
OctaverProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var octave = parameters.octave[0];
  var dry = parameters.dry[0];
  var R = 0.995;
  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    var rect = x >= 0 ? x : -x;
    var y = rect - this.prevX + R * this.prevY;
    this.prevX = rect;
    this.prevY = y;
    // ×2 compensates for the ~halved perceived amplitude after rectify.
    output[i] = x * dry + y * octave * 2;
  }
  return true;
};

registerProcessor('octaver', OctaverProcessor);
