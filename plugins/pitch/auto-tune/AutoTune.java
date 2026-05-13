package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Auto-Tune — production-grade vocal pitch correction. Three engines
// wired in series:
//
//   1. YIN pitch detector  — the published de Cheveigné/Kawahara
//      cumulative-mean-normalised-difference algorithm. Operates on a
//      sliding 1024-sample window, runs every 256 samples (~170 Hz
//      update rate at 44.1 kHz), with parabolic interpolation around
//      the CMND minimum for cents-accurate sub-sample period estimates.
//
//   2. Scale quantiser     — converts detected freq to MIDI cents,
//      finds the nearest pitch in the chosen scale (chromatic, major,
//      minor, harmonic minor, pentatonic), returns target ratio.
//
//   3. TD-PSOLA pitch shift — Time-Domain Pitch-Synchronous Overlap-Add.
//      Unlike fixed-size granular shifters (which average out the pitch
//      shift over long times because grain wraps undo it), PSOLA uses
//      grains exactly ONE PITCH PERIOD long. Adjacent pitch periods of
//      a voiced signal are nearly identical, so when a grain wraps and
//      "repeats" a previous source period, the wrap is inaudible. The
//      shift is preserved over long time and shows up correctly in
//      spectral analysis — exactly the technique Antares Auto-Tune and
//      Melodyne use for the monophonic case.
//
// Voicing gate: when YIN's CMND minimum exceeds the threshold the input
// is unpitched (consonant, breath, transient) — we fall back to dry so
// sibilants and breath don't become eerie pitched artefacts.
//
// Humanize: a slow random walk adds ±cents drift so corrected pitches
// don't sit dead-flat. At humanize=0 the correction is mathematically
// exact; at humanize=1 micro-variation matches a natural performance.

public final class AutoTune implements VocalMonitorNativePlugin {

    // --- Analysis (YIN) ---
    private static final int ANALYSIS_SIZE = 1024;
    private static final int LAG_MIN = 32;
    private static final int LAG_MAX = 512;
    private static final int ANALYSIS_INTERVAL = 256;
    private static final float YIN_THRESHOLD = 0.15f;

    private final float[] analysisBuf = new float[ANALYSIS_SIZE];
    private final float[] yinBuf = new float[ANALYSIS_SIZE];
    private final float[] yinD = new float[LAG_MAX + 2];
    private final float[] yinCMND = new float[LAG_MAX + 2];
    private int analysisWrite = 0;

    // --- Pitch state ---
    private float detectedFreq = 220f;
    private float detectedPeriod = 200f;
    private float voicingConfidence = 0f;
    private float targetRatio = 1f;
    private float currentRatio = 1f;
    private float voicingGate = 0f;
    private float humanizeState = 0f;

    // --- PSOLA grain state ---
    // Two voices, each emitting Hann-windowed source pitch periods at
    // staggered times. Each voice has its OWN current source-period
    // anchor and output-period-phase counter.
    private float[] srcBuf;
    private int srcBufLen;
    private int srcWrite = 0;
    // Floating-point source position trackers. Per output sample they
    // advance by the current `ratio`. When they get within one period
    // of the write head, they snap back by one source period (the only
    // place wraps happen — pitch-synchronous, so inaudible for voiced
    // material).
    private float voiceA_srcPos;
    private float voiceB_srcPos;
    // Output-phase counters: 0..outputPeriodLen, reset on wrap.
    private int voiceA_phase;
    private int voiceB_phase;
    // Output period length captured at start of each grain (so we don't
    // mid-period drift the envelope).
    private int voiceA_outLen;
    private int voiceB_outLen;
    // Source period captured at start of each grain (for fractional read).
    private float voiceA_srcLen;
    private float voiceB_srcLen;

    private int sampleRate = 44100;
    private int samplesSinceAnalysis = 0;
    private long noiseSeed = 1;

    // --- Parameters ---
    private float key = 0f;
    private float scaleMode = 0f;
    private float retune = 0.3f;
    private float humanize = 0.15f;
    private float strength = 1f;
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

        // ~1 sec of buffer holds plenty of past pitch periods for any
        // adult vocal range (50–1200 Hz).
        srcBufLen = sr;
        srcBuf = new float[srcBufLen];
        srcWrite = 0;
        // Stagger the two voices by ~half a default period so their
        // envelopes are complementary at startup.
        voiceA_srcPos = srcBufLen - 200f;   // 200 samples behind write
        voiceB_srcPos = srcBufLen - 300f;
        voiceA_phase = 0;
        voiceB_phase = 100;
        voiceA_outLen = 200;
        voiceB_outLen = 200;
        voiceA_srcLen = 200f;
        voiceB_srcLen = 200f;

        detectedFreq = 220f;
        detectedPeriod = sr / 220f;
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
    private float yinDetect() {
        final float[] aBuf = analysisBuf;
        final float[] yBuf = yinBuf;
        final int aw = analysisWrite;
        for (int k = 0; k < ANALYSIS_SIZE; k++) {
            int idx = aw + k;
            if (idx >= ANALYSIS_SIZE) idx -= ANALYSIS_SIZE;
            yBuf[k] = aBuf[idx];
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
        detectedPeriod = refined;
        return sampleRate / refined;
    }

    // ---------------------------------------------------------------
    // Scale quantiser
    // ---------------------------------------------------------------
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

    // Read source buffer at a fractional position (with linear interp + wrap).
    private float srcRead(float pos) {
        while (pos < 0) pos += srcBufLen;
        while (pos >= srcBufLen) pos -= srcBufLen;
        int i0 = (int) pos;
        float f = pos - i0;
        int i1 = i0 + 1; if (i1 >= srcBufLen) i1 = 0;
        return srcBuf[i0] * (1f - f) + srcBuf[i1] * f;
    }

    // Snap source pointer back if it's within `safeMargin` of the live
    // write head — that's the pitch-synchronous wrap.
    private float wrapIfClose(float srcPos, float srcLen) {
        // Compute "distance from srcPos forward to srcWrite" in the
        // circular sense, ≤ srcBufLen.
        float dist = srcWrite - srcPos;
        while (dist < 0) dist += srcBufLen;
        while (dist >= srcBufLen) dist -= srcBufLen;
        // We want the pointer to stay at least one period behind the
        // write head. If it has caught up to within `srcLen` samples,
        // jump back by exactly one source period.
        if (dist < srcLen) {
            srcPos -= srcLen;
            while (srcPos < 0) srcPos += srcBufLen;
        }
        return srcPos;
    }

    // ---------------------------------------------------------------
    // process()
    // ---------------------------------------------------------------
    @Override
    public void process(float[] input, float[] output) {
        final int n = input.length;
        final float[] aBuf = analysisBuf;
        final float[] sBuf = srcBuf;
        final int sBL = srcBufLen;

        final float retuneSec = 0.001f + retune * retune * 0.4f;
        final float ratioCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * retuneSec));
        final float voiceOpen = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));
        final float voiceClose = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.060));
        final float humanCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.250));
        final float humanMaxCents = humanize * 15f;
        final float strengthLocal = strength;
        final float mixLocal = mix;
        final float dryMix = 1f - mixLocal;

        int aw = analysisWrite;
        int sw = srcWrite;
        int ssa = samplesSinceAnalysis;
        float currentR = currentRatio;
        float targetR = targetRatio;
        float voiceG = voicingGate;
        float humState = humanizeState;
        float vAsrc = voiceA_srcPos, vBsrc = voiceB_srcPos;
        int vAphase = voiceA_phase, vBphase = voiceB_phase;
        int vAoutLen = voiceA_outLen, vBoutLen = voiceB_outLen;
        float vAsrcLen = voiceA_srcLen, vBsrcLen = voiceB_srcLen;

        for (int i = 0; i < n; i++) {
            float x = input[i];

            aBuf[aw] = x;
            aw++; if (aw >= ANALYSIS_SIZE) aw = 0;
            sBuf[sw] = x;
            sw++; if (sw >= sBL) sw = 0;

            ssa++;
            if (ssa >= ANALYSIS_INTERVAL) {
                ssa = 0;
                analysisWrite = aw;
                srcWrite = sw;
                float f0 = yinDetect();
                if (f0 > 50f && f0 < 2000f) {
                    detectedFreq = f0;
                    float tgt = snapToScale(f0);
                    targetR = tgt / f0;
                } else {
                    targetR = targetR + 0.5f * (1f - targetR);
                }
            }

            // Voicing gate. Below confidence threshold (sibilants/breath)
            // we crossfade back to dry to avoid eerie pitched artefacts.
            float voiceTarget = voicingConfidence > 0.3f ? 1f : 0f;
            float vCoef = voiceTarget > voiceG ? voiceOpen : voiceClose;
            voiceG = voiceG + vCoef * (voiceTarget - voiceG);

            // Humanize: smoothed random walk in cents → ratio multiplier.
            float humTargetCents = nextRandom() * humanMaxCents;
            humState = humState + humanCoef * (humTargetCents - humState);
            float humanFactor = (float) Math.pow(2.0, humState / 1200.0);

            // Smooth current correction ratio toward target, scaled by
            // strength (0=bypass, 1=full snap).
            float effectiveTarget = 1f + (targetR - 1f) * strengthLocal;
            currentR = currentR + ratioCoef * (effectiveTarget - currentR);
            float playRatio = currentR * humanFactor;
            if (playRatio < 0.5f) playRatio = 0.5f;
            if (playRatio > 2.0f) playRatio = 2.0f;

            // --- TD-PSOLA grain emission ---
            //
            // Each voice emits a Hann-windowed source pitch period over
            // its output-period worth of samples. At end of period it
            // resets and grabs the NEXT source pitch period (advance
            // source pointer by T_in).
            //
            // Source period T_in is the YIN-detected period; output
            // period T_out = T_in / ratio. The within-grain source-time
            // advance rate = T_in / T_out = ratio, so the listener hears
            // the shifted pitch. Across grain wraps, source pointer
            // advances by EXACTLY ONE PERIOD — invisible for periodic
            // material.
            float T_in = detectedPeriod;
            if (T_in < 16f) T_in = 16f;
            if (T_in > 1000f) T_in = 1000f;

            // --- Voice A ---
            if (vAphase >= vAoutLen) {
                vAphase = 0;
                vAsrcLen = T_in;
                vAoutLen = (int) Math.max(8, Math.round(T_in / playRatio));
                // Source pointer advances by one PERIOD per output period.
                // Net: source advances `T_in` over `T_out = T_in/ratio`
                // output samples → average source rate = ratio.
                vAsrc = vAsrc + vAsrcLen;
                while (vAsrc >= sBL) vAsrc -= sBL;
                while (vAsrc < 0) vAsrc += sBL;
                vAsrc = wrapIfClose(vAsrc, vAsrcLen);
            }
            // Read from source at fractional offset into the period.
            float fracA = (float) vAphase / (float) vAoutLen;
            float srcOffsetA = fracA * vAsrcLen;
            float sA = srcRead(vAsrc + srcOffsetA);
            float envA = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * fracA);
            vAphase++;

            // --- Voice B ---
            if (vBphase >= vBoutLen) {
                vBphase = 0;
                vBsrcLen = T_in;
                vBoutLen = (int) Math.max(8, Math.round(T_in / playRatio));
                vBsrc = vBsrc + vBsrcLen;
                while (vBsrc >= sBL) vBsrc -= sBL;
                while (vBsrc < 0) vBsrc += sBL;
                vBsrc = wrapIfClose(vBsrc, vBsrcLen);
            }
            float fracB = (float) vBphase / (float) vBoutLen;
            float srcOffsetB = fracB * vBsrcLen;
            float sB = srcRead(vBsrc + srcOffsetB);
            float envB = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * fracB);
            vBphase++;

            // Sum the two voices (Hann envelopes sum near 1 when staggered
            // by half a period).
            float corrected = sA * envA + sB * envB;

            float wet = x * (1f - voiceG) + corrected * voiceG;
            output[i] = x * dryMix + wet * mixLocal;
        }

        analysisWrite = aw;
        srcWrite = sw;
        samplesSinceAnalysis = ssa;
        currentRatio = currentR;
        targetRatio = targetR;
        voicingGate = voiceG;
        humanizeState = humState;
        voiceA_srcPos = vAsrc; voiceB_srcPos = vBsrc;
        voiceA_phase = vAphase; voiceB_phase = vBphase;
        voiceA_outLen = vAoutLen; voiceB_outLen = vBoutLen;
        voiceA_srcLen = vAsrcLen; voiceB_srcLen = vBsrcLen;
    }
}
