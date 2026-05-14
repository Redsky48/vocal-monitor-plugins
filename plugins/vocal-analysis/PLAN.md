# Vocal Analysis — rework towards A+ pro-tool grade

Tracking document for the per-plugin upgrades raised in the
2026-05-14 honest quality audit. Each item is a real DSP /
correctness gap, not a UI tweak. Updated as work progresses.

## Honest baseline (before this rework)

After auditing all 12 plugins against pro-tool references (Praat,
pYIN/CREPE, iZotope Insight, Waves WLM, FabFilter Pro-DS, Soothe2,
Voxengo SPAN, Sundberg's singer's-formant work):

| Plugin               | Grade | Why not A+ yet                                          |
|----------------------|-------|---------------------------------------------------------|
| vocal-spectrum       | B+    | Linear STFT only, no Constant-Q, no peak hold           |
| pitch-accuracy       | B     | Raw YIN, no Viterbi smoothing, no octave-error guard    |
| vibrato-analyzer     | C+    | Zero-crossing rate — noisy. No per-cycle depth.         |
| formant-tracker      | C     | Peak-pick on LP spectrum. No roots, no bandwidths.      |
| sibilance-detector   | B-    | 4 bandpasses, no lookahead, no GR recommendation.       |
| resonance-monitor    | B     | 2-band ratio. No proper LTAS, no calibrated thresholds. |
| breathiness-meter    | B-    | HNR from YIN CMND. No CPP (the gold standard).          |
| vocal-dynamics       | A-    | BS.1770 K-weighting ✅. Missing gating, LRA, true peak. |
| articulation         | C+    | Spectral flux only. No complex-domain onset, no class.  |
| vocal-stability      | B-    | **BUG**: "centroid" computed from time-domain samples.  |
| register-detector    | C     | Heuristic only. No H1-H2, no proper feature set.        |
| vocal-fatigue        | C     | 4 features only. No jitter / shimmer / CPP.             |

**Distribution:** 1 plugin A-, 6 plugins B-/B+, 5 plugins C/C+,
**1 known correctness bug**.

## Goal of this rework

Take every plugin to **A+** within the constraints of the
plain-Java/DEX runtime (no native libs, no ML frameworks, no
training data). Where a true A+ technique is unreachable in our
environment (e.g. CREPE neural pitch), document the gap honestly
and ship the best heuristic that is reachable.

A+ here means: the algorithm choice and parameters match what a
peer-reviewed reference (Praat / pYIN / BS.1770 / Sundberg) would
use, the numbers it shows are calibrated, and the display tells
the vocalist something they can act on.

---

## Items

### 1. vocal-spectrum — Constant-Q overlay + spectral smoothing + peak hold

**Problem:** linear-frequency STFT is fine for engineers, but
vocalists want to see notes — and a linear bin is far too coarse
in the low octave and far too fine in the high octave. No peak
hold means transients flash and vanish.

**Fix:**
- Add a "log-Q" rendering mode that re-samples the STFT
  magnitudes onto a per-semitone grid (E1..C8). Real Constant-Q
  is expensive; per-semitone log re-bin is the affordable proxy
  with the same on-screen feel.
- Spectral smoothing knob (1, 1/3, 1/6 oct) — moving average over
  log-bin neighbours.
- Peak hold ring: per-bin max with 1.5-second decay.

**Files:** vocal-spectrum/VocalSpectrum.java
**Status:** [x] DONE — split the canvas: scrolling linear-bin
spectrogram on the left (existing) + new **log-Q live spectrum**
panel on the right.  Log-Q is per-semitone triangular re-binning
of the 1024-pt FFT magnitudes across **MIDI 28–107 (E1–B7)** — the
affordable proxy for a true Constant-Q transform with the same on-
screen feel.  **Spectral smoothing** parameter (0 = 1/6 oct, 1 =
1/3 oct, 2 = 1 oct) applies a moving average across adjacent
semitones.  **Peak hold** with 1.5 s decay paints a yellow tick
per semitone showing the max-magnitude-since-recent.  Vocal-band
tints (MUD / BODY / HARSH / SIB / AIR) are overlaid on the log-Q
panel so the singer can read the zones.

---

### 2. pitch-accuracy — pYIN-style smoothing + octave-error guard

**Problem:** raw YIN per-frame jumps occasionally octave-down on
breathy vowels and octave-up on hard consonants. Reads as
±1200 cent spikes in "accuracy".

**Fix:**
- Multi-candidate YIN (keep best 3 minima, not just the first
  below threshold).
- Viterbi-style smoothing across N=8 frames: transition cost =
  |Δsemitones| × λ, observation cost = CMND value. Picks the
  cheapest path → kills isolated octave jumps.
- Voicing probability from CMND (not a hard threshold) — feeds
  the score weight so unsure frames don't tank the average.

**Files:** pitch-accuracy/PitchAccuracy.java
**Status:** [x] DONE — collects **3 candidate YIN minima** per
frame (sorted by CMND) using a relaxed 0.45 threshold instead of
the strict 0.15.  Runs an **8-frame forward Viterbi** over the
candidate trellis with transition cost λ·|Δsemitones| (λ = 0.10):
isolated octave jumps now pay 1.2 cost vs ≈0.1 for staying on the
nearest semitone, so the cheap-path stays on the true melody.
Falls back to a global-min single-candidate frame when no local
minima exist; resets the Viterbi history at unvoiced gaps.
**Voicing probability** (1 − CMND of the chosen candidate) feeds
the phrase-average weighting — uncertain frames contribute less
to the score.

---

### 3. vibrato-analyzer — autocorrelation rate + per-cycle depth + onset detection

**Problem:** rate from zero-crossing count is noisy and breaks
when the contour has small jitter. Depth is a single global
peak-to-peak, hiding swell/decay. No "vibrato vs straight tone"
classifier.

**Fix:**
- Autocorrelation of the pitch deviation buffer to find vibrato
  period directly (peak between 100 ms and 200 ms lag = 5–10 Hz).
- Cycle-detect via sign-change of smoothed deviation, record
  per-cycle depth and rate → time-domain plot of both.
- Vibrato onset: contiguous run of ≥3 cycles with rate stdev <
  0.5 Hz AND depth ≥ 15 cents — flag as "vibrato active".

**Files:** vibrato-analyzer/VibratoAnalyzer.java
**Status:** [x] DONE — replaced zero-crossing rate detection with
**autocorrelation** of the 1 s deviation buffer.  Lag is searched
across the 4–10 Hz window with parabolic peak interpolation; rate
is only emitted when the normalised autocorr peak is > 0.25 (i.e.
the signal is genuinely periodic, not jitter).  **Per-cycle
measurements** via positive-zero-crossing detection on a 3-tap-
smoothed deviation: each cycle records its own period (→ rate) and
peak-to-peak depth.  Depth readout is now the **most recent cycle**
not the buffer RMS.  Regularity = 1 − stdev(cycleRates)/mean.
**Vibrato active** indicator asserts when last ≥3 cycles have rate
stdev < 0.5 Hz AND mean depth ≥ 15 cents.  Header shows ● VIBRATO
ACTIVE in green when locked, ○ no vibrato otherwise.

---

### 4. formant-tracker — root finding + bandwidths + continuity

**Problem:** picking peaks off the LP magnitude spectrum is the
schoolbook method but loses accuracy for narrow formants and
gives no bandwidth (which is required to tell "ah" from a nasal
"ah"). No tracking across frames means F1/F2/F3 swap labels.

**Fix:**
- Solve roots of the LPC polynomial via Durand-Kerner (12 complex
  roots, 25 iter, deflate as we go). Bairstow is more textbook
  but Durand-Kerner is shorter and converges fine for order 12.
- Per-root frequency = arg(root)·sr/(2π); bandwidth =
  -ln|root|·sr/π. Keep roots with 90 ≤ f ≤ 5500 Hz and bw ≤ 600.
- Continuity tracker: nearest-frequency assignment to last
  frame's F1/F2/F3 instead of strict ascending order. Hungarian
  is overkill for 3 slots; greedy + minimum-distance is fine.

**Files:** formant-tracker/FormantTracker.java
**Status:** [x] DONE — replaced LPC-spectrum peak picking with
**Durand-Kerner complex root finding** on the 12th-order LPC
polynomial.  25 iterations of the parallel-root update with a
0.6-radius spiral initial guess; converges typically in 8–12
iter.  For each root z = r·e^(jω): **frequency = ω·sr/(2π)**,
**bandwidth = −ln(r)·sr/π**.  Roots filtered to 90 ≤ f ≤ 5500 Hz
and bw ≤ 600 Hz (drops nuisance non-formant poles).  **Greedy
continuity tracker** reassigns this frame's candidates to the
previous F1/F2/F3 by minimum |Δf| within a 350 Hz window, so
labels don't swap during glides.  UI now shows F1/F2/F3 with
their bandwidths: `F1 720 Hz (BW 80)`.

---

### 5. sibilance-detector — lookahead + finer bands + GR recommendation

**Problem:** zero-lookahead detector flags sibilance AFTER the
peak. Coarse 4 bands. No suggestion of "this much cut at this
band" — which is what users actually want.

**Fix:**
- 5 ms lookahead via a delay line on the analysis side
  (audio stays pass-through, no delay introduced).
- 6 bands at 3 / 4.5 / 6 / 7.5 / 9 / 11 kHz (Q=4 bandpass).
- For each event: track which band peaked and by how much,
  output "−4 dB @ 7 kHz" style recommendation in the readout.
- Threshold becomes a per-band-adaptive moving average (10 s
  median + 6 dB offset) instead of a global dB number.

**Files:** sibilance-detector/SibilanceDetector.java
**Status:** [x] DONE — split the 4 coarse bands into **6 finer
bands** at 3 / 4.5 / 6 / 7.5 / 9 / 11 kHz (Q=4 RBJ bandpass).  Per-
band **adaptive threshold** = median of last 10 s of envelope × 2
(≈ +6 dB), median re-estimated once per second.  Event detector
now records the **dominant band** + **excess in dB** → displays
`rec −X.X dB @ Y kHz` recommendation in the header.  Visual **5 ms
lookahead** — event tick is drawn one history-bin earlier than the
fire moment so the marker lines up with the *start* of the
sibilant, not its peak.  Audio still pass-through (no introduced
latency).

---

### 6. resonance-monitor — LTAS + Sundberg's SPR formula + calibrated thresholds

**Problem:** simple 2-band ratio gives a number but not the right
one. Sundberg (1974) defines the Singer's Power Ratio (SPR) as
peak energy in 2–4 kHz minus peak energy in 0–2 kHz, measured
from the Long-Term Average Spectrum (LTAS), not instantaneous
bins. Threshold of "good" needs to be calibrated.

**Fix:**
- LTAS: per-FFT-bin running average over 5 seconds, log-domain.
- Find peak dB in the 2–4 kHz band (singer's formant zone) and
  peak dB in the 0–2 kHz band (fundamental + body), compute SPR
  in dB.
- Calibration: SPR > -10 dB ≈ "operatic ring", -10..-20 ≈ "trained
  modern", -20..-30 ≈ "untrained", < -30 ≈ "no ring". Display the
  band along with the dB number.

**Files:** resonance-monitor/ResonanceMonitor.java
**Status:** [x] DONE — replaced 2-band envelope ratio with **proper
LTAS**: 1024-pt Hann-windowed STFT at 50 % overlap (86 fps), per-
bin running magnitude-dB average with 5 s time constant.  **SPR =
peak_dB(2–4 kHz) − peak_dB(0–2 kHz)** computed from the LTAS,
exactly Sundberg's definition.  Calibrated four-level verdict
(RING / TRAINED / UNTRAINED / NO RING) at −10 / −20 / −30 dB
boundaries.  UI: big SPR readout card, log-Hz LTAS plot with the
two band peaks marked, scrolling SPR history.

---

### 7. breathiness-meter — Cepstral Peak Prominence (CPP)

**Problem:** breathiness ≠ low HNR strictly. The clinical
gold-standard is CPP (Hillenbrand 1994): take the cepstrum of a
voiced frame, find the peak in the pitch-period quefrency region,
measure how far that peak rises above the regression line of the
surrounding cepstrum.

**Fix:**
- Real cepstrum: log |FFT(x)| → IFFT → take real part.
- In the quefrency range corresponding to pitch period
  (1/500 Hz ≤ q ≤ 1/60 Hz), find peak value pk.
- Linear regression across the same quefrency range → predicted
  value at the peak's quefrency.
- CPP_dB = pk - regression_at_pk. Higher = less breathy.
- Keep HNR as a secondary readout; CPP becomes the primary
  number. Calibrate: CPP > 15 dB = clear, 10–15 = normal, 5–10
  = breathy, < 5 = severely breathy / pressed-noise.

**Files:** breathiness-meter/BreathinessMeter.java
**Status:** [x] DONE — added the **Cepstral Peak Prominence**
(Hillenbrand 1994) primary path: 1024-pt Hann FFT → dB-magnitude
→ IFFT (symmetric-real trick) → real cepstrum in dB units.
Quefrency window mapped from 60–500 Hz pitch range, peak found in
that window, linear regression across the same window via least
squares.  **CPP_dB = peak − regression-at-peak**.  Calibrated
verdict (CLEAR ≥ 15, NORMAL ≥ 10, BREATHY ≥ 5, SEVERE) per
Heman-Ackah et al. 2014.  HNR kept as secondary readout.  UI:
big CPP card, cepstrum plot with regression line + peak dot +
detected f0, CPP history with zone shading.

---

### 8. vocal-dynamics — full BS.1770 (gating + LRA + true peak)

**Problem:** has K-weighting and 3-second window already (A-).
Missing: −70 LUFS absolute gate and −10 LU relative gate (which
the standard requires for integrated LUFS), Loudness Range (LRA,
EBU R128), and true-peak via 4× oversampling.

**Fix:**
- Integrated-LUFS path: keep K-weighted mean-square blocks,
  drop blocks below −70 LUFS absolute, then drop blocks below
  (mean − 10 LU). Recompute mean → integrated value.
- LRA: 3-s blocks, gate at −70 LUFS abs and −20 LU relative,
  LRA = LUFS_p95 − LUFS_p10.
- True peak: 4× FIR-interpolated peak per block. The classic
  half-band FIR (Lagrange / 47-tap windowed-sinc) is cheap.
- Display: M / S / I / LRA / TP in five labelled cells.

**Files:** vocal-dynamics/VocalDynamics.java
**Status:** [x] DONE — added 400 ms (M) and 3 s (S) sliding K-
weighted MS rings, 100 ms-stride M-block ring (30 min capacity)
with `−70 LU abs + −10 LU rel` gating → **Integrated LUFS**.
1 s-stride S-block ring (30 min capacity) with `−70 LU abs +
−20 LU rel` gating → **LRA = LUFS_p95 − LUFS_p10**.  4× FIR-
oversampled **true peak** via a 12-tap Hann-windowed-sinc
polyphase kernel (4 phases × 12 taps, unity-DC-gain normalised),
with peak hold + 1.5 s decay.  UI: 5 numeric cards `M / S / I /
LRA / TP` plus the existing peak history + DR readout.

---

### 9. articulation — complex-domain onset + adaptive threshold

**Problem:** spectral flux is the right family, but the
production version of the Bello/Dixon onset detector also uses
phase deviation (complex-domain) and the threshold is adaptive
(local median × scale), not fixed.

**Fix:**
- Per-bin: predict next phase = 2·prevPhase − prevPrevPhase,
  predict next mag = prevMag. Complex distance between predicted
  and observed = the per-bin onset contribution. Sum across HF
  bins → onset detection function.
- Adaptive threshold: 100 ms moving median × 1.7, plus a small
  floor so silence doesn't fire.
- Onset → simple consonant classifier from HF/LF ratio at peak:
  HF-heavy ≈ /s/ /t/ /k/, LF-heavy ≈ /b/ /d/ /g/, mid ≈ /m/ /n/.
  Display the most-recent classification.

**Files:** articulation/Articulation.java
**Status:** [x] DONE — replaced spectral-flux + HF-envelope with
the **complex-domain Bello/Dixon 2005 ODF**: per-bin predicted X̂
= |X[n−1]| · exp(j·(2·φ[n−1] − φ[n−2])); ODF = Σ |X − X̂|.
Captures phase deviations as well as magnitude changes — steady-
state harmonics keep the ODF low even at loud sustained notes,
which the old flux detector got wrong.  **Adaptive threshold** =
100 ms moving median × 1.7 + small floor.  Onsets fire only at
local ODF maxima above the threshold.  **Consonant classifier**:
HF/LF energy ratio at the onset frame → /s/ /t/ /k/ (HF) vs
/m/ /n/ /v/ (mid) vs /b/ /d/ /g/ (LF).  Most-recent class is
shown next to the title in the class colour.

---

### 10. vocal-stability — fix spectral-centroid bug + add jitter/shimmer

**Problem (correctness):** the existing "spectral centroid" is
computed from `yinBuf` time-domain samples being treated as if
they were FFT bin magnitudes (lines 146-155). This is wrong and
makes the TONE sub-score essentially meaningless.

**Additional gap:** classical voice stability is measured with
jitter (period-to-period pitch perturbation) and shimmer
(amplitude perturbation), not just stdev of the cents track.

**Fix:**
- Replace the fake centroid with a real FFT: 512-point Hann →
  magnitude → centroid = Σ(k·|X[k]|) / Σ|X[k]|, converted to Hz.
- Add JITTER (local, in %): mean of |T_i − T_{i-1}| / T_i across
  consecutive YIN pitch periods over 1 s. Normal < 1%.
- Add SHIMMER (local, in dB): mean of |20·log10(A_i / A_{i-1})|
  across consecutive period peak amplitudes over 1 s. Normal < 1 dB.
- Re-weight composite: 0.30 pitch + 0.15 jitter + 0.15 shimmer
  + 0.15 tone + 0.15 volume + 0.10 breaks.

**Files:** vocal-stability/VocalStability.java
**Status:** [x] DONE — replaced the time-domain "centroid" with a
real 1024-pt Hann-windowed radix-2 FFT centroid (Hz units, used by
the TONE stdev calc).  Added per-frame period (YIN lag with
parabolic interpolation) and per-period peak amplitude tracking →
**Praat-style local jitter (%)** and **local shimmer (dB)** over
the 2 s ring.  Composite re-weighted to 30/15/15/15/15/10 across
PITCH / JITTER / SHIMMER / TONE / VOLUME / BREAKS.  UI now shows
6 sub-bars + raw `J %  S dB` readout in the history strip.

---

### 11. register-detector — H1-H2 measurement + better feature set

**Problem:** pure heuristic with three features (pitch, spectral
slope, SF ratio) and gaussian/sigmoid scoring. The textbook
register cue (Sundberg, Titze) is H1-H2: dB difference between
the first two harmonics — large positive in breathy/falsetto,
small or negative in chest/modal.

**Fix:**
- Track f0 via YIN, then sample the FFT magnitude (in dB) at f0
  and 2·f0 with parabolic interpolation around each.
- H1-H2 = |X(f0)|_dB − |X(2·f0)|_dB.
- Combine: H1-H2 + spectral tilt + SF-ratio + pitch zone → four
  evidence scores per register, pick max. Honest framing:
  this is still a heuristic, but the features are now the ones
  the literature actually uses.

**Honest caveat:** true register classification needs labelled
training data and a small classifier. We have neither — so this
upgrade gets us to "best heuristic possible with the right
features", not to ML-grade.

**Files:** register-detector/RegisterDetector.java
**Status:** [x] DONE — added the **H1-H2** measurement (the
textbook register cue per Sundberg & Titze): 1024-pt Hann FFT,
dB-magnitude at f0 and 2·f0 sampled with parabolic interpolation
around the nearest bin, **H1-H2 = X_dB(f0) − X_dB(2·f0)**.
Reworked scoring to a clean **4-feature gaussian product** (pitch
zone × spectral tilt × H1-H2 × SF ratio), with per-register
expected (mean, sigma) tables for CHEST / MIX / HEAD / FALSETTO /
BELT.  Header tags the readout as `heuristic - 4-feature
evidence` so the user knows this is not ML-grade.  Live stats
strip now shows Hz / tilt / **H1-H2** / ring.

---

### 12. vocal-fatigue — jitter + shimmer + CPP added to trend

**Problem:** 4-feature trend (HF energy, pitch stability, RMS,
HNR) is a reasonable surface but misses jitter / shimmer / CPP,
which are the clinical fatigue indicators (vocal fold dysfunction
shows up as elevated jitter/shimmer and dropping CPP before
audible roughness appears).

**Fix:**
- Pull jitter from the new VocalStability code path (same
  computation, local impl).
- Pull shimmer from the same.
- Pull CPP from the new BreathinessMeter code path.
- 7-feature trend instead of 4: HF tilt, pitch stdev, RMS,
  HNR, jitter, shimmer, CPP — each normalised vs the first
  60 s baseline.
- Composite "fatigue index" = average of normalised deltas
  where degradation is upward.

**Files:** vocal-fatigue/VocalFatigue.java
**Status:** [x] DONE — extended the 4-feature trend to **7
features**: pitch stability + brightness + HNR + DR (kept) plus
**jitter (%)**, **shimmer (dB)** and **CPP (dB)** (added).  Per
30-second window: YIN-period perturbation gives jitter (Praat
local), per-period peak-amplitude gives shimmer (Praat local),
real-cepstrum CPP gives the clinical breathiness measure.
Composite degradation is averaged across all seven features (each
clamped to 3× baseline so a single outlier can't dominate).  UI:
the existing big LOAD number + trend graph **plus a per-feature
delta-bar row** showing which features are degrading and by how
much.

---

## Summary state

- [x] 1. vocal-spectrum — log-Q + smoothing + peak hold
- [x] 2. pitch-accuracy — pYIN-style smoothing + octave guard
- [x] 3. vibrato-analyzer — autocorrelation rate + per-cycle depth
- [x] 4. formant-tracker — root finding + bandwidths + continuity
- [x] 5. sibilance-detector — lookahead + finer bands + GR recommendation
- [x] 6. resonance-monitor — LTAS + Sundberg SPR + calibrated thresholds
- [x] 7. breathiness-meter — Cepstral Peak Prominence (CPP)
- [x] 8. vocal-dynamics — gating + LRA + true peak
- [x] 9. articulation — complex-domain onset + adaptive threshold
- [x] 10. vocal-stability — fix centroid bug + jitter + shimmer
- [x] 11. register-detector — H1-H2 + better features
- [x] 12. vocal-fatigue — jitter + shimmer + CPP added

**12 / 12 items completed.**

After each item is committed, this file gets the box checked AND a
brief note of what changed (so the file doubles as the change log
for the rework).

## What will remain honestly unclaimable

Even with every item above completed:

- **Pitch tracker is not CREPE-grade.** A small CNN trained on
  16 kHz audio with the f0 ground truth is the current
  state-of-the-art; we are not shipping a CNN inside a DEX. pYIN-
  level Viterbi smoothing is the best we can do, and is the best
  available CPU-only algorithm for unrestricted audio.
- **Register-detector is heuristic.** Even with H1-H2 and four
  features, without training data on labelled chest/mixed/head/
  falsetto recordings we cannot ship an ML classifier.
- **Vocal-fatigue is relative, not absolute.** A clinical fatigue
  measure requires laryngoscopy or EGG; an audio-only fatigue
  trend can only flag intra-session drift, not absolute risk.

These caveats will be visible in each plugin's description so the
vocalist knows what they are reading.

---

## Execution order

Roughly easiest-correctness-first → biggest-DSP-last so each
commit is reviewable:

1. **vocal-stability** (item 10) — fixes the known centroid bug
2. **vocal-dynamics** (item 8) — adds standard BS.1770 features
3. **sibilance-detector** (item 5) — adds bands + recommendation
4. **resonance-monitor** (item 6) — adds LTAS + SPR
5. **breathiness-meter** (item 7) — adds CPP cepstrum path
6. **vocal-fatigue** (item 12) — depends on jitter/shimmer/CPP
7. **vibrato-analyzer** (item 3) — autocorrelation rate
8. **pitch-accuracy** (item 2) — Viterbi smoothing
9. **register-detector** (item 11) — H1-H2 measurement
10. **vocal-spectrum** (item 1) — log-Q overlay
11. **articulation** (item 9) — complex-domain onset
12. **formant-tracker** (item 4) — Durand-Kerner roots
