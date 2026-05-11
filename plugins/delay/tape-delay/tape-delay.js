// Tape Delay — single-tap feedback delay with a one-pole low-pass on
// each repeat (the "tone" knob). Each subsequent echo loses high-end
// just like analogue tape, so a long feedback time slowly degrades
// into a warm low-frequency soup instead of harsh repeating spikes.

function TapeDelayProcessor() {
  // Pre-allocate buffer for the max delay (1.5 s @ assumed-high sample rate).
  // 96 kHz max gives 144 000 samples; round up.
  this.buf = new Array(150000);
  for (var k = 0; k < this.buf.length; k++) this.buf[k] = 0;
  this.bufLen = this.buf.length;
  this.writeIdx = 0;
  this.lpState = 0;
}
TapeDelayProcessor.parameterDescriptors = [
  { name: 'time',     label: 'Time (ms)', defaultValue: 350, minValue: 5,  maxValue: 1500 },
  { name: 'feedback', label: 'Feedback',  defaultValue: 0.4, minValue: 0,  maxValue: 0.95 },
  { name: 'tone',     label: 'Tone',      defaultValue: 0.5, minValue: 0,  maxValue: 1 },
  { name: 'mix',      label: 'Mix',       defaultValue: 0.4, minValue: 0,  maxValue: 1 }
];
TapeDelayProcessor.prototype.process = function(inputs, outputs, parameters) {
  var input = inputs[0][0];
  var output = outputs[0][0];
  if (!input || !output) return true;
  var timeMs = parameters.time[0];
  var feedback = parameters.feedback[0];
  var tone = parameters.tone[0];
  var mix = parameters.mix[0];
  var delaySamples = Math.max(1, Math.floor(sampleRate * timeMs / 1000));
  // One-pole low-pass coefficient: tone=1 → fully open, tone=0 → muffled.
  var lpA = 0.05 + 0.93 * tone;

  for (var i = 0; i < input.length; i++) {
    var readIdx = this.writeIdx - delaySamples;
    while (readIdx < 0) readIdx += this.bufLen;
    var delayed = this.buf[readIdx];

    // Filter the wet path for the next round (in-feedback tone shaping).
    this.lpState = this.lpState + lpA * (delayed - this.lpState);
    var fbSample = this.lpState;

    // Write input + feedback back into the line.
    this.buf[this.writeIdx] = input[i] + fbSample * feedback;
    this.writeIdx = (this.writeIdx + 1) % this.bufLen;

    output[i] = input[i] * (1 - mix) + fbSample * mix;
  }
  return true;
};

registerProcessor('tape-delay', TapeDelayProcessor);
