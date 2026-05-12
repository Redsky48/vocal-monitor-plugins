package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// De-wind — wind buffeting against a microphone produces sustained
// sub-100 Hz energy that's distinct from a plosive's brief thump.
// We measure the long-term LF energy with a sidechain LP at 100 Hz +
// slow envelope follower; whenever the LF energy outweighs the rest of
// the signal we ramp up an aggressive HPF cutoff on the main path. Like
// de-plosive but with longer time constants tuned for sustained noise
// rather than impulsive pops.

public final class DeWind implements VocalMonitorNativePlugin {

    private final float[] scA1 = new float[2], scB1 = new float[2];
    private final float[] scA2 = new float[2], scB2 = new float[2];
    private final float[] hpA1 = new float[2], hpB1 = new float[2];
    private final float[] hpA2 = new float[2], hpB2 = new float[2];
    private float lfEnv = 0f, fullEnv = 1e-4f;
    private float gateGain = 0f;
    private int sampleRate = 44100;

    private float sensitivity = 0.5f;
    private float maxCutoff = 200f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) {
            scA1[i] = scB1[i] = scA2[i] = scB2[i] = 0f;
            hpA1[i] = hpB1[i] = hpA2[i] = hpB2[i] = 0f;
        }
        lfEnv = 0f; fullEnv = 1e-4f; gateGain = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "sensitivity", "maxCutoff" }; }
    @Override public float parameterMin(String n) {
        return "maxCutoff".equals(n) ? 60f : 0f;
    }
    @Override public float parameterMax(String n) {
        return "maxCutoff".equals(n) ? 400f : 1f;
    }
    @Override public float parameterDefault(String n) {
        return "maxCutoff".equals(n) ? 200f : 0.5f;
    }
    @Override public String parameterLabel(String n) {
        return "sensitivity".equals(n) ? "Sensitivity" : "Max Cutoff (Hz)";
    }
    @Override public void setParameter(String n, float v) {
        if ("sensitivity".equals(n)) sensitivity = v;
        else if ("maxCutoff".equals(n)) maxCutoff = v;
    }

    private static float[] bqLP(float fc, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) ((1.0 - c) * 0.5 / a0), (float) ((1.0 - c) / a0), (float) ((1.0 - c) * 0.5 / a0),
            (float) (-2.0 * c / a0), (float) ((1.0 - alpha) / a0)
        };
    }
    private static float[] bqHP(float fc, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) ((1.0 + c) * 0.5 / a0), (float) (-(1.0 + c) / a0), (float) ((1.0 + c) * 0.5 / a0),
            (float) (-2.0 * c / a0), (float) ((1.0 - alpha) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        final float[] lp = bqLP(100f, sampleRate);
        final float[] hp = bqHP(maxCutoff, sampleRate);
        // Slow time constants — wind is sustained, not impulsive.
        final float envCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.080));
        final float fullCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.300));
        final float openCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.150));
        final float closeCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.500));
        // sensitivity 0..1 → LF/full ratio threshold 3..1.2
        final float ratioThresh = 3f - sensitivity * 1.8f;
        float lE = lfEnv, fE = fullEnv, gg = gateGain;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float sc1 = lp[0]*x + lp[1]*scA1[0] + lp[2]*scA1[1] - lp[3]*scB1[0] - lp[4]*scB1[1];
            scA1[1] = scA1[0]; scA1[0] = x;
            scB1[1] = scB1[0]; scB1[0] = sc1;
            float sc = lp[0]*sc1 + lp[1]*scA2[0] + lp[2]*scA2[1] - lp[3]*scB2[0] - lp[4]*scB2[1];
            scA2[1] = scA2[0]; scA2[0] = sc1;
            scB2[1] = scB2[0]; scB2[0] = sc;
            float rectLf = sc < 0 ? -sc : sc;
            float rectFull = x < 0 ? -x : x;
            lE = lE + envCoef * (rectLf - lE);
            fE = fE + fullCoef * (rectFull - fE);
            if (fE < 1e-6f) fE = 1e-6f;

            float target = lE > fE * ratioThresh * 0.3f ? 1f : 0f;
            float coef = target > gg ? openCoef : closeCoef;
            gg = gg + coef * (target - gg);

            float h1 = hp[0]*x + hp[1]*hpA1[0] + hp[2]*hpA1[1] - hp[3]*hpB1[0] - hp[4]*hpB1[1];
            hpA1[1] = hpA1[0]; hpA1[0] = x;
            hpB1[1] = hpB1[0]; hpB1[0] = h1;
            float h2 = hp[0]*h1 + hp[1]*hpA2[0] + hp[2]*hpA2[1] - hp[3]*hpB2[0] - hp[4]*hpB2[1];
            hpA2[1] = hpA2[0]; hpA2[0] = h1;
            hpB2[1] = hpB2[0]; hpB2[0] = h2;

            output[i] = x * (1f - gg) + h2 * gg;
        }
        lfEnv = lE; fullEnv = fE; gateGain = gg;
    }
}
