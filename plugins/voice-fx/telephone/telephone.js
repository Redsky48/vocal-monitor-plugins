// Telephone — band-passes the signal into the 300 Hz–3.4 kHz range
// (POTS handset bandwidth) and adds a touch of compression-style
// distortion to mimic GSM codec artefacts. Drop on a vocal for the
// classic "caller on the other end of the line" effect.

function TelephoneProcessor() {
  // Two cascaded one-pole filters: HPF then LPF = band-pass.
  this.hpPrev = 0;
  this.hpOut = 0;
  this.lpOut = 0;
}
TelephoneProcessor.parameterDescriptors = [
  { name: 'lowCut',  label: 'Low Hz',  defaultValue: 300,  minValue: 100, maxValue: 1000 },
  { name: 'highCut', label: 'High Hz', defaultValue: 3400, minValue: 1500,maxValue: 8000 },
  { name: 'crunch',  label: 'Crunch',  defaultValue: 0.3,  minValue: 0,   maxValue: 1 },
  { name: 'mix',     label: 'Mix',     defaultValue: 1.0,  minValue: 0,   maxValue: 1 }
];
TelephoneProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0];
  var output = outputs[0][0];
  if (!input || !output) return true;
  var lowCut = parameters.lowCut[0];
  var highCut = Math.max(lowCut + 100, parameters.highCut[0]);
  var crunch = parameters.crunch[0];
  var mix = parameters.mix[0];

  // One-pole coefficients (RC form): smaller alpha → tighter cutoff.
  var dt = 1 / sampleRate;
  var hpRc = 1 / (2 * Math.PI * lowCut);
  var hpA = hpRc / (hpRc + dt);
  var lpRc = 1 / (2 * Math.PI * highCut);
  var lpA = dt / (lpRc + dt);

  for (var i = 0; i < input.length; i++) {
    var x = input[i];
    // HPF
    this.hpOut = hpA * (this.hpOut + x - this.hpPrev);
    this.hpPrev = x;
    // LPF on the HPF output
    this.lpOut = this.lpOut + lpA * (this.hpOut - this.lpOut);
    // Soft saturation for that codec crunch
    var k = 1 + crunch * 12;
    var wet = Math.tanh(this.lpOut * k) / Math.tanh(k);
    output[i] = x * (1 - mix) + wet * mix;
  }
  return true;
};

registerProcessor('telephone', TelephoneProcessor);
