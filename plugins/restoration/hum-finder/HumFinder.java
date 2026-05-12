package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Hum Finder — automatically detects whether the input has 50 Hz or
// 60 Hz mains contamination and notches the dominant one + its first
// five harmonics. We do this by running two narrow bandpass detectors
// (one at 50 Hz, one at 60 Hz), comparing their long-term envelopes,
// and selecting the winner. The chosen mains frequency drives a 6-notch
// chain identical to the manual De-hum's, but you don't have to know
// which power grid the recording came from. Convenient for fielded
// recordings, downloaded interviews, etc.

public final class HumFinder implements VocalMonitorNativePlugin {

    private static final int NUM_HARMONICS = 6;

    // Two BP detector biquads.
    private final float[] bp50A = new float[2], bp50B = new float[2];
    private final float[] bp60A = new float[2], bp60B = new float[2];
    private float env50 = 0f, env60 = 0f;
    // Notch chain (6 biquads, same as de-hum).
    private final float[][] notchA = new float[NUM_HARMONICS][2];
    private final float[][] notchB = new float[NUM_HARMONICS][2];
    private float currentMains = 50f;     // smoothed
    private int sampleRate = 44100;

    private float depth = 1f;
    private float strength = 0.5f;        // bias toward selected detector

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) {
            bp50A[i] = bp50B[i] = bp60A[i] = bp60B[i] = 0f;
        }
        for (int h = 0; h < NUM_HARMONICS; h++) {
            notchA[h][0] = notchA[h][1] = 0f;
            notchB[h][0] = notchB[h][1] = 0f;
        }
        env50 = env60 = 0f;
        currentMains = 50f;
    }

    @Override public String[] parameterNames() { return new String[] { "depth", "strength" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return "depth".equals(n) ? 1f : 0.5f; }
    @Override public String parameterLabel(String n) {
        return "depth".equals(n) ? "Depth" : "Bias";
    }
    @Override public void setParameter(String n, float v) {
        if ("depth".equals(n)) depth = v;
        else if ("strength".equals(n)) strength = v;
    }

    private static float[] bqBP(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / (2.0 * q);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) (alpha / a0), 0f, (float) (-alpha / a0),
            (float) (-2.0 * c / a0), (float) ((1.0 - alpha) / a0)
        };
    }
    private static float[] bqNotch(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / (2.0 * q);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) (1.0 / a0), (float) (-2.0 * c / a0), (float) (1.0 / a0),
            (float) (-2.0 * c / a0), (float) ((1.0 - alpha) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        final float[] bp50 = bqBP(50f, 30f, sampleRate);
        final float[] bp60 = bqBP(60f, 30f, sampleRate);
        final float envCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.150));
        final float mainsCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.500));
        // Pre-compute notch coefs at current mains; we re-derive each
        // block so smooth tracking from 50 → 60 is reflected in the filter
        // bank without zipper noise.
        final float[][] coefs = new float[NUM_HARMONICS][];
        for (int h = 0; h < NUM_HARMONICS; h++) {
            float fc = currentMains * (h + 1);
            if (fc >= sampleRate * 0.45f) { coefs[h] = null; continue; }
            coefs[h] = bqNotch(fc, 40f, sampleRate);
        }
        final float depthLocal = depth;
        final float dry = 1f - depthLocal;
        final float bias = strength;
        float e50 = env50, e60 = env60, mains = currentMains;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float s50 = bp50[0]*x + bp50[1]*bp50A[0] + bp50[2]*bp50A[1] - bp50[3]*bp50B[0] - bp50[4]*bp50B[1];
            bp50A[1] = bp50A[0]; bp50A[0] = x;
            bp50B[1] = bp50B[0]; bp50B[0] = s50;
            float s60 = bp60[0]*x + bp60[1]*bp60A[0] + bp60[2]*bp60A[1] - bp60[3]*bp60B[0] - bp60[4]*bp60B[1];
            bp60A[1] = bp60A[0]; bp60A[0] = x;
            bp60B[1] = bp60B[0]; bp60B[0] = s60;
            float r50 = s50 < 0 ? -s50 : s50;
            float r60 = s60 < 0 ? -s60 : s60;
            e50 = e50 + envCoef * (r50 - e50);
            e60 = e60 + envCoef * (r60 - e60);

            // Choose mains: env-weighted blend, bias controls how
            // decisive the pick is.
            float target = e60 > e50 * (1f + bias) ? 60f
                         : e50 > e60 * (1f + bias) ? 50f
                         : mains;
            mains = mains + mainsCoef * (target - mains);

            float v = x;
            for (int h = 0; h < NUM_HARMONICS; h++) {
                float[] cf = coefs[h];
                if (cf == null) continue;
                float y = cf[0]*v + cf[1]*notchA[h][0] + cf[2]*notchA[h][1] - cf[3]*notchB[h][0] - cf[4]*notchB[h][1];
                notchA[h][1] = notchA[h][0]; notchA[h][0] = v;
                notchB[h][1] = notchB[h][0]; notchB[h][0] = y;
                v = y;
            }
            output[i] = x * dry + v * depthLocal;
        }
        env50 = e50; env60 = e60; currentMains = mains;
    }
}
