// Vinyl — old-record texture: pink-noise hiss plus randomly-triggered
// crackle pops, mixed into the signal. Pink noise comes from a small
// Voss-McCartney generator (5 octave-band white sources, each refreshed
// at a different rate keyed off the running counter's lowest set bit).
// Crackles fire at a Poisson rate scaled by the knob and decay through
// a one-pole envelope so they have an analog "snap" rather than a click.

function VinylProcessor() {
  this.pink = [0, 0, 0, 0, 0];
  this.counter = 0;
  this.crackleState = 0;
}
VinylProcessor.parameterDescriptors = [
  { name: 'hiss',    label: 'Hiss',    defaultValue: 0.12, minValue: 0, maxValue: 1 },
  { name: 'crackle', label: 'Crackle', defaultValue: 0.30, minValue: 0, maxValue: 1 },
  { name: 'mix',     label: 'Mix',     defaultValue: 1,    minValue: 0, maxValue: 1 }
];
VinylProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0], output = outputs[0][0];
  if (!input || !output) return true;
  var hiss = parameters.hiss[0];
  var crackle = parameters.crackle[0];
  var mix = parameters.mix[0];
  for (var i = 0; i < input.length; i++) {
    this.counter = (this.counter + 1) | 0;
    // Voss-McCartney: refresh band whose bit position equals the
    // lowest set bit of the counter — gives 1/f noise spectrum.
    var lsb = 0;
    var c = this.counter;
    while ((c & 1) === 0 && lsb < this.pink.length - 1) {
      lsb++;
      c = c >> 1;
    }
    this.pink[lsb] = Math.random() * 2 - 1;
    var pinkSum = 0;
    for (var p = 0; p < this.pink.length; p++) pinkSum += this.pink[p];
    pinkSum *= 0.18;

    if (Math.random() < crackle * 0.0008) {
      this.crackleState = (Math.random() * 2 - 1) * 0.8;
    }
    this.crackleState *= 0.85;

    var dirt = pinkSum * hiss * 0.4 + this.crackleState * crackle;
    output[i] = input[i] + dirt * mix;
  }
  return true;
};

registerProcessor('vinyl', VinylProcessor);
