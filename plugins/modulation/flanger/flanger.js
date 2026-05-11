// Flanger — short variable delay (0.5–8 ms) with feedback, modulated by
// a slow LFO. Identical topology to chorus.js but the delay sits in the
// territory where comb-filter notches are audible as harmonic sweeps —
// the classic "jet plane" whoosh. Feedback turns the notches into
// resonant peaks for a metallic, hollow character.

function FlangerProcessor() {
  this.buf = new Array(4000);
  for (var k = 0; k < this.buf.length; k++) this.buf[k] = 0;
  this.bufLen = this.buf.length;
  this.writeIdx = 0;
  this.phase = 0;
}
FlangerProcessor.parameterDescriptors = [
  { name: 'rate',     label: 'Rate (Hz)',  defaultValue: 0.3, minValue: 0.05, maxValue: 5 },
  { name: 'depth',    label: 'Depth (ms)', defaultValue: 3,   minValue: 0,    maxValue: 8 },
  { name: 'feedback', label: 'Feedback',   defaultValue: 0.6, minValue: 0,    maxValue: 0.95 },
  { name: 'mix',      label: 'Mix',        defaultValue: 0.5, minValue: 0,    maxValue: 1 }
];
FlangerProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var rate = parameters.rate[0];
  var depthMs = parameters.depth[0];
  var feedback = parameters.feedback[0];
  var mix = parameters.mix[0];
  // 0.5 ms minimum so the comb peak never collapses to DC at LFO trough.
  var baseSamp = sampleRate * 0.0005;
  var depthSamp = sampleRate * depthMs * 0.001;
  var phaseInc = (2 * Math.PI * rate) / sampleRate;
  for (var i = 0; i < input.length; i++) {
    var lfo = 0.5 + 0.5 * Math.sin(this.phase);
    var read = this.writeIdx - (baseSamp + depthSamp * lfo);
    while (read < 0) read += this.bufLen;
    var i0 = Math.floor(read);
    var frac = read - i0;
    var i1 = (i0 + 1) % this.bufLen;
    var wet = this.buf[i0] * (1 - frac) + this.buf[i1] * frac;
    this.buf[this.writeIdx] = input[i] + wet * feedback;
    this.writeIdx = (this.writeIdx + 1) % this.bufLen;
    output[i] = input[i] * (1 - mix) + wet * mix;
    this.phase += phaseInc;
    if (this.phase > 2 * Math.PI) this.phase -= 2 * Math.PI;
  }
  return true;
};

registerProcessor('flanger', FlangerProcessor);
