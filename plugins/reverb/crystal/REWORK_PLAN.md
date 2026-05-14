# Crystal — rework towards true Crystalline parity

Tracking document for the audio-DSP corrections raised in the
2026-05-15 honest review. Each item is a real algorithmic gap (not
a UI tweak). Updated as work progresses.

**Honest baseline (before this rework):**
- Function-coverage parity: ~88% (names match)
- DSP idea similarity: ~72%
- Proven sonic 1:1: **0%** (never A/B tested)

**Goal of this rework:** raise DSP idea similarity to ~90% and
ship A/B testing infrastructure so future iterations can chase the
last 10% with real measurements instead of guesses.

---

## Items

### 1. SHIMMER — dual mode

**Problem:** current impl is a +1/+2/+3 octave pitch shifter
(granular PSOLA in feedback). Crystalline's SHIMMER is *high-
frequency decay extension* — HF bins decay 2×/4×/6× slower than
the rest of the spectrum, with an adjustable cutoff. Different
effect.

**Fix:**
- Add `shimmerMode` param (0 = HF Decay, 1 = Octave).
- HF Decay mode: 1-pole HP at `shimmerCut` Hz pulls out the high
  band, feeds it back into the tank with its own elongated decay
  (`feedbackG_hf = feedbackG ^ (1 / multiplier)`), so HF takes
  2×/4×/6× longer to fall to silence.
- Octave mode: keep current implementation as the second mode.
- UI: toggle the two modes via a small "HF / OCT" dot in the
  SHIMMER cell next to the 2×/4×/6× pills.

**Files:** Crystal.java
**Estimated work:** 45 min
**Status:** [x] DONE — added `shimmerMode` param (0 = HF Decay,
1 = Octave). HF Decay path runs a per-tank HP at `shimmerCut`
followed by a slower-decay loop with coef `feedbackG ^ (1 / mul)`
where mul is 2/4/6. HF bins now genuinely decay slower than the
main spectrum, matching Crystalline's documented behaviour.
Octave mode kept intact as the second option.

---

### 2. REVERSE — real reverse playback (not delay-with-readback)

**Problem:** current impl is `revR = revW - bufLen + 1`, which is
just a fixed 400 ms delay. There's no actual reversal of audio in
time.

**Fix:**
- 8-segment ring (~50 ms per segment).
- Write head fills the active segment forward.
- When ON, read from the most-recently-completed segment with the
  read pointer travelling BACKWARDS (descending index).
- Hann-window crossfade between consecutive reversed segments to
  hide the seams.
- Net effect: each ~50 ms chunk of wet plays back in reverse,
  giving the true Twin-Peaks "pre-attack swell".

**Files:** Crystal.java
**Estimated work:** 45 min
**Status:** [x] DONE — replaced the delay-with-readback hack with
true segmented reverse playback. 8 × ~50 ms segments, write head
fills segments forward, read head plays the most-recently-finished
segment backwards (counts down from segLen-1 to 0), then steps to
the previous segment.  Hann crossfade between adjacent reversed
segments hides seams.

---

### 3. FREEZE — input-disconnect + crossfade + DC blocker + safety

**Problem:** current `feedbackG = 1.0` works but lacks the
production-grade safeguards Crystalline implements: input keeps
feeding (causes content to leak in during freeze), no DC blocker
(can build up offset on long holds), no crossfade on toggle
(clicks), no soft limiter (can drift to NaN/clip).

**Fix:**
- Input mute fade-in on freeze ON (10 ms ramp `inputGate` 1→0).
- Input fade-out on freeze OFF.
- Soft limiter in the feedback path: `fb = tanh(fb * 0.85f) / 0.85f`
  triggers above ±0.95 — prevents runaway.
- DC blocker (HP @ 5 Hz) before output to prevent DC drift over
  long holds.

**Files:** Crystal.java
**Estimated work:** 30 min
**Status:** [x] DONE — added input-gate ramp (10 ms 1→0 on freeze
ON, 0→1 on release), soft tanh limiter on both tank feedback taps
that triggers above ±0.95, and a 5 Hz DC blocker on the wet path.
Freeze can now be held indefinitely without runaway, content leak,
or DC drift.

---

### 4. GATE — user-controllable release

**Problem:** release coef is hardcoded `0.0008`. Crystalline has
threshold AND release as user controls.

**Fix:**
- Add `gateRelease` param (5..500 ms).
- Compute release coef per-block from sample rate.
- Add UI knob? Or keep as advanced param without UI for now (lives
  in `parameterNames()` but no on-canvas control).

**Files:** Crystal.java
**Estimated work:** 15 min
**Status:** [x] DONE — `gateRelease` parameter (5–500 ms) added.
Per-block coef computed from sample rate. Attack stays fast
(20 ms) for snappy opening — Crystalline behaviour matches.

---

### 5. SMOOTHING — multi-band notch bank

**Problem:** single peaking biquad @ 2.5 kHz doesn't match
"custom EQ curve addressing multiple resonant zones".

**Fix:**
- Replace single biquad with a 4-band bank at:
  - 900 Hz (gentle Q=0.7, light dip)
  - 1800 Hz (Q=1.0, moderate)
  - 2800 Hz (Q=1.4, strongest — primary resonance)
  - 4500 Hz (Q=1.0, harshness control)
- All 4 share the `smoothing` knob, with gain scaling
  proportional to the typical resonance strength at each band.

**Files:** Crystal.java
**Estimated work:** 25 min
**Status:** [x] DONE — replaced single peaking biquad with a 4-band
bank at 900 Hz (Q 0.7), 1800 Hz (Q 1.0), 2800 Hz (Q 1.4, strongest),
4500 Hz (Q 1.0). Per-band weights bias the gain so the 2.8 kHz
band gets the deepest cut for the same `smoothing` knob value —
matches Crystalline's "custom EQ curve" framing.

---

### 6. A/B impulse-response capture utility

**Problem:** no objective way to measure if my reverb sounds
closer to Crystalline after each tweak. Need a tool that captures
the impulse response so we can compare against reference plots.

**Fix:**
- New file `tools/test-app/MeasureIR.java`.
- Loads any native plugin via the existing TestApp classloader
  scaffolding.
- Feeds a Dirac impulse (1.0 in the first sample, 0 elsewhere).
- Captures 4 seconds of output.
- Writes:
  - `tools/test-app/ir.wav` — the impulse response as audio
  - `tools/test-app/ir-spectrogram.png` — STFT spectrogram of the IR
  - `tools/test-app/ir-decay.png` — RT60 decay curve per octave band
  - `tools/test-app/ir-stats.json` — peak, RMS, RT60, spectral
    centroid over time
- Designed to be runnable against any reverb plugin, not just
  Crystal — so we can compare against other reverbs we add too.

**Files:** new `tools/test-app/MeasureIR.java`
**Estimated work:** 45 min
**Status:** [x] DONE — standalone CLI harness with the same
classloader scaffolding as RunTest.java. Fires a Dirac impulse,
captures N seconds (default 4), writes `ir.wav`, STFT
`ir-spectrogram.png`, per-octave RT60 decay curve `ir-decay.png`,
and Schroeder RT60 stats JSON. Works against any reverb plugin
(`--plugin <id> --params "k=v,..." --seconds N`).

---

### 7. BPM sync — optional manual tempo

**Problem:** Crystalline's START/END can sync to host tempo. We
have no host tempo from the Android app yet, so true sync isn't
possible.

**Fix:** introduce an optional `tempoBpm` parameter (default 0 =
"no sync"). When > 0 AND `syncMode` is on, predelay/decay snap to
musical divisions (1/16, 1/8, 1/4, 1/2, 1 bar) of the configured
tempo. Gives users a workable interim solution until the host
delivers real tempo.

**Files:** Crystal.java
**Estimated work:** 30 min
**Status:** [x] DONE — added `tempoBpm` (30–300 BPM) and reworked
the per-block START/END resolution so that when `syncMode` is on
and tempoBpm > 1, the START knob snaps to 1/64–1/4-of-a-beat
divisions and the END knob snaps to 1/4-, 1/2-, 1-, 2-, 4-beat
divisions of the manual tempo.  Falls back to raw seconds when
syncMode is off — host-supplied tempo can be wired later by
having the host write to `tempoBpm` per block.

---

## Summary state

- [x] 1. SHIMMER dual mode (HF Decay + Octave)
- [x] 2. REVERSE segmented + crossfaded
- [x] 3. FREEZE input-disconnect + safety
- [x] 4. GATE user-release
- [x] 5. SMOOTHING multi-band bank
- [x] 6. A/B impulse-response capture utility
- [x] 7. BPM sync via manual tempo

**7 / 7 items completed.**

After each item is committed, this file gets the box checked AND
a brief note of what changed (so the file doubles as the change
log for the rework).

---

## Verification pass (against official Crystalline docs)

Done after all 7 items were implemented. Each row pairs the
manufacturer's exact wording with what the code now actually does.

| # | Crystalline (official quote) | My implementation | Verdict |
|---|---|---|---|
| 1 | SHIMMER: "makes the high frequencies of the reverb tail decay slower than the rest of the spectrum… set the frequency cutoff point as well as the multiplier (2×, 4× or 6×)" | HF Decay mode (default): 1-pole HP at `shimmerCut` extracts HF, fed back into each tank with coef `feedbackG ^ (1/mul)` → HF decays mul× slower. `shimmerOct` selects 2/4/6. Octave mode kept as second option. | ✅ matches |
| 2 | REVERSE: "reverses the reverb playback" | 8 × 50 ms segments. Read head plays the most-recent finished segment backwards (segLen-1 → 0), then steps to the previous segment. Hann crossfade between adjacent reversed segments. | ✅ matches |
| 3 | FREEZE: "takes a granular snapshot of the reverb when clicked and holds this snapshot continuously" | `feedbackG = 1.0` holds the tank state. Input gate ramps 1→0 in 10 ms to prevent contamination, soft tanh limiter on both feedback taps, 5 Hz DC blocker on wet. | ⚠️ functionally equivalent — produces an infinite hold. Not literally a "snapshot to separate buffer" + loop. Audible result is the same drone-hold but the mechanism differs. Marked here so we don't pretend it's identical. |
| 4 | GATE: "threshold and release controls" | `gate` (dB threshold) + `gateRelease` (5–500 ms) params. Attack stays fast 20 ms; release coef computed per-block from user value. | ✅ matches |
| 5 | SMOOTHING: "EQ-curve custom-designed to address the frequency areas that tend to get resonant and sharp" | 4-band peaking biquad bank at 900 / 1800 / 2800 / 4500 Hz with weighted negative gains, all driven by the single `smoothing` knob. 2.8 kHz gets the heaviest cut (typical primary tank resonance). | ✅ matches |
| 6 | DAMPING: "dual filter control that lets you remove high and low frequency content from the reverb reflections" | In-loop 1-pole LP (kills highs over time) + in-loop 1-pole HP (kills sub-bass build-up). Same `damping` knob drives both. | ✅ matches |
| 7 | DUCKER: "Gentle = slow and natural… standard/pumpy = more pumpy feel" | `duckMode` toggle: Gentle = 25 ms attack / 300 ms release IIR (sub-audible). Pumpy = 3 ms / 60 ms (audible classic pump). | ✅ matches |
| 8 | START/END SYNC: "sync the reverb pre-delay and decay time to your DAW's tempo, or switch to millisecond-based settings" | `syncMode` toggle + manual `tempoBpm` (30–300 BPM). When sync is on, START snaps to 1/64–1/4-beat divisions and END snaps to 1/4–4-beat divisions. | ⚠️ uses a manual tempo param instead of host-supplied DAW tempo. The Android host can wire to `tempoBpm` per block later — algorithm side is identical. |

### Honest scorecard

- 6 / 8 fully matches official spec
- 2 / 8 functionally equivalent with caveats explicitly noted
  (FREEZE mechanism, BPM source)
- 0 / 8 missing or wrong

### What still cannot be claimed

Even with all 8 items matching the official descriptions:

- Sonic 1:1 with Crystalline is **still not proven** — Crystalline's
  delay times, AP coefficients, modulation curves, gain staging and
  parameter response curves are not published. Two reverbs sharing
  every named parameter can sound noticeably different.
- The MeasureIR tool (item 6) is the path forward: render IRs with
  the same parameter setup against a Crystalline reference and
  iterate on coefficients until the per-octave RT60 curves and
  spectrograms converge.

This rework gets us from "function names match" → "official
behavioural descriptions match". Closing the remaining sonic gap is
a separate, measurement-driven workflow.


kad pabeidz izej cauri visiem punktiem un pārbaudi vai ir viss izdarīts korekti. kā to dara crystaline pārliecinies vairrakkart lai ir 100%
