// Chorus — variable delay (~18 ms base) modulated by a slow sine LFO,
// mixed with the dry signal. The 18 ms base keeps the wet path far
// enough from the input that the comb-filter notches never collapse
// into audible coloration — a tighter base would make this sound more
// like a flanger, which is what flanger.js is for.

function ChorusProcessor() {
  this.buf = new Array(20000);
  for (var k = 0; k < this.buf.length; k++) this.buf[k] = 0;
  this.bufLen = this.buf.length;
  this.writeIdx = 0;
  this.phase = 0;
}
ChorusProcessor.parameterDescriptors = [
  { name: 'rate',  label: 'Rate (Hz)',  defaultValue: 0.8,  minValue: 0.1, maxValue: 5 },
  { name: 'depth', label: 'Depth (ms)', defaultValue: 6,    minValue: 0,   maxValue: 15 },
  { name: 'mix',   label: 'Mix',        defaultValue: 0.45, minValue: 0,   maxValue: 1 }
];
ChorusProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var rate = parameters.rate[0];
  var depthMs = parameters.depth[0];
  var mix = parameters.mix[0];
  var baseSamp = sampleRate * 0.018;
  var depthSamp = sampleRate * depthMs * 0.001;
  var phaseInc = (2 * Math.PI * rate) / sampleRate;
  for (var i = 0; i < input.length; i++) {
    var lfo = Math.sin(this.phase);
    var read = this.writeIdx - (baseSamp + depthSamp * lfo);
    while (read < 0) read += this.bufLen;
    var i0 = Math.floor(read);
    var frac = read - i0;
    var i1 = (i0 + 1) % this.bufLen;
    var wet = this.buf[i0] * (1 - frac) + this.buf[i1] * frac;
    this.buf[this.writeIdx] = input[i];
    this.writeIdx = (this.writeIdx + 1) % this.bufLen;
    output[i] = input[i] * (1 - mix) + wet * mix;
    this.phase += phaseInc;
    if (this.phase > 2 * Math.PI) this.phase -= 2 * Math.PI;
  }
  return true;
};

registerProcessor('chorus', ChorusProcessor);
