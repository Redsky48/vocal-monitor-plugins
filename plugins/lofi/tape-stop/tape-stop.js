// Tape Stop — variable-speed playback from a continuously-rolling
// buffer. Write head always advances at 1× (capturing live audio); the
// read head advances at the user-set speed. At speed=1 it sounds like a
// short delay, at speed→0 the playback grinds to a halt with the
// pitch dropping accordingly — exactly the tape-deck-pulling-the-plug
// effect heard on countless EDM drops.

function TapeStopProcessor() {
  // 2 s @ 48 kHz is plenty of buffer for slowdown trails.
  this.buf = new Array(96000);
  for (var k = 0; k < this.buf.length; k++) this.buf[k] = 0;
  this.bufLen = this.buf.length;
  this.writeIdx = 0;
  this.readPos = 0;
}
TapeStopProcessor.parameterDescriptors = [
  { name: 'speed', label: 'Speed', defaultValue: 1, minValue: 0, maxValue: 1 },
  { name: 'mix',   label: 'Mix',   defaultValue: 1, minValue: 0, maxValue: 1 }
];
TapeStopProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var speed = parameters.speed[0];
  var mix = parameters.mix[0];
  for (var i = 0; i < input.length; i++) {
    this.buf[this.writeIdx] = input[i];
    this.writeIdx = (this.writeIdx + 1) % this.bufLen;
    var idx = Math.floor(this.readPos) % this.bufLen;
    if (idx < 0) idx += this.bufLen;
    var i1 = (idx + 1) % this.bufLen;
    var frac = this.readPos - Math.floor(this.readPos);
    var wet = this.buf[idx] * (1 - frac) + this.buf[i1] * frac;
    output[i] = input[i] * (1 - mix) + wet * mix;
    this.readPos += speed;
    if (this.readPos >= this.bufLen) this.readPos -= this.bufLen;
  }
  return true;
};

registerProcessor('tape-stop', TapeStopProcessor);
