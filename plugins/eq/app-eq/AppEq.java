package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

/**
 * Bit-exact port of the app's built-in {@code BiquadEqualizer.kt} —
 * 10-band constant-Q peaking EQ at ISO octave centres.  Every
 * coefficient + state variable maps directly to the Kotlin original
 * so the plugin and the host's save-time chain produce identical
 * output sample-for-sample.
 *
 * Parameters: ten {@code bandN} entries (0..9) carrying gain in dB
 * for each ISO band.  Per-band absolute gain &lt; 0.05 dB short-
 * circuits to a pass-through biquad (matches the Kotlin "skip
 * near-flat band" optimisation).
 */
public final class AppEq implements VocalMonitorNativePlugin {

    private static final int   BAND_COUNT = 10;
    private static final int[] BAND_FREQUENCIES =
        new int[] { 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };
    private static final String[] BAND_LABELS =
        new String[] { "31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k" };
    private static final double Q = 1.4;

    private int sampleRate = 44_100;

    private final float[] gainsDb = new float[BAND_COUNT];

    // Biquad coefficients per band.
    private final double[] b0 = new double[BAND_COUNT];
    private final double[] b1 = new double[BAND_COUNT];
    private final double[] b2 = new double[BAND_COUNT];
    private final double[] a1 = new double[BAND_COUNT];
    private final double[] a2 = new double[BAND_COUNT];
    // Direct-form II transposed state.
    private final double[] z1 = new double[BAND_COUNT];
    private final double[] z2 = new double[BAND_COUNT];

    @Override
    public void init(int sr) {
        this.sampleRate = Math.max(8_000, sr);
        for (int i = 0; i < BAND_COUNT; i++) { gainsDb[i] = 0f; z1[i] = 0; z2[i] = 0; }
        recompute();
    }

    /** Re-derive coefficients from current {@link #gainsDb}.  Mirrors
     *  the Kotlin source — RBJ "Audio EQ Cookbook" peaking-EQ form. */
    private void recompute() {
        for (int i = 0; i < BAND_COUNT; i++) {
            double freq = BAND_FREQUENCIES[i];
            double gain = gainsDb[i];
            // Near-flat band optimisation: short-circuit to pass-through.
            if (Math.abs(gain) < 0.05) {
                b0[i] = 1.0; b1[i] = 0.0; b2[i] = 0.0;
                a1[i] = 0.0; a2[i] = 0.0;
                continue;
            }
            double a = Math.pow(10.0, gain / 40.0);
            double w0 = 2.0 * Math.PI * freq / sampleRate;
            double cosW0 = Math.cos(w0);
            double sinW0 = Math.sin(w0);
            double alpha = sinW0 / (2.0 * Q);
            double a0c = 1.0 + alpha / a;
            b0[i] = (1.0 + alpha * a) / a0c;
            b1[i] = (-2.0 * cosW0) / a0c;
            b2[i] = (1.0 - alpha * a) / a0c;
            a1[i] = (-2.0 * cosW0) / a0c;
            a2[i] = (1.0 - alpha / a) / a0c;
        }
    }

    @Override public String[] parameterNames() {
        return new String[] {
            "band0", "band1", "band2", "band3", "band4",
            "band5", "band6", "band7", "band8", "band9",
        };
    }

    @Override public float parameterMin(String n)     { return -24f; }
    @Override public float parameterMax(String n)     { return  24f; }
    @Override public float parameterDefault(String n) { return   0f; }
    @Override public String parameterLabel(String n) {
        if (n != null && n.length() == 5 && n.startsWith("band")) {
            char c = n.charAt(4);
            if (c >= '0' && c <= '9') return BAND_LABELS[c - '0'] + " Hz";
        }
        return n;
    }

    @Override public void setParameter(String n, float v) {
        if (n == null || n.length() != 5 || !n.startsWith("band")) return;
        char c = n.charAt(4);
        if (c < '0' || c > '9') return;
        int idx = c - '0';
        if (gainsDb[idx] == v) return;
        gainsDb[idx] = v;
        recompute();
        // Match the Kotlin setter: state reset on gain change so an
        // abrupt slider move doesn't ring the old coefficients.
        for (int i = 0; i < BAND_COUNT; i++) { z1[i] = 0; z2[i] = 0; }
    }

    @Override
    public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int s = 0; s < n; s++) {
            double x = input[s];
            // 10 biquads in series, direct form II transposed.
            for (int i = 0; i < BAND_COUNT; i++) {
                double y  = b0[i] * x + z1[i];
                z1[i]     = b1[i] * x - a1[i] * y + z2[i];
                z2[i]     = b2[i] * x - a2[i] * y;
                x = y;
            }
            // Match the Kotlin processor's soft clip: clamp to [-1, 1]
            // so a band pushed > 0 dBFS doesn't hard-distort.
            if (x >  1.0) x =  1.0;
            if (x < -1.0) x = -1.0;
            output[s] = (float) x;
        }
    }
}
