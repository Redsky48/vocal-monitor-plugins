// Resonator — tuned comb filter, the Karplus-Strong building block. A
// delay line equal to one period of the resonant pitch, with feedback
// through a one-pole lowpass to bleed off treble (= decay). Excite it
// with any input and it rings at that pitch like a struck string. On a
// vocal, it acts like a sympathetic-resonance harmoniser — sing into a
// piano with the sustain pedal down. Drive the feedback near 1.0 for
// near-infinite ringing drones.

function ResonatorProcessor() {
  // 80 Hz at 48 kHz = 600 samples; round up generously.
  var maxLen = Math.ceil(sampleRate / 50);
  this.buf = new Array(maxLen);
  for (var k = 0; k < maxLen; k++) this.buf[k] = 0;
  this.bufLen = maxLen;
  this.idx = 0;
  this.lp = 0;
}

ResonatorProcessor.parameterDescriptors = [
  { name: 'pitch',    label: 'Pitch (Hz)', defaultValue: 220, minValue: 50,  maxValue: 2000 },
  { name: 'feedback', label: 'Feedback',   defaultValue: 0.92, minValue: 0,   maxValue: 0.999 },
  { name: 'damping',  label: 'Damping',    defaultValue: 0.3, minValue: 0,   maxValue: 1 },
  { name: 'mix',      label: 'Mix',        defaultValue: 0.5, minValue: 0,   maxValue: 1 }
];

ResonatorProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var pitch = parameters.pitch[0];
  var fb = parameters.feedback[0];
  var damping = parameters.damping[0];
  var mix = parameters.mix[0];

  // Delay length = one period. Fractional fine tuning via linear interp.
  var period = sampleRate / pitch;
  if (period < 2) period = 2;
  if (period >= this.bufLen) period = this.bufLen - 1;
  var lpA = 1 - damping * 0.95;

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    var read = this.idx - period;
    while (read < 0) read += this.bufLen;
    while (read >= this.bufLen) read -= this.bufLen;
    var i0 = Math.floor(read);
    var frac = read - i0;
    var i1 = i0 + 1; if (i1 >= this.bufLen) i1 = 0;
    var delayed = this.buf[i0] * (1 - frac) + this.buf[i1] * frac;
    // Damping LP inside the feedback loop.
    this.lp = this.lp + lpA * (delayed - this.lp);
    var wet = this.lp;
    this.buf[this.idx] = x + wet * fb;
    this.idx++; if (this.idx >= this.bufLen) this.idx = 0;
    output[i] = x * (1 - mix) + wet * mix;
  }
  return true;
};

registerProcessor('resonator', ResonatorProcessor);
