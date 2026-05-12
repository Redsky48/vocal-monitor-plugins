package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// De-hum — six narrow-band notch filters tuned to the mains frequency
// (50 Hz in EU, 60 Hz in US, switchable) and its first five harmonics.
// The unwanted hum is concentrated at exactly those discrete spots, so
// notching them surgically removes the buzz without touching the rest
// of the voice. Notch Q is generous (~40) so each notch is narrow
// enough to leave neighbouring frequencies essentially flat.

public final class DeHum implements VocalMonitorNativePlugin {

    private static final int NUM_HARMONICS = 6;

    private final float[][] sA = new float[NUM_HARMONICS][2];
    private final float[][] sB = new float[NUM_HARMONICS][2];
    private int sampleRate = 44100;
    private float frequency = 50f;
    private float depth = 1f;       // 0..1 → 0 dB to ∞ notch
    private float q = 40f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int h = 0; h < NUM_HARMONICS; h++) {
            sA[h][0] = sA[h][1] = 0f;
            sB[h][0] = sB[h][1] = 0f;
        }
    }

    @Override public String[] parameterNames() { return new String[] { "frequency", "depth", "q" }; }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "frequency": return 50f;
            case "depth":     return 0f;
            case "q":         return 10f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "frequency": return 60f;
            case "depth":     return 1f;
            case "q":         return 80f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "frequency": return 50f;
            case "depth":     return 1f;
            case "q":         return 40f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "frequency": return "Mains (Hz)";
            case "depth":     return "Depth";
            case "q":         return "Q";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "frequency": frequency = v; break;
            case "depth":     depth = v; break;
            case "q":         q = v; break;
        }
    }

    // Notch biquad — RBJ cookbook form. Depth is applied as a per-sample
    // crossfade between dry and notched chain in process(), so the
    // coefficients here represent the full notch.
    private static float[] bqNotch(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / (2.0 * q);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) (1.0 / a0),
            (float) (-2.0 * c / a0),
            (float) (1.0 / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        final float qLocal = q;
        // Pre-compute biquad coefs once per block.
        float[][] coefs = new float[NUM_HARMONICS][];
        for (int h = 0; h < NUM_HARMONICS; h++) {
            float fc = frequency * (h + 1);
            if (fc >= sampleRate * 0.45f) { coefs[h] = null; continue; }
            coefs[h] = bqNotch(fc, qLocal, sampleRate);
        }
        final float depthLocal = depth;
        final float dry = 1f - depthLocal;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float v = x;
            for (int h = 0; h < NUM_HARMONICS; h++) {
                float[] c = coefs[h];
                if (c == null) continue;
                float y = c[0]*v + c[1]*sA[h][0] + c[2]*sA[h][1] - c[3]*sB[h][0] - c[4]*sB[h][1];
                sA[h][1] = sA[h][0]; sA[h][0] = v;
                sB[h][1] = sB[h][0]; sB[h][0] = y;
                v = y;
            }
            // Depth: 0 = passthrough, 1 = full notched chain.
            output[i] = x * dry + v * depthLocal;
        }
    }
}
