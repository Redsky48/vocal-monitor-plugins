package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// De-mouth — catches lip smacks, tongue clicks and saliva-pops between
// words. These live in a different physical place from broadband clicks:
// they're short HF transients in the 1.5–4 kHz region that fire ONLY
// when the vocal isn't otherwise busy. We exploit that by running a
// bandpass detector AND a wideband envelope, and only ducking when the
// bandpass shows a fast spike while the wideband sits below the voice
// threshold (i.e. the singer isn't actually singing).
//
// 64-sample lookahead gives us time to envelope-shape the duck so we
// don't chop off the leading edge of the next sung syllable.

public final class DeMouth implements VocalMonitorNativePlugin {

    private static final int LOOKAHEAD = 64;

    private final float[] buf = new float[LOOKAHEAD];
    private int bufPos = 0;
    private final float[] bpA = new float[2], bpB = new float[2];
    private float bpEnvFast = 0f, bpEnvSlow = 1e-4f;
    private float wideEnv = 0f;
    private float duckGain = 1f;
    private int sampleRate = 44100;

    private float sensitivity = 0.55f;
    private float reduction = 14f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < LOOKAHEAD; i++) buf[i] = 0f;
        for (int i = 0; i < 2; i++) { bpA[i] = bpB[i] = 0f; }
        bufPos = 0;
        bpEnvFast = 0f; bpEnvSlow = 1e-4f;
        wideEnv = 0f;
        duckGain = 1f;
    }

    @Override public String[] parameterNames() { return new String[] { "sensitivity", "reduction" }; }
    @Override public float parameterMin(String n) { return "reduction".equals(n) ? 0f : 0f; }
    @Override public float parameterMax(String n) { return "reduction".equals(n) ? 24f : 1f; }
    @Override public float parameterDefault(String n) {
        return "reduction".equals(n) ? 14f : 0.55f;
    }
    @Override public String parameterLabel(String n) {
        return "sensitivity".equals(n) ? "Sensitivity" : "Reduction (dB)";
    }
    @Override public void setParameter(String n, float v) {
        if ("sensitivity".equals(n)) sensitivity = v;
        else if ("reduction".equals(n)) reduction = v;
    }

    // BPF biquad: peak at fc, Q.
    private static float[] bqBP(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / (2.0 * q);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) (alpha / a0),
            0f,
            (float) (-alpha / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        // Centre BPF at 2.5 kHz, Q=1.4 (about an octave wide).
        final float[] bp = bqBP(2500f, 1.4f, sampleRate);
        final float fastCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.002));
        final float slowCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.200));
        final float wideCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.030));
        final float openCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));
        final float closeCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.080));
        final float threshMul = 5f - sensitivity * 3f;       // 2..5 — BPF over-shoot ratio
        final float voiceThresh = 0.04f * (2f - sensitivity); // wideband ceiling = "vocal is quiet enough"
        final float minGain = (float) Math.pow(10.0, -reduction / 20.0);
        final float[] bRing = buf;
        int bp_pos = bufPos;
        float bF = bpEnvFast, bS = bpEnvSlow, wE = wideEnv, g = duckGain;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float bpOut = bp[0]*x + bp[1]*bpA[0] + bp[2]*bpA[1] - bp[3]*bpB[0] - bp[4]*bpB[1];
            bpA[1] = bpA[0]; bpA[0] = x;
            bpB[1] = bpB[0]; bpB[0] = bpOut;
            float rect = bpOut < 0 ? -bpOut : bpOut;
            bF = bF + fastCoef * (rect - bF);
            if (rect < bS) bS = bS + slowCoef * (rect - bS);
            else bS = bS + slowCoef * 0.05f * (rect - bS);
            if (bS < 1e-6f) bS = 1e-6f;

            float wide = x < 0 ? -x : x;
            wE = wE + wideCoef * (wide - wE);

            boolean spike = bF > bS * threshMul;
            boolean quiet = wE < voiceThresh;
            float target = (spike && quiet) ? minGain : 1f;
            float coef = target < g ? openCoef : closeCoef;
            g = g + coef * (target - g);

            // Lookahead: emit oldest sample (with current g applied).
            float emit = bRing[bp_pos];
            bRing[bp_pos] = x * g;
            bp_pos++; if (bp_pos >= LOOKAHEAD) bp_pos = 0;
            output[i] = emit;
        }
        bufPos = bp_pos;
        bpEnvFast = bF; bpEnvSlow = bS;
        wideEnv = wE; duckGain = g;
    }
}
