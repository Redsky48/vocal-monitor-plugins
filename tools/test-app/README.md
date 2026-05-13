# Plugin Test App

A small desktop Swing GUI for trying any of the registry's native
plugins on your PC. Load a WAV or record from the mic, dial the
plugin's sliders, hear the original vs the processed audio, and
optionally export the result back to a WAV. No need to push to the
app or test through the phone.

There's also a **headless CLI companion** (`RunTest.java`) that lets
me (Claude) run plugins against your saved test recording from the
command line and read back numeric stats + PNG waveforms / spectrograms.
See "Saving a default test recording" below.

## Requirements

- JDK 11 or newer (you already have one — it's what compiles the
  plugins' DEX files).
- A working sound card and (for the mic-record button) a default
  microphone.

## Run — easiest way

**Windows:** double-click `tools/test-app/run.bat`.
**macOS / Linux:** in a terminal, `bash tools/test-app/run.sh`
(or `chmod +x` it once and then double-click).

Both launchers locate the repo root automatically and pop a friendly
message if no `java` is on your PATH.

## Run — manual way

From the repo root:

```bash
java tools/test-app/TestApp.java
```

JEP 330 single-file source-code launch — compiles and runs in one step.

The GUI scans the `plugins/` tree for every folder whose `plugin.json`
declares `"engine": "native"` and shows them grouped by category in a
searchable sidebar. Type in the **Filter** box to narrow by name, id,
or category. Picking a plugin recompiles its `.java` source on the fly
via the in-process `JavaCompiler` API, so any local edit to a plugin
source takes effect the next time you select it from the tree.

If the selected plugin implements `VocalMonitorVisualPlugin` (custom
canvas-mode UI), its panel pops open automatically in a draggable
window — drag the dark title bar to move, click **X** to close. The
**Open Plugin UI** button at the bottom re-opens it after a close.

## What each button does

| Button | What it does |
|---|---|
| **Sidebar tree** | Browse by category, type in **Filter** to narrow. Click a plugin to load it. |
| **Load WAV...** | Open a WAV (any sample rate / mono or stereo). Decoded to mono 44.1 kHz internally. |
| **Record 5 s from mic** | Captures 5 s of audio from the default microphone. |
| **Play original** | Plays the loaded/recorded audio through the default output. |
| **Process** | Runs the audio through the current plugin with the slider values. |
| **Play processed** | Plays the processed audio. |
| **Stop** | Stops whichever playback is active. |
| **Save processed WAV...** | Exports the processed audio to a WAV. |
| **Open Plugin UI** | Re-open the visual plugin panel (enabled only for `VocalMonitorVisualPlugin` implementers). |

The parameter sliders are auto-generated from each plugin's
`parameterNames()` / `parameterMin/Max/Default()` so they match the
plugin's actual API one-for-one. The displayed value next to each
slider is the float value being passed to `setParameter()`.

## Tips for testing Auto-Tune

1. Pick **Auto-Tune** from the dropdown.
2. Either record a quick sung phrase, or load a vocal WAV.
3. Set **key** and **scale** to whatever the take is in.
   (key: 0 = C, 1 = C#, … 11 = B; scale: 0 chromatic, 1 major, 2 minor,
   3 harmonic minor, 4 pentatonic major, 5 pentatonic minor.)
4. Try **preset** values 1–6 to hear the built-in voicings, or leave
   it at 0 (Custom) and dial Retune / Humanize / Strength / Formant
   by hand.
5. **Process**, then **Play processed**, compare with **Play original**.
6. **Save processed WAV…** to keep the result.

## Troubleshooting

**"No JavaCompiler available — need JDK, not just JRE."**
Install a JDK (Adoptium / Microsoft / Oracle — anything ≥ 11). The
single-file launcher needs `javax.tools.JavaCompiler`, which only
ships with the JDK.

**Plugin selection throws "Compile failed"**
The plugin's `.java` source has a syntax or symbol error. Open the
file in your editor and check the compile diagnostics shown in the
error dialog.

**Mic record doesn't capture anything / silent**
Check the OS-level default input device. Java picks whatever's
default. There's no input-device selector in this minimal harness.

**Audio sounds glitchy at start**
Auto-Tune in particular needs ~20 ms of lookahead before the first
real output sample emerges. Skip that bit when listening.

## Saving a default test recording (for headless debugging)

The GUI has a ⭐ **Save as default test input** button. Clicking it
writes the currently-loaded (or just-recorded) audio to a fixed path:

```
tools/test-app/test-input.wav
```

That's the file `RunTest.java` picks up. Once it exists, anyone with
a checkout — including AI assistants iterating on the plugin code —
can run a one-line headless plugin sweep against the same recording:

```bash
java tools/test-app/RunTest.java                       # auto-tune, defaults
java tools/test-app/RunTest.java --plugin compressor
java tools/test-app/RunTest.java --params "preset=2,key=0,scale=1"
java tools/test-app/RunTest.java --plugin auto-tune --params "retune=0,strength=1,formant=1"
```

Each run produces, alongside `test-input.wav`:

| File | What it contains |
|---|---|
| `test-output.wav` | The processed audio. |
| `test-original.png` / `test-processed.png` | Waveform PNGs (peak-tracked min/max-per-pixel rendering). |
| `test-spec-original.png` / `test-spec-processed.png` | Spectrograms — 1024-pt Hann FFT, hop 256, 0–12 kHz, viridis. |
| `test-stats.json` | Numeric stats: peak, RMS, DC bias, NaN / Inf / clipped counts, processing-time, **click locations** (3rd-order linear-predictor residual outliers — the sample-level spikes you saw in the GUI's processed waveform). |

The click list is the most useful diagnostic when chasing audible
artefacts. Each entry has the sample index, its time-offset in
seconds, and how many times louder its residual was vs the median
— so you can correlate a "tick" you hear at, say, 1.3 s with an
actual reported spike at sample ≈ 57 300.

## Iterating on Auto-Tune with the CLI

Workflow Claude uses when chasing an artefact you reported:

1. You record + ⭐-save → `test-input.wav`.
2. Claude edits `plugins/pitch/auto-tune/AutoTune.java`.
3. Claude runs `java tools/test-app/RunTest.java`, reads back
   `test-stats.json` (counts of clicks / NaN / clipping) and inspects
   `test-spec-processed.png` for spectral artefacts.
4. Iterate until clicksTotal drops and the spectrogram looks clean.
