# Professional Vocal Analysis — plugin pack work plan

12 plugins under the new `vocal-analysis` category. Each is a
**pass-through audio plugin** — they measure and visualise, they
do not modify the signal. All Crystal/Auto-Tune-grade theming
(yellow-on-black panel, canvas-mode UI, touch handling stubs).

## Build order

Ordered so that earlier plugins establish DSP foundations that
later ones can copy / refine (each plugin's source is standalone,
but the per-DSP code patterns get reused).

| # | Plugin id            | Core DSP                                          | Status |
|---|---------------------|---------------------------------------------------|--------|
| 1 | `vocal-spectrum`    | STFT (radix-2 FFT, Hann, hop)                     | [x] |
| 2 | `pitch-accuracy`    | YIN + per-frame cents-from-target + drift trend  | [x] |
| 3 | `vibrato-analyzer`  | Pitch contour autocorrelation → rate + depth      | [x] |
| 4 | `formant-tracker`   | 12th-order LPC autocorrelation → F1/F2/F3 peaks  | [x] |
| 5 | `sibilance-detector`| Band energy 5–10 kHz with transient peak picking  | [x] |
| 6 | `resonance-monitor` | Singer's formant band 2–4 kHz vs total RMS        | [x] |
| 7 | `breathiness-meter` | Harmonic-to-noise ratio from YIN + spectral noise | [x] |
| 8 | `vocal-dynamics`    | RMS / peak / LUFS (K-weighted)                    | [x] |
| 9 | `articulation`      | Onset / transient detector + spectral flux        | [x] |
| 10| `vocal-stability`   | Composite of pitch/volume/formant variance        | [x] |
| 11| `register-detector` | Pitch + spectral slope + harmonic richness        | [x] |
| 12| `vocal-fatigue`     | Long-window trend of stability + brightness loss  | [x] |

**12 / 12 plugins built and compiling cleanly to .dex.**

## Per-plugin checklist

Every plugin in the category needs:

- [ ] Folder `plugins/vocal-analysis/<id>/`
- [ ] `<Name>.java` — implements `VocalMonitorNativePlugin` +
      `VocalMonitorVisualPlugin`. Pass-through audio.
- [ ] `plugin.json` — `ui_kind: "canvas"`, sensible aspect /
      min_height_dp, description aimed at vocalists.
- [ ] Render uses house theme (light grey bg, white cards, accent
      yellow for highlights, section colour for the analysis type).
- [ ] At least one factory preset showing how to read the display.
- [ ] Compiles to `.dex` cleanly via `npm run build:native`.

## Shared aesthetic

So the whole pack reads as one suite:

- Background: `#0E0F12` (slightly darker than Crystal) — this
  category is "measurement instruments", not "musical effects".
- Panel cards: `#1A1B1F` with thin `#2A2B2F` border.
- Text: `#E6E6EA` bright, `#8A8B8F` dim.
- Accent: still `#F5C842` for the live "current value" marker.
- Per-plugin signature colour for its measurement (matches the
  spectrum/category):
  - vocal-spectrum:    cyan `#5BD9E0`
  - pitch-accuracy:    green `#6FE07A` (in-tune) / red `#E0606A`
  - vibrato-analyzer:  pink `#E36C9C`
  - formant-tracker:   orange `#EE8A2C`
  - sibilance:         hot red `#E34855`
  - resonance:         gold `#F5C842`
  - breathiness:       sky blue `#6DD3E0`
  - vocal-dynamics:    yellow `#F5C842`
  - articulation:      mint `#4FCB60`
  - vocal-stability:   purple `#A060E0`
  - register:          teal `#3FB8B8`
  - vocal-fatigue:     amber `#FFA040`

## Tracking

After each plugin lands:
1. Tick its box above.
2. Append a short "what it actually does" paragraph under its row.
3. Commit with `vocal-analysis: <id> — first cut`.
