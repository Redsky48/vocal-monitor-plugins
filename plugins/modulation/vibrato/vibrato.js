// Vibrato — pure pitch wobble: variable delay only, no dry blend. By
// pulling samples from an LFO-swept tap, the playback rate of the
// buffered signal accelerates and decelerates, which the ear hears as
// frequency modulation. Unlike chorus (delay + dry), vibrato doesn't
// produce comb filtering — just the wobble itself.

function VibratoProcessor() {
  this.buf = new Array(4000);
  for (var k = 0; k < this.buf.length; k++) this.buf[k] = 0;
  this.bufLen = this.buf.length;
  this.writeIdx = 0;
  this.phase = 0;
}
VibratoProcessor.parameterDescriptors = [
  { name: 'rate',  label: 'Rate (Hz)',  defaultValue: 5, minValue: 0.5, maxValue: 12 },
  { name: 'depth', label: 'Depth (ms)', defaultValue: 2, minValue: 0,   maxValue: 8 }
];
VibratoProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var rate = parameters.rate[0];
  var depthMs = parameters.depth[0];
  var baseSamp = sampleRate * 0.005;
  var depthSamp = sampleRate * depthMs * 0.001;
  var phaseInc = (2 * Math.PI * rate) / sampleRate;
  for (var i = 0; i < input.length; i++) {
    var lfo = 0.5 + 0.5 * Math.sin(this.phase);
    var read = this.writeIdx - (baseSamp + depthSamp * lfo);
    while (read < 0) read += this.bufLen;
    var i0 = Math.floor(read);
    var frac = read - i0;
    var i1 = (i0 + 1) % this.bufLen;
    output[i] = this.buf[i0] * (1 - frac) + this.buf[i1] * frac;
    this.buf[this.writeIdx] = input[i];
    this.writeIdx = (this.writeIdx + 1) % this.bufLen;
    this.phase += phaseInc;
    if (this.phase > 2 * Math.PI) this.phase -= 2 * Math.PI;
  }
  return true;
};

registerProcessor('vibrato', VibratoProcessor);
