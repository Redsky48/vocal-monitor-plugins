package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Exciter (native port) — Aphex-style HP → asymmetric soft-clip →
// harmonic add. Re-airs dull vocals and cassette transfers.
public final class Exciter implements VocalMonitorNativePlugin {
    private final float[] hpA = new float[2], hpB = new float[2];
    private int sampleRate = 44100;
    private float frequency = 3500f, drive = 0.6f, amount = 0.4f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) { hpA[i] = 0f; hpB[i] = 0f; }
    }

    @Override public String[] parameterNames() { return new String[] { "frequency", "drive", "amount" }; }
    @Override public float parameterMin(String n) {
        return "frequency".equals(n) ? 1000f : 0f;
    }
    @Override public float parameterMax(String n) {
        return "frequency".equals(n) ? 12000f : 1f;
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "frequency": return 3500f;
            case "drive":     return 0.6f;
            case "amount":    return 0.4f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "frequency": return "Freq (Hz)";
            case "drive":     return "Drive";
            case "amount":    return "Amount";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "frequency": frequency = v; break;
            case "drive":     drive = v; break;
            case "amount":    amount = v; break;
        }
    }

    private static float[] bqHP(float fc, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) ((1.0 + c) * 0.5 / a0),
            (float) (-(1.0 + c) / a0),
            (float) ((1.0 + c) * 0.5 / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        final float[] hp = bqHP(frequency, sampleRate);
        final float k = 1f + drive * 14f;
        final float normFactor = 1f / (float) Math.tanh(k);
        final float kNeg = k * 0.6f;
        final float amt = amount;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float hp1 = hp[0]*x + hp[1]*hpA[0] + hp[2]*hpA[1] - hp[3]*hpB[0] - hp[4]*hpB[1];
            hpA[1] = hpA[0]; hpA[0] = x;
            hpB[1] = hpB[0]; hpB[0] = hp1;
            float d;
            if (hp1 >= 0f) d = (float) Math.tanh(hp1 * k) * normFactor;
            else d = (float) Math.tanh(hp1 * kNeg) * normFactor;
            float excited = d - hp1;
            output[i] = x + excited * amt;
        }
    }
}
