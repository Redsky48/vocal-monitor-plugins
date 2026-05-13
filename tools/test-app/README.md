# Plugin Test App

A small desktop Swing GUI for trying any of the registry's native
plugins on your PC. Load a WAV or record from the mic, dial the
plugin's sliders, hear the original vs the processed audio, and
optionally export the result back to a WAV. No need to push to the
app or test through the phone.

## Requirements

- JDK 11 or newer (you already have one — it's what compiles the
  plugins' DEX files).
- A working sound card and (for the mic-record button) a default
  microphone.

## Run

From the repo root:

```bash
java tools/test-app/TestApp.java
```

That's it — JEP 330 single-file source-code launch compiles and runs
in one step.

The GUI scans the `plugins/` tree for every folder whose `plugin.json`
declares `"engine": "native"` and lists them in the **Plugin** dropdown.
Switching plugin recompiles its `.java` source on the fly via the
in-process `JavaCompiler` API, so any local edit to a plugin source
takes effect the next time you select it from the dropdown.

## What each button does

| Button | What it does |
|---|---|
| **Plugin ▾** | Pick the plugin to test. Re-compiles its `.java` source on selection. |
| **Load WAV…** | Open a WAV (any sample rate / mono or stereo). Decoded to mono 44.1 kHz internally. |
| **Record 5 s from mic** | Captures 5 s of audio from the default microphone. |
| **Play original** | Plays the loaded/recorded audio through the default output. |
| **Process** | Runs the audio through the current plugin with the slider values. |
| **Play processed** | Plays the processed audio. |
| **Stop** | Stops whichever playback is active. |
| **Save processed WAV…** | Exports the processed audio to a WAV. |

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
