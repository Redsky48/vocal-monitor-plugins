package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Auto-Tune — production-grade vocal pitch correction. Three engines
// wired in series:
//
//   1. YIN pitch detector  — the published de Cheveigné/Kawahara
//      algorithm that Antares Auto-Tune and Melodyne use for the
//      monophonic case. Operates on a 1024-sample sliding window;
//      cumulative-mean-normalized difference function makes it
//      octave-robust where plain autocorrelation fails. Parabolic
//      interpolation around the minimum gives cents-accurate sub-sample
//      period estimates. Runs every 256 samples → ~170 Hz update rate
//      at 44.1 kHz, fast enough to track vibrato.
//
//   2. Scale quantiser     — converts detected freq to MIDI cents,
//      finds the nearest pitch in the chosen scale (chromatic, major,
//      minor, harmonic minor, pentatonic), returns target freq.
//
//   3. Granular pitch shift — two crossfaded Hann-windowed grains read
//      at the (smoothed) correction ratio. Same family of pitch shift
//      as our PitchShifter plugin, but with a tight grain (~40 ms) and
//      a one-pole retune-time IIR on the ratio so the correction can be
//      anywhere from "Cher robotic instant snap" (retune=0) to "natural
//      sub-percent drift correction" (retune=1).
//
// Voicing gate: when YIN's CMND minimum exceeds the threshold the input
// is treated as unpitched (consonant, breath, transient) and we
// crossfade smoothly back to dry. Without this gate, sibilants come out
// as eerie pitched artefacts.
//
// Humanize: a slow random walk adds ±cents drift to prevent the dead-
// flat sound that pure pitch quantisation produces. At humanize=0 the
// correction is mathematically exact; at humanize=1 you get natural
// micro-variation around the target.

public final class AutoTune implements VocalMonitorNativePlugin {

    // --- Analysis (YIN) ---
    private static final int ANALYSIS_SIZE = 1024;   // 23 ms at 44.1k
    private static final int LAG_MIN = 32;           // 1378 Hz at 44.1k
    private static final int LAG_MAX = 512;          // 86 Hz at 44.1k
    private static final int ANALYSIS_INTERVAL = 256;
    private static final float YIN_THRESHOLD = 0.15f;

    private final float[] analysisBuf = new float[ANALYSIS_SIZE];
    private final float[] yinBuf = new float[ANALYSIS_SIZE];     // chronological copy
    private final float[] yinD = new float[LAG_MAX + 2];
    private final float[] yinCMND = new float[LAG_MAX + 2];
    private int analysisWrite = 0;

    // --- Pitch state ---
    private float detectedFreq = 220f;
    private float voicingConfidence = 0f;   // 0..1 — high means confidently pitched
    private float targetRatio = 1f;
    private float currentRatio = 1f;
    private float voicingGate = 0f;         // smoothed dry/wet for the correction path
    private float humanizeState = 0f;       // random walk

    // --- Granular pitch shift ---
    private float[] grainBuf;
    private int grainBufLen;
    private int grainWrite = 0;
    private int grainSize;
    private int grainPhase = 0;
    private float[] hann;

    private int sampleRate = 44100;
    private int samplesSinceAnalysis = 0;
    private long noiseSeed = 1;

    // --- Parameters ---
    private float key = 0f;        // 0=C..11=B
    private float scaleMode = 0f;  // 0=Chromatic 1=Major 2=Minor 3=Harm Minor 4=Pent Maj 5=Pent Min
    private float retune = 0.3f;   // 0=instant 1=natural
    private float humanize = 0.15f;
    private float strength = 1f;   // 0=dry only 1=full correction
    private float mix = 1f;

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

        // 40 ms grain — short enough that pitch shifts stay snappy, long
        // enough that low fundamentals get represented faithfully.
        grainSize = Math.max(64, (int) (sr * 0.04));
        // 3× headroom — covers ratios up to ~2.5 (about a tenth) safely.
        grainBufLen = grainSize * 3;
        grainBuf = new float[grainBufLen];
        grainWrite = 0;
        grainPhase = 0;
        hann = new float[grainSize];
        for (int i = 0; i < grainSize; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / grainSize));
        }

        detectedFreq = 220f;
        voicingConfidence = 0f;
        targetRatio = 1f;
        currentRatio = 1f;
        voicingGate = 0f;
        humanizeState = 0f;
        samplesSinceAnalysis = 0;
        noiseSeed = 1;
    }

    @Override
    public String[] parameterNames() {
        return new String[] { "key", "scale", "retune", "humanize", "strength", "mix" };
    }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "key":   return 11f;
            case "scale": return 5f;
            default:      return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "key":      return 0f;
            case "scale":    return 0f;
            case "retune":   return 0.3f;
            case "humanize": return 0.15f;
            case "strength": return 1f;
            case "mix":      return 1f;
            default:         return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "key":      return "Key";
            case "scale":    return "Scale";
            case "retune":   return "Retune";
            case "humanize": return "Humanize";
            case "strength": return "Strength";
            case "mix":      return "Mix";
            default:         return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "key":      key = v; break;
            case "scale":    scaleMode = v; break;
            case "retune":   retune = v; break;
            case "humanize": humanize = v; break;
            case "strength": strength = v; break;
            case "mix":      mix = v; break;
        }
    }

    // ---------------------------------------------------------------
    // YIN pitch detection
    // ---------------------------------------------------------------
    //
    // Returns detected fundamental frequency in Hz, or -1 if unvoiced.
    // Side effect: writes voicingConfidence in [0,1].
    private float yinDetect() {
        // 1. Materialise the most recent ANALYSIS_SIZE samples in
        //    chronological order. The ring buffer's `analysisWrite`
        //    points at the slot for the NEXT write, so the oldest sample
        //    is at index `analysisWrite` and the newest at
        //    `(analysisWrite - 1 + N) % N`.
        final float[] aBuf = analysisBuf;
        final float[] yBuf = yinBuf;
        final int aw = analysisWrite;
        for (int k = 0; k < ANALYSIS_SIZE; k++) {
            int idx = aw + k;
            if (idx >= ANALYSIS_SIZE) idx -= ANALYSIS_SIZE;
            yBuf[k] = aBuf[idx];
        }

        // 2. Difference function d(τ) = Σ (x[n] - x[n+τ])² over the
        //    first half of the window. Half-window so the lag plus the
        //    index stays inside the buffer.
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

        // 3. Cumulative mean normalised difference. This is the trick
        //    that makes YIN octave-robust: at the true period, d(τ) is
        //    small AND the running average up to that point is large,
        //    so d'(τ) dips well below 1. At octave multiples it's still
        //    small but the running average is even smaller, so d'(τ)
        //    stays close to 1 — no spurious lock to harmonics.
        final float[] cmnd = yinCMND;
        cmnd[0] = 1f;
        float runningSum = 0f;
        for (int tau = 1; tau <= maxLag; tau++) {
            runningSum += d[tau];
            if (runningSum > 1e-12f) {
                cmnd[tau] = d[tau] * tau / runningSum;
            } else {
                cmnd[tau] = 1f;
            }
        }

        // 4. Absolute-threshold step: scan for the first τ ≥ LAG_MIN
        //    where CMND drops below the threshold, then walk forward as
        //    long as the value keeps decreasing — that locates the true
        //    local minimum (not just the threshold crossing).
        int chosenTau = -1;
        for (int tau = LAG_MIN; tau < maxLag - 1; tau++) {
            if (cmnd[tau] < YIN_THRESHOLD) {
                while (tau + 1 < maxLag && cmnd[tau + 1] < cmnd[tau]) tau++;
                chosenTau = tau;
                break;
            }
        }

        // If no τ broke the threshold, pick the global minimum's depth
        // as a confidence number and report unvoiced.
        if (chosenTau < 0) {
            float minVal = 1f;
            for (int tau = LAG_MIN; tau < maxLag; tau++) {
                if (cmnd[tau] < minVal) minVal = cmnd[tau];
            }
            voicingConfidence = 1f - minVal;
            if (voicingConfidence < 0f) voicingConfidence = 0f;
            return -1f;
        }

        // 5. Parabolic interpolation around chosenTau to refine the
        //    period to fractional samples. Without this, detection
        //    quantises to ±50 cents around 220 Hz at 44.1 kHz — audible.
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

        // Confidence: how deep the minimum dropped. 0 = no dip, 1 = total.
        voicingConfidence = 1f - cmnd[chosenTau];
        if (voicingConfidence < 0f) voicingConfidence = 0f;

        return sampleRate / refined;
    }

    // ---------------------------------------------------------------
    // Scale quantiser
    // ---------------------------------------------------------------
    //
    // Given a detected frequency, find the nearest pitch in the
    // configured scale and return the corresponding target frequency.
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

        // Convert to MIDI semis (A4 = 69 = 440 Hz).
        double semis = 12.0 * Math.log(freq / 440.0) / Math.log(2.0) + 69.0;
        // Find nearest scale degree to the fractional MIDI value.
        int nearestSemi = (int) Math.round(semis);
        double bestDist = 1e9;
        int bestSemi = nearestSemi;
        // Search nearest-3-semitones in each direction so we catch
        // diatonic gaps (e.g. between B and C in major, only a semi apart).
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
        // LCG noise — deterministic, fast.
        noiseSeed = noiseSeed * 1664525L + 1013904223L;
        long u = noiseSeed & 0xFFFFFFFFL;
        return ((float) u / 2147483648f) - 1f;
    }

    // ---------------------------------------------------------------
    // process()
    // ---------------------------------------------------------------
    @Override
    public void process(float[] input, float[] output) {
        final int n = input.length;
        final float[] aBuf = analysisBuf;
        final float[] gBuf = grainBuf;
        final float[] hLut = hann;
        final int gs = grainSize;
        final int halfGs = gs / 2;
        final int gBL = grainBufLen;

        // Smoothing time constants.
        // Retune 0..1 → 1 ms..400 ms exponential ratio chase time.
        final float retuneSec = 0.001f + retune * retune * 0.4f;
        final float ratioCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * retuneSec));
        // Voicing gate: 5 ms attack to open, 60 ms release to close —
        // bias toward opening (start correcting fast) and closing slowly
        // (don't snap dry on a brief unvoiced moment mid-syllable).
        final float voiceOpen = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));
        final float voiceClose = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.060));
        // Humanize: slow random walk drifting ±N cents.
        final float humanCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.250));
        // Max drift in cents → ratio. ±15 cents at humanize=1.
        final float humanMaxCents = humanize * 15f;
        final float strengthLocal = strength;
        final float mixLocal = mix;
        final float dryMix = 1f - mixLocal;

        int aw = analysisWrite;
        int gw = grainWrite;
        int gp = grainPhase;
        int ssa = samplesSinceAnalysis;
        float currentR = currentRatio;
        float targetR = targetRatio;
        float voiceG = voicingGate;
        float humState = humanizeState;

        for (int i = 0; i < n; i++) {
            float x = input[i];

            // Fill both buffers in lock-step.
            aBuf[aw] = x;
            aw++; if (aw >= ANALYSIS_SIZE) aw = 0;
            gBuf[gw] = x;
            gw++; if (gw >= gBL) gw = 0;

            ssa++;
            if (ssa >= ANALYSIS_INTERVAL) {
                ssa = 0;
                // Sync the ring-buffer pointer into the field so yinDetect()
                // can read it; restore the local copy after.
                analysisWrite = aw;
                float f0 = yinDetect();
                if (f0 > 50f && f0 < 2000f) {
                    detectedFreq = f0;
                    float tgt = snapToScale(f0);
                    targetR = tgt / f0;
                } else {
                    // Unvoiced — keep targetR ramping back toward 1.0
                    // so when voicing returns the correction starts from
                    // unity rather than a stale aggressive ratio.
                    targetR = targetR + 0.5f * (1f - targetR);
                }
            }

            // Voicing gate target — confidence-driven. 0.3 is a safe
            // floor; below that, sibilants and breath would otherwise
            // be "corrected" with audible weirdness.
            float voiceTarget = voicingConfidence > 0.3f ? 1f : 0f;
            float vCoef = voiceTarget > voiceG ? voiceOpen : voiceClose;
            voiceG = voiceG + vCoef * (voiceTarget - voiceG);

            // Humanize: random walk in cents, smoothed.
            float humTargetCents = nextRandom() * humanMaxCents;
            humState = humState + humanCoef * (humTargetCents - humState);
            float humanFactor = (float) Math.pow(2.0, humState / 1200.0);

            // Smooth current ratio toward target, weighted by strength.
            // Strength = 0 → no correction (ratio always 1); strength = 1
            // → full snap to target.
            float effectiveTarget = 1f + (targetR - 1f) * strengthLocal;
            currentR = currentR + ratioCoef * (effectiveTarget - currentR);
            float playRatio = currentR * humanFactor;

            // Granular pitch shift with two crossfaded Hann grains.
            int pA = gp;
            int pB = pA + halfGs;
            if (pB >= gs) pB -= gs;
            float rA = gw - gs * playRatio + pA * playRatio;
            float rB = gw - gs * playRatio + pB * playRatio;
            while (rA < 0) rA += gBL;
            while (rA >= gBL) rA -= gBL;
            while (rB < 0) rB += gBL;
            while (rB >= gBL) rB -= gBL;
            int iA = (int) rA; float fA = rA - iA;
            int jA = iA + 1; if (jA >= gBL) jA = 0;
            int iB = (int) rB; float fB = rB - iB;
            int jB = iB + 1; if (jB >= gBL) jB = 0;
            float sA = gBuf[iA] * (1f - fA) + gBuf[jA] * fA;
            float sB = gBuf[iB] * (1f - fB) + gBuf[jB] * fB;
            float corrected = sA * hLut[pA] + sB * hLut[pB];

            gp++; if (gp >= gs) gp = 0;

            // Crossfade between dry and corrected by voicing gate so that
            // unvoiced regions (sibilants, breath) pass through as-is.
            float wet = x * (1f - voiceG) + corrected * voiceG;
            output[i] = x * dryMix + wet * mixLocal;
        }

        analysisWrite = aw;
        grainWrite = gw;
        grainPhase = gp;
        samplesSinceAnalysis = ssa;
        currentRatio = currentR;
        targetRatio = targetR;
        voicingGate = voiceG;
        humanizeState = humState;
    }
}
