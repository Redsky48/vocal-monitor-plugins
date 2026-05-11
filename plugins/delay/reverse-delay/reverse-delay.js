// Reverse Delay — record a window of length T, then play it back in
// reverse. As the write head sweeps forward and the read head sweeps
// backward through the same chunk, you hear the input rolled backwards
// — the trademark sound from Pink Floyd "Empty Spaces" and countless
// shoegaze records. Feedback re-injects the reversed wet into the line,
// piling up nested reversals into a swelling cloud.

function ReverseDelayProcessor() {
  // 4 s max @ 48 kHz.
  this.buf = new Array(192000);
  for (var k = 0; k < this.buf.length; k++) this.buf[k] = 0;
  this.bufLen = this.buf.length;
  this.writeIdx = 0;
  this.readOffset = 0;
}
ReverseDelayProcessor.parameterDescriptors = [
  { name: 'time',     label: 'Time (ms)', defaultValue: 600, minValue: 100, maxValue: 4000 },
  { name: 'feedback', label: 'Feedback',  defaultValue: 0.3, minValue: 0,   maxValue: 0.9 },
  { name: 'mix',      label: 'Mix',       defaultValue: 0.5, minValue: 0,   maxValue: 1 }
];
ReverseDelayProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var timeMs = parameters.time[0];
  var feedback = parameters.feedback[0];
  var mix = parameters.mix[0];
  var len = Math.max(64, Math.floor(sampleRate * timeMs / 1000));
  if (len > this.bufLen) len = this.bufLen;
  for (var i = 0; i < input.length; i++) {
    // Read N samples back, stepping backwards through the chunk.
    var idx = this.writeIdx - 1 - this.readOffset;
    while (idx < 0) idx += this.bufLen;
    var wet = this.buf[idx];
    this.buf[this.writeIdx] = input[i] + wet * feedback;
    this.writeIdx = (this.writeIdx + 1) % this.bufLen;
    this.readOffset++;
    if (this.readOffset >= len) this.readOffset = 0;
    output[i] = input[i] * (1 - mix) + wet * mix;
  }
  return true;
};

registerProcessor('reverse-delay', ReverseDelayProcessor);
