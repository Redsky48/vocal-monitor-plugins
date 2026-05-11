// REPLACE-ME — describe what the effect SOUNDS like in one short paragraph.
// The reader should be able to guess the use case before they read the code.

function ReplaceMeProcessor() {
  // Allocate every buffer / state field here. Anything you mutate inside
  // process() that needs to persist across calls (LFO phase, delay line,
  // filter capacitor, envelope follower) belongs on `this`. Never allocate
  // inside process() — the GC pressure shows up as audible crackle.
  this.phase = 0;
}

ReplaceMeProcessor.parameterDescriptors = [
  // Every entry gets an auto-generated slider in the app. Pick defaults
  // that sound musical the instant the user adds the plugin — they
  // shouldn't have to fiddle just to confirm it's working.
  { name: 'amount', label: 'Amount', defaultValue: 0.5, minValue: 0, maxValue: 1 }
];

ReplaceMeProcessor.prototype.process = function (inputs, outputs, parameters) {
  var input = inputs[0][0];
  var output = outputs[0][0];
  if (!input || !output) return true;

  // K-rate: one value per block. Read once, reuse inside the loop.
  var amount = parameters.amount[0];

  for (var i = 0; i < input.length; i++) {
    // Replace this with your DSP. The example just attenuates by `amount`.
    output[i] = input[i] * amount;
  }

  return true;
};

// The string here is the plugin id — must match the folder name AND the
// `id` field in plugin.json. Otherwise the app can't find the source.
registerProcessor('REPLACE-ME', ReplaceMeProcessor);
