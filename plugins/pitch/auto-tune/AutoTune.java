package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Auto-Tune — production-grade vocal pitch correction. Six engines wired
// in series, all built on published industry-standard DSP:
//
//   1. YIN pitch detector (de Cheveigné & Kawahara 2002). 1024-sample
//      sliding window, cumulative-mean-normalised-difference function,
//      parabolic interpolation, ~170 Hz update rate, cents-accurate.
//
//   2. Octave-error correction. YIN occasionally locks an octave off on
//      voices with strong harmonics; we reject any new pitch estimate
//      that's a near-clean octave jump from a recent confident reading.
//
//   3. Scale quantiser. Detected freq → MIDI cents → snap to nearest
//      pitch in the chosen scale (chromatic / major / minor / harmonic
//      minor / pentatonic), with ±3-semi search so diatonic semi-gaps
//      (B → C in major, etc.) are handled correctly.
//
//   4. LPC source-filter formant preservation (12th-order autocorrelation
//      method, Levinson-Durbin recursion). The classic vocoder /
//      speech-synthesis trick: estimate the vocal-tract resonances every
//      ~6 ms, inverse-filter them out of the input to get a whitened
//      residual ("excitation"), run the pitch shift on the residual,
//      then re-apply the LPC synthesis filter to restore the formants.
//      Without this, large pitch shifts produce chipmunk / Darth Vader
//      artefacts because formant frequencies scale along with pitch.
//      With it, shifts stay natural across the full ±octave range.
//
//   5. TD-PSOLA pitch shift. Time-Domain Pitch-Synchronous Overlap-Add
//      operating on the LPC residual: grains are one source pitch period
//      long, output periods are T_in/ratio long, wraps land at pitch-
//      period boundaries where the signal is nearly periodic so they're
//      inaudible. Two staggered Hann-windowed voices crossfade. Cubic
//      Hermite interpolation on the residual reads for clean harmonics
//      at non-integer source positions.
//
//   6. Transient bypass. Fast/slow envelope ratio detects consonant
//      attacks and plosives, smoothly crossfading the correction off
//      so transients pass through dry. Without this, fast melodic
//      passages with crisp consonants get muddied by the grain machinery.
//
// Voicing gate (YIN-confidence-driven) and humanize (slow random walk
// on the ratio) round out the chain.

public final class AutoTune implements VocalMonitorNativePlugin {

    // ============================================================
    //  YIN pitch detector
    // ============================================================
    private static final int ANALYSIS_SIZE = 1024;
    private static final int LAG_MIN = 32;        // 1378 Hz @ 44.1k
    private static final int LAG_MAX = 512;       // 86 Hz @ 44.1k
    private static final int ANALYSIS_INTERVAL = 256;
    private static final float YIN_THRESHOLD = 0.15f;

    // YIN sees the LATEST input (not the delayed one) so its detections
    // are "ahead" of the audio currently being filtered through the
    // processing chain. That's where the lookahead benefit comes from.
    private final float[] analysisBuf = new float[ANALYSIS_SIZE];
    private final float[] yinBuf = new float[ANALYSIS_SIZE];
    private final float[] yinD = new float[LAG_MAX + 2];
    private final float[] yinCMND = new float[LAG_MAX + 2];
    private int analysisWrite = 0;
    private int samplesSinceAnalysis = 0;
    private float voicingConfidence = 0f;

    // ============================================================
    //  Input lookahead
    // ============================================================
    //
    // The processing chain (pre-emphasis → LPC inverse → PSOLA →
    // LPC synthesis → de-emphasis) operates on a DELAYED copy of the
    // input that lags the latest arrival by LOOKAHEAD_SAMPLES. YIN runs
    // on the latest input, so by the time the audio reaches the output
    // YIN has already had ~LOOKAHEAD samples to detect the upcoming
    // pitch, update the target ratio, and let the smoothing IIR start
    // moving toward the new target. Transitions arrive at the output
    // with the correction already engaged instead of lagging it.
    //
    // 50 ms = 2205 samples at 44.1 kHz. Inaudible for non-realtime
    // ("processing") use, and a meaningful quality gain on note edges.
    private float[] inputDelayRing;
    private int inputDelayRingLen;
    private int inputDelayWrite = 0;
    private int lookaheadSamples;

    // ============================================================
    //  Pitch state (with octave-error correction + vibrato preservation)
    // ============================================================
    private float detectedFreq = 220f;
    private float detectedPeriod = 200f;
    private float stableFreq = 220f;       // long-smoothed reference
    private float stableConfidence = 0f;
    private float targetRatio = 1f;
    private float currentRatio = 1f;

    // Vibrato preservation: track a slow-IIR note centre so we can snap
    // it (not the raw f0) to the scale grid, then ADD BACK the natural
    // deviation (f0 - centre). This preserves 3-7 Hz vibrato through the
    // correction instead of flattening it.
    private float noteCenter = 220f;
    private static final int PITCH_HISTORY = 32;
    private final float[] pitchHistory = new float[PITCH_HISTORY];
    private int pitchHistoryIdx = 0;
    private float pitchVariance = 0f;
    private float lastTargetCenter = 220f;

    // Confidence hold: when YIN drops to unvoiced briefly (mid-phrase
    // consonants), keep the last good targetRatio for a hold window
    // before easing back to passthrough.
    private int unvoicedSamples = 0;

    // ============================================================
    //  LPC formant preservation
    // ============================================================
    // Order 16 captures the first ~4 formants accurately at 44.1 kHz
    // (more poles than 12 lets the predictor separate F3 and F4 in
    // soprano voices where they sit close together).
    private static final int LPC_ORDER = 16;
    private static final int LPC_FRAME_SIZE = 512;
    private static final int LPC_UPDATE_INTERVAL = 256;
    private static final float PRE_EMPHASIS = 0.97f;
    // Bandwidth expansion gamma: after Levinson-Durbin we multiply each
    // a[k] by γ^k for γ slightly less than 1. This pulls every pole
    // radially toward the origin in the z-plane, broadening formant
    // resonances. The synthesis filter rings less and sounds more
    // natural — a standard speech-synthesis post-processing step.
    private static final float LPC_GAMMA = 0.99f;

    // LPC analyzes the SAME (delayed) signal that the inverse filter
    // operates on, so the modelled formants are consistent with the
    // input being whitened. Separate from YIN's buffer (which is on
    // latest input).
    private float[] lpcAnalysisBuf;
    private int lpcAnalysisWrite = 0;

    private final float[] lpcFrame = new float[LPC_FRAME_SIZE];
    private final float[] lpcAuto = new float[LPC_ORDER + 1];
    private final float[] lpcA = new float[LPC_ORDER + 1];        // current
    private final float[] lpcAtarget = new float[LPC_ORDER + 1];  // newly-computed (we ramp toward this)
    private final float[] lpcAprev = new float[LPC_ORDER + 1];    // start of ramp
    private final float[] lpcAtmp = new float[LPC_ORDER + 2];     // Levinson-Durbin scratch
    // Per-sample state for inverse (whitening) and synthesis (re-formant)
    // filters — direct-form-I IIR state for the LPC predictor.
    private final float[] invInputDelay = new float[LPC_ORDER];
    private final float[] synthOutputDelay = new float[LPC_ORDER];
    private float preEmphasisPrev = 0f;
    private float deEmphasisPrev = 0f;
    private int samplesSinceLPC = 0;
    private float lpcRampPos = 1f;   // 0..1 ramp progress between coef updates
    private int totalSamplesProcessed = 0; // hold off LPC until buffer filled

    // ============================================================
    //  Lanczos-3 windowed-sinc interpolation table for residual reads
    // ============================================================
    //
    // For each fractional bin in [0, 1) we pre-compute six tap weights
    //   w[k] = sinc(k - f) · sinc((k - f) / 3)     for k ∈ {-2..3}
    // normalised so that they sum to 1 (preserves DC). Reading a
    // fractional buffer position then costs 6 multiplies + 5 adds plus
    // one table lookup — same family as the standard SRC libraries.
    //
    // 256 bins gives ~0.004-sample precision; 6 taps gives < -60 dB
    // alias rejection at quarter-Nyquist. Total table size 6 KB.
    private static final int LANCZOS_BINS = 256;
    private static final int LANCZOS_TAPS = 6;     // -2..+3
    private final float[][] lanczosTable = new float[LANCZOS_BINS][LANCZOS_TAPS];

    // ============================================================
    //  PSOLA two-voice state (operates on LPC residual)
    // ============================================================
    private float[] residualBuf;
    private int residualBufLen;
    private int residualWrite = 0;
    // Floating-point source positions in residualBuf.
    private float voiceA_srcPos;
    private float voiceB_srcPos;
    private int voiceA_phase;
    private int voiceB_phase;
    private int voiceA_outLen;
    private int voiceB_outLen;
    private float voiceA_srcLen;
    private float voiceB_srcLen;

    // ============================================================
    //  Transient bypass
    // ============================================================
    private float fastEnv = 0f;
    private float slowEnv = 1e-4f;
    private float transientGate = 0f;   // 0 = correcting, 1 = bypassing
    private float silenceEnv = 0f;      // tracks dry-input amplitude for silence muting

    // ============================================================
    //  Voicing gate + humanize
    // ============================================================
    private float voicingGate = 0f;
    private float humanizeState = 0f;

    // ============================================================
    //  Misc
    // ============================================================
    private int sampleRate = 44100;
    private long noiseSeed = 1;

    // ============================================================
    //  Parameters
    // ============================================================
    private float preset = 0f;    // 0 = Custom, 1..6 = built-in voicings
    private float key = 0f;
    private float scaleMode = 0f;
    private float retune = 0.3f;
    private float humanize = 0.15f;
    private float strength = 1f;
    private float formant = 1f;   // 0 = off (chipmunk shifts), 1 = full preservation
    private float mix = 1f;

    // Built-in preset values for retune / humanize / strength / formant.
    // When `preset` parameter is set non-zero, these override the four
    // individual sliders. Key, scale and mix stay user-controlled because
    // they're project- / mixing-specific, not voicing choices.
    //
    //                       retune humanize strength formant
    //   1. Natural          0.45   0.30     0.70     1.00   gentle pop, preserves performance
    //   2. Pop              0.20   0.15     0.95     1.00   modern pop correction
    //   3. Hard / Snap      0.00   0.00     1.00     0.80   T-Pain instant pitch snap
    //   4. Cher             0.00   0.00     1.00     0.00   the original "Believe" chipmunk
    //   5. Country          0.60   0.35     0.60     1.00   slow drift preserved, gentle
    //   6. Subtle           0.70   0.40     0.40     1.00   barely there ride correction
    private static final float[][] PRESETS = {
        {  0,    0,    0,    0   }, // 0: Custom (unused, sentinel)
        { 0.45f, 0.30f, 0.70f, 1.00f }, // Natural
        { 0.20f, 0.15f, 0.95f, 1.00f }, // Pop
        { 0.00f, 0.00f, 1.00f, 0.80f }, // Hard
        { 0.00f, 0.00f, 1.00f, 0.00f }, // Cher
        { 0.60f, 0.35f, 0.60f, 1.00f }, // Country
        { 0.70f, 0.40f, 0.40f, 1.00f }, // Subtle
    };

    private static final int[] SCALE_CHROMATIC = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 };
    private static final int[] SCALE_MAJOR     = { 0, 2, 4, 5, 7, 9, 11 };
    private static final int[] SCALE_MINOR     = { 0, 2, 3, 5, 7, 8, 10 };
    private static final int[] SCALE_HARMONIC  = { 0, 2, 3, 5, 7, 8, 11 };
    private static final int[] SCALE_PENT_MAJ  = { 0, 2, 4, 7, 9 };
    private static final int[] SCALE_PENT_MIN  = { 0, 3, 5, 7, 10 };

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        analysisWrite = 0;
        for (int i = 0; i < ANALYSIS_SIZE; i++) analysisBuf[i] = 0f;
        for (int i = 0; i < yinD.length; i++) { yinD[i] = 0f; yinCMND[i] = 0f; }

        // Build the Lanczos-3 weight table once at init.
        for (int b = 0; b < LANCZOS_BINS; b++) {
            float frac = (float) b / LANCZOS_BINS;
            float wSum = 0f;
            for (int t = 0; t < LANCZOS_TAPS; t++) {
                float x = (t - 2) - frac;   // tap offsets: -2..+3
                float w;
                if (x == 0f) {
                    w = 1f;
                } else if (x <= -3f || x >= 3f) {
                    w = 0f;
                } else {
                    double piX = Math.PI * x;
                    double piXa = piX / 3.0;
                    w = (float) (Math.sin(piX) * Math.sin(piXa) / (piX * piXa));
                }
                lanczosTable[b][t] = w;
                wSum += w;
            }
            // Normalise so DC gain is exactly 1 (otherwise small
            // amplitude error accumulates at non-integer reads).
            if (Math.abs(wSum) > 1e-9f) {
                for (int t = 0; t < LANCZOS_TAPS; t++) lanczosTable[b][t] /= wSum;
            }
        }

        // Lookahead buffer (20 ms) — enough head-start for the ratio
        // IIR to start moving before each new note arrives at the
        // output, without eating the first 100 ms of short audio
        // files. Previously was 100 ms which felt great on long takes
        // but produced "silence then crackle" on clips under ~250 ms
        // because the lookahead delay consumed most of the input.
        lookaheadSamples = (int) (sr * 0.02);
        inputDelayRingLen = lookaheadSamples + 64;  // small headroom
        inputDelayRing = new float[inputDelayRingLen];
        inputDelayWrite = 0;
        lpcAnalysisBuf = new float[ANALYSIS_SIZE];
        lpcAnalysisWrite = 0;

        residualBufLen = sr;
        residualBuf = new float[residualBufLen];
        residualWrite = 0;
        voiceA_srcPos = residualBufLen - 200f;
        voiceB_srcPos = residualBufLen - 300f;
        voiceA_phase = 0;
        voiceB_phase = 100;
        voiceA_outLen = 200; voiceB_outLen = 200;
        voiceA_srcLen = 200f; voiceB_srcLen = 200f;

        for (int i = 0; i < LPC_ORDER; i++) {
            invInputDelay[i] = 0f;
            synthOutputDelay[i] = 0f;
        }
        for (int i = 0; i <= LPC_ORDER; i++) {
            lpcA[i] = lpcAtarget[i] = lpcAprev[i] = (i == 0 ? 1f : 0f);
        }
        preEmphasisPrev = 0f;
        deEmphasisPrev = 0f;
        samplesSinceLPC = 0;
        lpcRampPos = 1f;

        detectedFreq = 220f;
        detectedPeriod = sr / 220f;
        stableFreq = 220f;
        stableConfidence = 0f;
        voicingConfidence = 0f;
        targetRatio = 1f;
        currentRatio = 1f;
        noteCenter = 220f;
        for (int i = 0; i < PITCH_HISTORY; i++) pitchHistory[i] = 220f;
        pitchHistoryIdx = 0;
        pitchVariance = 0f;
        lastTargetCenter = 220f;
        unvoicedSamples = 0;

        fastEnv = 0f;
        slowEnv = 1e-4f;
        transientGate = 0f;
        silenceEnv = 0f;

        voicingGate = 0f;
        humanizeState = 0f;
        samplesSinceAnalysis = 0;
        noiseSeed = 1;
    }

    @Override
    public String[] parameterNames() {
        return new String[] { "preset", "key", "scale", "retune", "humanize", "strength", "formant", "mix" };
    }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "preset": return 6f;
            case "key":    return 11f;
            case "scale":  return 5f;
            default:       return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "preset":   return 0f;
            case "key":      return 0f;
            case "scale":    return 0f;
            case "retune":   return 0.3f;
            case "humanize": return 0.15f;
            case "strength": return 1f;
            case "formant":  return 1f;
            case "mix":      return 1f;
            default:         return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "preset":   return "Preset";
            case "key":      return "Key";
            case "scale":    return "Scale";
            case "retune":   return "Retune";
            case "humanize": return "Humanize";
            case "strength": return "Strength";
            case "formant":  return "Formant";
            case "mix":      return "Mix";
            default:         return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "preset":   preset = v; break;
            case "key":      key = v; break;
            case "scale":    scaleMode = v; break;
            case "retune":   retune = v; break;
            case "humanize": humanize = v; break;
            case "strength": strength = v; break;
            case "formant":  formant = v; break;
            case "mix":      mix = v; break;
        }
    }

    // ============================================================
    //  YIN pitch detection
    // ============================================================
    private float yinDetect() {
        final float[] aBuf = analysisBuf;
        final float[] yBuf = yinBuf;
        final int aw = analysisWrite;
        double energy = 0;
        for (int k = 0; k < ANALYSIS_SIZE; k++) {
            int idx = aw + k;
            if (idx >= ANALYSIS_SIZE) idx -= ANALYSIS_SIZE;
            float s = aBuf[idx];
            yBuf[k] = s;
            energy += s * s;
        }
        // Amplitude gate: YIN's CMND function can produce confident
        // pitch estimates on pure noise — silence + numerical noise still
        // has correlation structure. Without this gate, voicing stays open
        // through silent passages and PSOLA reads stale residual content,
        // producing a low-level buzz instead of true silence.
        float rms = (float) Math.sqrt(energy / ANALYSIS_SIZE);
        if (rms < 0.003f) {
            voicingConfidence = 0f;
            return -1f;
        }
        final int halfSize = ANALYSIS_SIZE / 2;
        final int maxLag = Math.min(halfSize, LAG_MAX);
        final float[] d = yinD;
        for (int tau = 1; tau <= maxLag; tau++) {
            float sum = 0f;
            for (int nn = 0; nn < halfSize; nn++) {
                float diff = yBuf[nn] - yBuf[nn + tau];
                sum += diff * diff;
            }
            d[tau] = sum;
        }
        final float[] cmnd = yinCMND;
        cmnd[0] = 1f;
        float runningSum = 0f;
        for (int tau = 1; tau <= maxLag; tau++) {
            runningSum += d[tau];
            if (runningSum > 1e-12f) cmnd[tau] = d[tau] * tau / runningSum;
            else                      cmnd[tau] = 1f;
        }
        int chosenTau = -1;
        for (int tau = LAG_MIN; tau < maxLag - 1; tau++) {
            if (cmnd[tau] < YIN_THRESHOLD) {
                while (tau + 1 < maxLag && cmnd[tau + 1] < cmnd[tau]) tau++;
                chosenTau = tau;
                break;
            }
        }
        if (chosenTau < 0) {
            float minVal = 1f;
            for (int tau = LAG_MIN; tau < maxLag; tau++) {
                if (cmnd[tau] < minVal) minVal = cmnd[tau];
            }
            voicingConfidence = 1f - minVal;
            if (voicingConfidence < 0f) voicingConfidence = 0f;
            return -1f;
        }
        float refined = chosenTau;
        if (chosenTau > 0 && chosenTau < maxLag) {
            float y1 = cmnd[chosenTau - 1];
            float y2 = cmnd[chosenTau];
            float y3 = cmnd[chosenTau + 1];
            float denom = 2f * (2f * y2 - y1 - y3);
            if (Math.abs(denom) > 1e-9f) {
                float adj = (y3 - y1) / denom;
                if (adj > -1f && adj < 1f) refined += adj;
            }
        }
        voicingConfidence = 1f - cmnd[chosenTau];
        if (voicingConfidence < 0f) voicingConfidence = 0f;
        return sampleRate / refined;
    }

    // Octave-error rejection: if a new detection jumps by close to ±1
    // octave from the stably-detected pitch AND confidence is borderline,
    // ignore it. Without this, a singer with strong 2nd harmonic can
    // cause YIN to flip-flop between f and 2f.
    private float octaveCorrect(float f0) {
        if (stableConfidence > 0.6f && f0 > 0f) {
            double ratio = f0 / stableFreq;
            double octaves = Math.log(ratio) / Math.log(2.0);
            // Within ±0.1 of ±1 octave from stable, and confidence not
            // overwhelmingly high → reject and keep stable estimate.
            if (Math.abs(Math.abs(octaves) - 1.0) < 0.1 && voicingConfidence < 0.85f) {
                return stableFreq;
            }
        }
        // Smooth a separate "stable" track with a heavy IIR (slow trust).
        if (f0 > 0f && voicingConfidence > 0.5f) {
            stableFreq = stableFreq + 0.05f * (f0 - stableFreq);
            stableConfidence = stableConfidence + 0.05f * (voicingConfidence - stableConfidence);
        } else {
            stableConfidence = stableConfidence * 0.95f;
        }
        return f0;
    }

    // ============================================================
    //  Scale quantiser
    // ============================================================
    private float snapToScale(float freq) {
        if (freq <= 0f) return freq;
        int[] scaleTable;
        int sm = (int) Math.floor(scaleMode);
        switch (sm) {
            case 1:  scaleTable = SCALE_MAJOR; break;
            case 2:  scaleTable = SCALE_MINOR; break;
            case 3:  scaleTable = SCALE_HARMONIC; break;
            case 4:  scaleTable = SCALE_PENT_MAJ; break;
            case 5:  scaleTable = SCALE_PENT_MIN; break;
            default: scaleTable = SCALE_CHROMATIC; break;
        }
        int rootKey = ((int) Math.floor(key)) % 12;
        if (rootKey < 0) rootKey += 12;
        double semis = 12.0 * Math.log(freq / 440.0) / Math.log(2.0) + 69.0;
        int nearestSemi = (int) Math.round(semis);
        double bestDist = 1e9;
        int bestSemi = nearestSemi;
        for (int trySemi = nearestSemi - 3; trySemi <= nearestSemi + 3; trySemi++) {
            int relToKey = ((trySemi - rootKey) % 12 + 12) % 12;
            boolean inScale = false;
            for (int k = 0; k < scaleTable.length; k++) {
                if (scaleTable[k] == relToKey) { inScale = true; break; }
            }
            if (!inScale) continue;
            double dist = Math.abs(trySemi - semis);
            if (dist < bestDist) { bestDist = dist; bestSemi = trySemi; }
        }
        return (float) (440.0 * Math.pow(2.0, (bestSemi - 69) / 12.0));
    }

    private float nextRandom() {
        noiseSeed = noiseSeed * 1664525L + 1013904223L;
        long u = noiseSeed & 0xFFFFFFFFL;
        return ((float) u / 2147483648f) - 1f;
    }

    // ============================================================
    //  LPC analysis (autocorrelation + Levinson-Durbin, order 12)
    // ============================================================
    //
    // Computes the predictor coefficients a[1..P] such that the predicted
    // sample x̂[n] = -Σₖ a[k] · x[n-k] minimises mean-squared error vs the
    // actual x[n]. Equivalently, the polynomial 1 + Σ a[k]·z⁻ᵏ is the
    // inverse filter that whitens the signal — its roots are at the
    // formant frequencies on the unit circle (when |a|<1).
    //
    // We then apply the inverse filter to live input to recover the
    // residual (LPC excitation), pitch-shift that, and re-apply the
    // synthesis filter to restore the original formant envelope at the
    // shifted pitch.
    private void computeLPCFrame() {
        // Pull the most recent LPC_FRAME_SIZE samples from the DELAYED
        // input buffer (which holds what the inverse filter is seeing).
        // Hamming window to reduce spectral leakage in the autocorrelation.
        final float[] aBuf = lpcAnalysisBuf;
        final int aw = lpcAnalysisWrite;
        final int frameStart = ANALYSIS_SIZE - LPC_FRAME_SIZE;
        final float twoPiOverN = (float) (2.0 * Math.PI / (LPC_FRAME_SIZE - 1));
        for (int k = 0; k < LPC_FRAME_SIZE; k++) {
            int idx = aw + frameStart + k;
            if (idx >= ANALYSIS_SIZE) idx -= ANALYSIS_SIZE;
            float hamming = 0.54f - 0.46f * (float) Math.cos(twoPiOverN * k);
            lpcFrame[k] = aBuf[idx] * hamming;
        }

        // 2. Autocorrelation R[0..P].
        final float[] R = lpcAuto;
        for (int lag = 0; lag <= LPC_ORDER; lag++) {
            float sum = 0f;
            for (int i = 0; i < LPC_FRAME_SIZE - lag; i++) {
                sum += lpcFrame[i] * lpcFrame[i + lag];
            }
            R[lag] = sum;
        }
        if (R[0] < 1e-10f) {
            // Frame is essentially silent — set coefs to passthrough.
            lpcAtarget[0] = 1f;
            for (int k = 1; k <= LPC_ORDER; k++) lpcAtarget[k] = 0f;
            return;
        }

        // 3. Levinson-Durbin recursion.
        //
        //    e₀ = R[0]
        //    for m = 1..P:
        //      kₘ = -(R[m] + Σⱼ₌₁..ₘ₋₁ a[j] · R[m-j]) / eₘ₋₁
        //      a'[m] = kₘ
        //      a'[j] = a[j] + kₘ · a[m-j] for j = 1..m-1
        //      eₘ = eₘ₋₁ · (1 - kₘ²)
        //
        final float[] a = lpcAtarget;
        final float[] aOld = lpcAtmp;
        a[0] = 1f;
        for (int k = 1; k <= LPC_ORDER; k++) a[k] = 0f;
        float e = R[0];
        for (int m = 1; m <= LPC_ORDER; m++) {
            float acc = R[m];
            for (int j = 1; j < m; j++) acc += a[j] * R[m - j];
            if (Math.abs(e) < 1e-12f) break;
            float km = -acc / e;
            // Clamp reflection coef to |k|<0.99 — guarantees the resulting
            // LPC polynomial has all roots inside the unit circle (stable
            // inverse + synthesis filter). Voiced frames almost never need
            // this; it's a safety net for transient frames and silence.
            if (km > 0.99f) km = 0.99f;
            if (km < -0.99f) km = -0.99f;
            // Copy current `a` into `aOld` before update.
            for (int j = 0; j <= m; j++) aOld[j] = a[j];
            // Update.
            a[m] = km;
            for (int j = 1; j < m; j++) {
                a[j] = aOld[j] + km * aOld[m - j];
            }
            e = e * (1f - km * km);
            if (e < 1e-12f) e = 1e-12f;
        }
        // Lattice can occasionally produce unstable coefs in pathological
        // frames. Cap magnitudes to keep the IIR stable on output.
        for (int k = 1; k <= LPC_ORDER; k++) {
            if (Float.isNaN(a[k]) || Float.isInfinite(a[k])) a[k] = 0f;
            if (a[k] > 3f) a[k] = 3f;
            if (a[k] < -3f) a[k] = -3f;
        }
        // Bandwidth expansion: a[k] *= γ^k. Slightly broadens formant
        // resonances for a more natural, less-ringing synthesis filter.
        float gPow = 1f;
        for (int k = 1; k <= LPC_ORDER; k++) {
            gPow *= LPC_GAMMA;
            a[k] *= gPow;
        }
    }

    // Linear interpolation between lpcAprev and lpcAtarget by ramp position.
    private void interpolateLPC(float ramp) {
        float oneMinus = 1f - ramp;
        for (int k = 0; k <= LPC_ORDER; k++) {
            lpcA[k] = lpcAprev[k] * oneMinus + lpcAtarget[k] * ramp;
        }
    }

    // LPC inverse-filter step (whiten): r[n] = x[n] + Σₖ a[k]·x[n-k]
    private float lpcInverseStep(float x) {
        float r = x;
        final float[] a = lpcA;
        final float[] z = invInputDelay;
        for (int k = 0; k < LPC_ORDER; k++) {
            r += a[k + 1] * z[k];
        }
        for (int k = LPC_ORDER - 1; k > 0; k--) z[k] = z[k - 1];
        z[0] = x;
        return r;
    }

    // LPC synthesis-filter step (recover formants): y[n] = r[n] - Σₖ a[k]·y[n-k]
    private float lpcSynthStep(float r) {
        float y = r;
        final float[] a = lpcA;
        final float[] z = synthOutputDelay;
        for (int k = 0; k < LPC_ORDER; k++) {
            y -= a[k + 1] * z[k];
        }
        for (int k = LPC_ORDER - 1; k > 0; k--) z[k] = z[k - 1];
        z[0] = y;
        return y;
    }

    // ============================================================
    //  Lanczos-3 windowed-sinc interpolation
    // ============================================================
    //
    // 6-tap fractional read. Alias rejection at quarter-Nyquist > 60 dB
    // (vs ~35 dB for cubic Hermite). Audible improvement: cleaner upper
    // harmonics, no "shrinky" buzz when grain reads at off-period
    // fractional positions.
    private float residualRead(float pos) {
        while (pos < 0) pos += residualBufLen;
        while (pos >= residualBufLen) pos -= residualBufLen;
        int i1 = (int) pos;
        float frac = pos - i1;
        int bin = (int) (frac * LANCZOS_BINS);
        if (bin >= LANCZOS_BINS) bin = LANCZOS_BINS - 1;
        if (bin < 0) bin = 0;
        final float[] w = lanczosTable[bin];
        final float[] buf = residualBuf;
        final int bL = residualBufLen;
        // Tap offsets -2..+3 around i1.
        int idx0 = i1 - 2; if (idx0 < 0) idx0 += bL;
        int idx1 = i1 - 1; if (idx1 < 0) idx1 += bL;
        int idx2 = i1;
        int idx3 = i1 + 1; if (idx3 >= bL) idx3 -= bL;
        int idx4 = i1 + 2; if (idx4 >= bL) idx4 -= bL;
        int idx5 = i1 + 3; if (idx5 >= bL) idx5 -= bL;
        return buf[idx0] * w[0] + buf[idx1] * w[1] + buf[idx2] * w[2]
             + buf[idx3] * w[3] + buf[idx4] * w[4] + buf[idx5] * w[5];
    }

    private float wrapIfClose(float srcPos, float srcLen) {
        float dist = residualWrite - srcPos;
        while (dist < 0) dist += residualBufLen;
        while (dist >= residualBufLen) dist -= residualBufLen;
        if (dist < srcLen) {
            srcPos -= srcLen;
            while (srcPos < 0) srcPos += residualBufLen;
        }
        return srcPos;
    }

    // Glottal-closure-instant snap. The LPC residual of a voiced signal
    // is essentially a train of impulses at the moments of vocal-fold
    // closure (each "glottal pulse"). Aligning PSOLA grain centres to
    // these impulses minimises the phase mismatch at grain boundaries
    // and gives the cleanest possible PSOLA output. We search a window
    // of ±T_in/4 around the proposed grain start position for the
    // sample with maximum |residual|. That's our GCI estimate.
    //
    // For unvoiced regions the residual is noise-like and the search
    // returns an arbitrary peak, but the grain won't be audible during
    // unvoiced regions anyway (voicing gate closes), so this is fine.
    private float snapToGCI(float expectedPos, float period) {
        // Tight snap window — ±period/8. Wider windows let the snap jump
        // to a neighbouring glottal cycle, breaking grain-to-grain phase
        // continuity. ±period/8 is enough to track natural GCI drift
        // (which is small per period) without allowing cross-cycle jumps.
        int radius = (int) (period * 0.125f);
        if (radius < 3) radius = 3;
        int center = (int) expectedPos;
        int bestOffset = 0;
        float bestSignedVal = 0f;
        float bestAbsVal = -1f;
        final float[] r = residualBuf;
        final int bL = residualBufLen;
        // Lock onto POSITIVE peaks only. Pre-emphasised LPC residual of a
        // voiced signal has dominantly positive-going glottal-closure
        // impulses; locking on |value| can land on either polarity, which
        // produces phase-inverted grains and audible sign-flip glitches
        // at note transitions.
        for (int o = -radius; o <= radius; o++) {
            int idx = center + o;
            while (idx < 0) idx += bL;
            while (idx >= bL) idx -= bL;
            float v = r[idx];
            if (v > bestSignedVal) { bestSignedVal = v; bestOffset = o; }
            float a = v < 0 ? -v : v;
            if (a > bestAbsVal) bestAbsVal = a;
        }
        // Pure-sine LPC residual is essentially zero — there are no real
        // glottal pulses to find, just floating-point noise. If the peak
        // is below a sensible voiced-signal threshold, skip the snap and
        // let the synthetic pitch-mark schedule control grain timing.
        if (bestAbsVal < 0.01f) return expectedPos;
        // If the best peak isn't meaningfully larger than the largest peak
        // we found (e.g., dominant peak in window is negative — antiphase
        // residual), skip the snap rather than locking onto a weak positive
        // peak that doesn't represent a real glottal closure.
        if (bestSignedVal < bestAbsVal * 0.5f) return expectedPos;
        return expectedPos + bestOffset;
    }

    // ============================================================
    //  process()
    // ============================================================
    @Override
    public void process(float[] input, float[] output) {
        final int n = input.length;
        final float[] aBuf = analysisBuf;
        final float[] rBuf = residualBuf;
        final int rBL = residualBufLen;

        // --- Preset lookup ---
        //
        // If `preset` is non-zero (rounded to 1..6), override the
        // user-set retune / humanize / strength / formant with the
        // values baked into that preset. Key / scale / mix stay
        // user-controlled because they depend on the song, not on
        // the voicing choice.
        final float effRetune, effHumanize, effStrength, effFormantParam;
        int presetInt = (int) (preset + 0.5f);
        if (presetInt >= 1 && presetInt < PRESETS.length) {
            float[] p = PRESETS[presetInt];
            effRetune       = p[0];
            effHumanize     = p[1];
            effStrength     = p[2];
            effFormantParam = p[3];
        } else {
            effRetune       = retune;
            effHumanize     = humanize;
            effStrength     = strength;
            effFormantParam = formant;
        }

        // Smoothing time constants.
        // Adaptive retune: base time is `retune`-driven (1ms..400ms). When
        // pitch variance is high (singer mid-slide between notes), shrink
        // the time constant so the correction snaps on faster. When the
        // pitch is stable (sustained note), keep the base time so vibrato
        // and natural micro-variation aren't crushed.
        final float retuneSec = 0.001f + effRetune * effRetune * 0.4f;
        // Variance is in Hz²; for a typical 5 Hz / ±5 Hz vibrato it sits
        // around 12; for a note slide it can reach hundreds.
        final float varSpeedup = 1f / (1f + pitchVariance * 0.02f);
        final float adaptiveRetuneSec = retuneSec * varSpeedup;
        final float ratioCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * adaptiveRetuneSec));
        final float voiceOpen = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));
        final float voiceClose = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.060));
        final float humanCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.250));
        final float humanMaxCents = effHumanize * 15f;
        final float fastEnvCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.003));
        final float slowEnvCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.080));
        final float transOpenCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.001));
        final float transCloseCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.040));
        // Per-sample increment of LPC ramp position (so ramps complete
        // exactly in LPC_UPDATE_INTERVAL samples).
        final float lpcRampStep = 1f / LPC_UPDATE_INTERVAL;
        final float formantLocal = effFormantParam;
        final float strengthLocal = effStrength;
        final float mixLocal = mix;
        final float dryMix = 1f - mixLocal;

        int aw = analysisWrite;
        int rw = residualWrite;
        int ssa = samplesSinceAnalysis;
        int ssl = samplesSinceLPC;
        float currentR = currentRatio;
        float targetR = targetRatio;
        float voiceG = voicingGate;
        float humState = humanizeState;
        float pePrev = preEmphasisPrev;
        float dePrev = deEmphasisPrev;
        float fEnv = fastEnv;
        float sEnv = slowEnv;
        float tGate = transientGate;
        float rampPos = lpcRampPos;
        float vAsrc = voiceA_srcPos, vBsrc = voiceB_srcPos;
        int vAphase = voiceA_phase, vBphase = voiceB_phase;
        int vAoutLen = voiceA_outLen, vBoutLen = voiceB_outLen;
        float vAsrcLen = voiceA_srcLen, vBsrcLen = voiceB_srcLen;

        final float[] idRing = inputDelayRing;
        final int idRingLen = inputDelayRingLen;
        final float[] lpcABuf = lpcAnalysisBuf;
        int idWrite = inputDelayWrite;
        int lpcAW = lpcAnalysisWrite;
        final int lookSamples = lookaheadSamples;

        for (int i = 0; i < n; i++) {
            // xLatest = the freshly-arrived input. YIN reads from this so
            // its pitch detections are "ahead" of the audio that's actually
            // flowing through the LPC/PSOLA pipeline.
            final float xLatest = input[i];

            // Push into the lookahead ring and pull out the corresponding
            // sample from LOOKAHEAD samples ago. That's the sample the
            // processing chain will operate on this iteration.
            idRing[idWrite] = xLatest;
            int idRead = idWrite - lookSamples;
            if (idRead < 0) idRead += idRingLen;
            final float xDelayed = idRing[idRead];
            idWrite++; if (idWrite >= idRingLen) idWrite = 0;

            // YIN's analysis buffer sees the latest input — that's the
            // lookahead.
            aBuf[aw] = xLatest;
            aw++; if (aw >= ANALYSIS_SIZE) aw = 0;

            // LPC's analysis buffer sees the DELAYED input — so its
            // formant estimate matches the input being inverse-filtered.
            lpcABuf[lpcAW] = xDelayed;
            lpcAW++; if (lpcAW >= ANALYSIS_SIZE) lpcAW = 0;

            // Trigger analyses periodically.
            ssa++;
            if (ssa >= ANALYSIS_INTERVAL) {
                ssa = 0;
                analysisWrite = aw;
                float f0 = yinDetect();
                if (f0 > 0f) f0 = octaveCorrect(f0);
                if (f0 > 50f && f0 < 2000f) {
                    detectedFreq = f0;
                    detectedPeriod = sampleRate / f0;

                    // --- Vibrato preservation pipeline ---
                    //
                    // We need to separate the SLOWLY-CHANGING centre of
                    // the note from the FAST natural pitch micro-variation
                    // (vibrato). The centre gets quantised to the scale
                    // grid; the variation is added back unchanged. This
                    // way a 5 Hz, ±20-cent vibrato around 220 Hz becomes
                    // a 5 Hz, ±20-cent vibrato around the snapped target
                    // (e.g. still 220 Hz, or 247 Hz, etc.) — instead of
                    // being flattened by the correction.
                    pitchHistory[pitchHistoryIdx] = f0;
                    pitchHistoryIdx = (pitchHistoryIdx + 1) % PITCH_HISTORY;
                    // Slow IIR on f0 → note centre. ~200 ms time constant
                    // at 170 Hz YIN rate ≈ 30 detections to settle.
                    noteCenter = noteCenter + 0.07f * (f0 - noteCenter);

                    // Pitch variance over recent history → adaptive
                    // retune speed. High variance = singer is moving
                    // between notes, snap faster. Low variance = sustained
                    // note, ease retune so vibrato isn't crushed.
                    float varAcc = 0f;
                    for (int k = 0; k < PITCH_HISTORY; k++) {
                        float d = pitchHistory[k] - noteCenter;
                        varAcc += d * d;
                    }
                    pitchVariance = pitchVariance + 0.2f * (varAcc / PITCH_HISTORY - pitchVariance);
                    if (pitchVariance < 0f) pitchVariance = 0f;

                    // Snap centre, not raw f0, to scale grid.
                    float targetCenter = snapToScale(noteCenter);
                    lastTargetCenter = targetCenter;
                    // Add back the natural deviation around centre.
                    targetR = (targetCenter + (f0 - noteCenter)) / f0;
                    unvoicedSamples = 0;
                } else {
                    // Unvoiced — confidence-hold the last targetR for
                    // up to ~100 ms (so brief consonants don't reset the
                    // correction) then ease toward passthrough.
                    unvoicedSamples += ANALYSIS_INTERVAL;
                    if (unvoicedSamples > sampleRate / 10) {
                        targetR = targetR + 0.3f * (1f - targetR);
                    }
                }
            }
            ssl++;
            totalSamplesProcessed++;
            // Hold off LPC analysis until the analysis buffer has been
            // fully populated at least once — otherwise the half-zero
            // half-signal frame produces extreme coefficients that
            // destabilise the IIR even with the reflection-coef clamp.
            if (ssl >= LPC_UPDATE_INTERVAL && totalSamplesProcessed >= ANALYSIS_SIZE + lookSamples) {
                ssl = 0;
                lpcAnalysisWrite = lpcAW;
                for (int k = 0; k <= LPC_ORDER; k++) lpcAprev[k] = lpcA[k];
                computeLPCFrame();
                rampPos = 0f;
            }

            // Interpolate LPC coefs toward target over the inter-update period.
            if (rampPos < 1f) {
                rampPos += lpcRampStep;
                if (rampPos > 1f) rampPos = 1f;
                interpolateLPC(rampPos);
            } else if (rampPos >= 1f) {
                // Lock to target once ramp completes.
                for (int k = 0; k <= LPC_ORDER; k++) lpcA[k] = lpcAtarget[k];
            }

            // --- Transient detection on the LATEST input ---
            // Look-ahead transient detection: when a consonant lands at
            // xLatest, the gate opens immediately. By the time that
            // consonant reaches the OUTPUT (LOOKAHEAD samples later), the
            // bypass is fully engaged. Smooth pass-through.
            float rect = xLatest < 0 ? -xLatest : xLatest;
            fEnv = fEnv + fastEnvCoef * (rect - fEnv);
            sEnv = sEnv + slowEnvCoef * (rect - sEnv);
            if (sEnv < 1e-6f) sEnv = 1e-6f;
            float transTarget = (fEnv > sEnv * 2.5f) ? 1f : 0f;
            float tCoef = transTarget > tGate ? transOpenCoef : transCloseCoef;
            tGate = tGate + tCoef * (transTarget - tGate);

            // --- Pre-emphasis + LPC inverse (whiten) on DELAYED input ---
            // Processing chain operates on xDelayed; YIN's pitch and the
            // transient detector are already "ahead" of this point, so the
            // ratio and gates are already at their target by the time the
            // audio reaches the output.
            float xPre = xDelayed - PRE_EMPHASIS * pePrev;
            pePrev = xDelayed;
            float residual;
            if (formantLocal > 0.001f) {
                residual = lpcInverseStep(xPre);
            } else {
                residual = xDelayed;
                final float[] z = invInputDelay;
                for (int k = LPC_ORDER - 1; k > 0; k--) z[k] = z[k - 1];
                z[0] = xPre;
            }

            // Write residual to PSOLA source buffer.
            rBuf[rw] = residual;
            rw++; if (rw >= rBL) rw = 0;
            residualWrite = rw;   // wrapIfClose() reads this field

            // --- Voicing & humanize ---
            float voiceTarget = voicingConfidence > 0.3f ? 1f : 0f;
            float vCoef = voiceTarget > voiceG ? voiceOpen : voiceClose;
            voiceG = voiceG + vCoef * (voiceTarget - voiceG);

            float humTargetCents = nextRandom() * humanMaxCents;
            humState = humState + humanCoef * (humTargetCents - humState);
            float humanFactor = (float) Math.pow(2.0, humState / 1200.0);

            float effectiveTarget = 1f + (targetR - 1f) * strengthLocal;
            currentR = currentR + ratioCoef * (effectiveTarget - currentR);
            float playRatio = currentR * humanFactor;
            if (playRatio < 0.5f) playRatio = 0.5f;
            if (playRatio > 2.0f) playRatio = 2.0f;

            // --- TD-PSOLA on residual ---
            float T_in = detectedPeriod;
            if (T_in < 16f) T_in = 16f;
            if (T_in > 1000f) T_in = 1000f;

            if (vAphase >= vAoutLen) {
                vAphase = 0;
                vAsrcLen = T_in;
                vAoutLen = (int) Math.max(8, Math.round(T_in / playRatio));
                vAsrc = vAsrc + vAsrcLen;
                while (vAsrc >= rBL) vAsrc -= rBL;
                while (vAsrc < 0) vAsrc += rBL;
                vAsrc = wrapIfClose(vAsrc, vAsrcLen);
                // Snap to nearest glottal-closure instant in the residual.
                vAsrc = snapToGCI(vAsrc, vAsrcLen);
                while (vAsrc < 0) vAsrc += rBL;
                while (vAsrc >= rBL) vAsrc -= rBL;
            }
            float fracA = (float) vAphase / (float) vAoutLen;
            float sA = residualRead(vAsrc + fracA * vAsrcLen);
            float envA = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * fracA);
            vAphase++;

            if (vBphase >= vBoutLen) {
                vBphase = 0;
                vBsrcLen = T_in;
                vBoutLen = (int) Math.max(8, Math.round(T_in / playRatio));
                vBsrc = vBsrc + vBsrcLen;
                while (vBsrc >= rBL) vBsrc -= rBL;
                while (vBsrc < 0) vBsrc += rBL;
                vBsrc = wrapIfClose(vBsrc, vBsrcLen);
                vBsrc = snapToGCI(vBsrc, vBsrcLen);
                while (vBsrc < 0) vBsrc += rBL;
                while (vBsrc >= rBL) vBsrc -= rBL;
            }
            float fracB = (float) vBphase / (float) vBoutLen;
            float sB = residualRead(vBsrc + fracB * vBsrcLen);
            float envB = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * fracB);
            vBphase++;

            // Envelope normalisation: with GCI snap moving each voice's
            // anchor independently, envA + envB no longer sums exactly
            // to 1.0 (the theoretical guarantee from staggered Hann
            // overlap only holds when the two voices stay precisely
            // half-period apart). Without normalisation, amplitude
            // wobbles at the grain rate. Dividing by the actual envelope
            // sum eliminates that wobble.
            float envSum = envA + envB;
            if (envSum < 1e-4f) envSum = 1e-4f;
            float shiftedResidual = (sA * envA + sB * envB) / envSum;

            // --- LPC synthesis (restore formants) + de-emphasis ---
            float yPre;
            if (formantLocal > 0.001f) {
                yPre = lpcSynthStep(shiftedResidual);
                // De-emphasis: y[n] = y_pre[n] + α · y[n-1]
                float yOut = yPre + PRE_EMPHASIS * dePrev;
                dePrev = yOut;
                yPre = yOut;
                // Crossfade between formant-preserved and naive PSOLA by the
                // `formant` knob (0..1).
                yPre = shiftedResidual * (1f - formantLocal) + yPre * formantLocal;
            } else {
                yPre = shiftedResidual;
                // Keep delay state tracking so toggling formant on is smooth.
                final float[] z = synthOutputDelay;
                for (int k = LPC_ORDER - 1; k > 0; k--) z[k] = z[k - 1];
                z[0] = shiftedResidual;
                dePrev = yPre;
            }

            // --- Voicing + transient blend with DELAYED dry ---
            // Dry side must come from xDelayed so the dry/wet timeline
            // stays coherent with the wet (which is the corrected delayed
            // input).
            // Silence gate: peak-follower (fast attack, 30 ms release) on
            // the dry input. When the dry peak falls below ~-60 dBFS the
            // wet contribution is muted. Without this, the slow
            // voicing-close IIR keeps gateMix elevated through ~60 ms of
            // silence after a note ends, during which the PSOLA pipeline
            // keeps reading stale residual and produces a low-level buzz
            // where the dry signal is true silence.
            float drySignalAbs = xDelayed < 0 ? -xDelayed : xDelayed;
            if (drySignalAbs > silenceEnv) {
                silenceEnv = drySignalAbs;             // instant attack
            } else {
                silenceEnv += transCloseCoef * (drySignalAbs - silenceEnv);  // ~40 ms release
            }
            // silenceMul ramps 0..1 over input range 0.0005..0.002
            float silenceMul;
            if (silenceEnv < 0.0005f) silenceMul = 0f;
            else if (silenceEnv > 0.002f) silenceMul = 1f;
            else silenceMul = (silenceEnv - 0.0005f) / 0.0015f;
            float gateMix = voiceG * (1f - tGate) * silenceMul;
            float wet = xDelayed * (1f - gateMix) + yPre * gateMix;
            output[i] = xDelayed * dryMix + wet * mixLocal;
        }

        analysisWrite = aw;
        residualWrite = rw;
        inputDelayWrite = idWrite;
        lpcAnalysisWrite = lpcAW;
        samplesSinceAnalysis = ssa;
        samplesSinceLPC = ssl;
        currentRatio = currentR;
        targetRatio = targetR;
        voicingGate = voiceG;
        humanizeState = humState;
        preEmphasisPrev = pePrev;
        deEmphasisPrev = dePrev;
        fastEnv = fEnv;
        slowEnv = sEnv;
        transientGate = tGate;
        lpcRampPos = rampPos;
        voiceA_srcPos = vAsrc; voiceB_srcPos = vBsrc;
        voiceA_phase = vAphase; voiceB_phase = vBphase;
        voiceA_outLen = vAoutLen; voiceB_outLen = vBoutLen;
        voiceA_srcLen = vAsrcLen; voiceB_srcLen = vBsrcLen;
    }
}
