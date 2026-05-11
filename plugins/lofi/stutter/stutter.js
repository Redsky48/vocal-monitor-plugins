// Stutter — beat-repeat. Captures the first slice of every cycle into a
// buffer, then plays that slice back N-1 times before re-arming. Loops
// forever as the cycle wraps, so a held vowel turns into a percussive
// chop. Mix lets you blend live signal under the repeats; at mix=1 the
// stutter fully replaces the dry path.

function StutterProcessor() {
  // 1.5 s max slice = 72 000 samples @ 48 kHz, round up for headroom.
  this.buf = new Array(96000);
  for (var k = 0; k < this.buf.length; k++) this.buf[k] = 0;
  this.cursor = 0;
}
StutterProcessor.parameterDescriptors = [
  { name: 'slice',  label: 'Slice (ms)', defaultValue: 120, minValue: 20, maxValue: 500 },
  { name: 'repeat', label: 'Repeats',    defaultValue: 4,   minValue: 1,  maxValue: 8 },
  { name: 'mix',    label: 'Mix',        defaultValue: 0.7, minValue: 0,  maxValue: 1 }
];
StutterProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var sliceMs = parameters.slice[0];
  var repeats = Math.max(1, Math.floor(parameters.repeat[0]));
  var mix = parameters.mix[0];
  var sliceLen = Math.max(1, Math.floor(sampleRate * sliceMs / 1000));
  if (sliceLen > this.buf.length) sliceLen = this.buf.length;
  var totalLen = sliceLen * repeats;
  for (var i = 0; i < input.length; i++) {
    var phase = this.cursor % totalLen;
    if (phase < sliceLen) {
      // First pass through the cycle: record and pass dry.
      this.buf[phase] = input[i];
      output[i] = input[i];
    } else {
      var inSlice = phase % sliceLen;
      var wet = this.buf[inSlice];
      output[i] = input[i] * (1 - mix) + wet * mix;
    }
    this.cursor++;
  }
  return true;
};

registerProcessor('stutter', StutterProcessor);
