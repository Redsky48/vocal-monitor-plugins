package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Vowel Filter (native port) — three parallel bandpass biquads tuned to
// F1/F2/F3 formant frequencies, morphed between A·E·I·O·U with optional
// LFO sweep. Talk-wah, robot-mouth, choir flavour on a dry voice.
public final class VowelFilter implements VocalMonitorNativePlugin {
    private final float[][] s = { new float[4], new float[4], new float[4] };
    private float phase = 0f;
    private int sampleRate = 44100;
    private float vowel = 0f, rate = 0f, depth = 0.5f, q = 8f, mix = 1f;

    private static final float[] VOWEL_F1 = { 700f, 500f, 270f, 450f, 325f };
    private static final float[] VOWEL_F2 = { 1220f, 2300f, 2300f, 800f, 700f };
    private static final float[] VOWEL_F3 = { 2600f, 3000f, 3000f, 2830f, 2530f };

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int b = 0; b < 3; b++)
            for (int k = 0; k < 4; k++) s[b][k] = 0f;
        phase = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "vowel", "rate", "depth", "q", "mix" }; }
    @Override public float parameterMin(String n) {
        if ("q".equals(n)) return 2f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "vowel": return 4f;
            case "rate":  return 8f;
            case "q":     return 30f;
            default:      return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "vowel": return 0f;
            case "rate":  return 0f;
            case "depth": return 0.5f;
            case "q":     return 8f;
            case "mix":   return 1f;
            default:      return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "vowel": return "Vowel";
            case "rate":  return "Rate (Hz)";
            case "depth": return "Depth";
            case "q":     return "Q";
            case "mix":   return "Mix";
            default:      return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "vowel": vowel = v; break;
            case "rate":  rate = v; break;
            case "depth": depth = v; break;
            case "q":     q = v; break;
            case "mix":   mix = v; break;
        }
    }

    // Returns [b0, b2, a1, a2] (b1 is 0 for BP, b2 = -b0).
    private static void bpCoefs(float fc, float q, int sr, float[] out) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s2 = Math.sin(w);
        double alpha = s2 / (2.0 * q);
        double a0 = 1.0 + alpha;
        out[0] = (float) (alpha / a0);   // b0
        out[1] = (float) (-2.0 * c / a0); // a1
        out[2] = (float) ((1.0 - alpha) / a0); // a2
    }

    @Override
    public void process(float[] input, float[] output) {
        final float phaseInc = rate > 0f ? (float) (2.0 * Math.PI * rate / sampleRate) : 0f;
        final float twoPi = (float) (2.0 * Math.PI);
        float pos = vowel;
        if (rate > 0f) pos = vowel + depth * 2f * (float) Math.sin(phase);
        if (pos < 0f) pos = 0f;
        if (pos > 4f) pos = 4f;
        int vi = (int) pos;
        if (vi > 3) vi = 3;
        float frac = pos - vi;
        float f1 = VOWEL_F1[vi] * (1f - frac) + VOWEL_F1[vi + 1] * frac;
        float f2 = VOWEL_F2[vi] * (1f - frac) + VOWEL_F2[vi + 1] * frac;
        float f3 = VOWEL_F3[vi] * (1f - frac) + VOWEL_F3[vi + 1] * frac;
        float[] c1 = new float[3], c2 = new float[3], c3 = new float[3];
        bpCoefs(f1, q, sampleRate, c1);
        bpCoefs(f2, q, sampleRate, c2);
        bpCoefs(f3, q, sampleRate, c3);
        final float g1 = 1f, g2 = 0.75f, g3 = 0.5f;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float[] s1 = s[0], s2_ = s[1], s3 = s[2];
        float ph = phase;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            // BP biquad: y = b0*x + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
            // For our BP: b1 = 0, b2 = -b0. State stores x[n-1], x[n-2], y[n-1], y[n-2].
            float y1 = c1[0] * x + (-c1[0]) * s1[1] - c1[1] * s1[2] - c1[2] * s1[3];
            s1[1] = s1[0]; s1[0] = x; s1[3] = s1[2]; s1[2] = y1;
            float y2 = c2[0] * x + (-c2[0]) * s2_[1] - c2[1] * s2_[2] - c2[2] * s2_[3];
            s2_[1] = s2_[0]; s2_[0] = x; s2_[3] = s2_[2]; s2_[2] = y2;
            float y3 = c3[0] * x + (-c3[0]) * s3[1] - c3[1] * s3[2] - c3[2] * s3[3];
            s3[1] = s3[0]; s3[0] = x; s3[3] = s3[2]; s3[2] = y3;
            float wet = y1 * g1 + y2 * g2 + y3 * g3;
            output[i] = x * dry + wet * mixLocal;
            if (phaseInc > 0f) {
                ph += phaseInc;
                if (ph > twoPi) ph -= twoPi;
            }
        }
        phase = ph;
    }
}
