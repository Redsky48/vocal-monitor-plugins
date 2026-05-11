// Auto-Wah — envelope-following band-pass that opens / closes based on
// the input level. Loud notes push the filter up, quiet notes let it
// fall — same "quack" you hear on funky guitar / synth bass with a
// MuTron III. The Biquad coefficient maths is the standard
// Robert Bristow-Johnson cookbook (public domain).

function AutoWahProcessor() {
  this.env = 0;
  // Biquad state (Direct Form II Transposed).
  this.z1 = 0;
  this.z2 = 0;
}
AutoWahProcessor.parameterDescriptors = [
  { name: 'sensitivity', label: 'Sense',    defaultValue: 0.6, minValue: 0,    maxValue: 1 },
  { name: 'minFreq',     label: 'Min Hz',   defaultValue: 250, minValue: 60,   maxValue: 2000 },
  { name: 'maxFreq',     label: 'Max Hz',   defaultValue: 2500,minValue: 400,  maxValue: 8000 },
  { name: 'q',           label: 'Q',        defaultValue: 4,   minValue: 0.5,  maxValue: 12 },
  { name: 'attack',      label: 'Attack ms',defaultValue: 5,   minValue: 0.5,  maxValue: 200 },
  { name: 'release',     label: 'Rel ms',   defaultValue: 80,  minValue: 5,    maxValue: 500 }
];
AutoWahProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0];
  var output = outputs[0][0];
  if (!input || !output) return true;
  var sense = parameters.sensitivity[0];
  var minF = parameters.minFreq[0];
  var maxF = Math.max(minF + 50, parameters.maxFreq[0]);
  var q = parameters.q[0];
  var attackCoef = Math.exp(-1 / (sampleRate * parameters.attack[0] * 0.001));
  var releaseCoef = Math.exp(-1 / (sampleRate * parameters.release[0] * 0.001));

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    // Envelope: full-wave rectify then attack/release follower.
    var rect = Math.abs(x);
    var coef = rect > this.env ? attackCoef : releaseCoef;
    this.env = rect + (this.env - rect) * coef;

    // Map env (0..1-ish, capped at ~0.7 typical voice peak) to cutoff
    // in log frequency so a small env change near min-freq sweeps
    // similarly to one near max-freq.
    var drive = Math.min(1, this.env * (1 + sense * 8));
    var logMin = Math.log(minF);
    var logMax = Math.log(maxF);
    var cutoff = Math.exp(logMin + (logMax - logMin) * drive);

    // Bandpass biquad coefficients (constant 0 dB peak gain).
    var w0 = 2 * Math.PI * cutoff / sampleRate;
    var sinW = Math.sin(w0);
    var cosW = Math.cos(w0);
    var alpha = sinW / (2 * q);
    var b0 = alpha;
    var b1 = 0;
    var b2 = -alpha;
    var a0 = 1 + alpha;
    var a1 = -2 * cosW;
    var a2 = 1 - alpha;
    var nb0 = b0 / a0, nb1 = b1 / a0, nb2 = b2 / a0;
    var na1 = a1 / a0, na2 = a2 / a0;

    var y = nb0 * x + this.z1;
    this.z1 = nb1 * x - na1 * y + this.z2;
    this.z2 = nb2 * x - na2 * y;
    output[i] = y;
  }
  return true;
};

registerProcessor('auto-wah', AutoWahProcessor);
