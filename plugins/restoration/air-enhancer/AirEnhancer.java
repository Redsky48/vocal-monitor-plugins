package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Air Enhancer — dynamic high shelf that boosts 8–15 kHz "air" content
// only when the vocal is present. Static high-shelf EQ also boosts the
// noise floor between phrases, but a dynamic shelf gated by an envelope
// detector applies the lift only when there's signal worth lifting.
// iZotope Vocal Enhancer's "presence" band works the same way.

public final class AirEnhancer implements VocalMonitorNativePlugin {

    private final float[] hsA = new float[2], hsB = new float[2];
    private float env = 0f;
    private float currentBoost = 0f;
    private int sampleRate = 44100;

    private float frequency = 12000f;
    private float maxBoost = 6f;       // dB
    private float threshold = -30f;    // dB envelope above which we start lifting
    private float mix = 1f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) { hsA[i] = hsB[i] = 0f; }
        env = 0f; currentBoost = 0f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "frequency", "maxBoost", "threshold", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "frequency": return 4000f;
            case "maxBoost":  return 0f;
            case "threshold": return -60f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "frequency": return 18000f;
            case "maxBoost":  return 12f;
            case "threshold": return 0f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "frequency": return 12000f;
            case "maxBoost":  return 6f;
            case "threshold": return -30f;
            case "mix":       return 1f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "frequency": return "Freq (Hz)";
            case "maxBoost":  return "Max Boost (dB)";
            case "threshold": return "Threshold (dB)";
            case "mix":       return "Mix";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "frequency": frequency = v; break;
            case "maxBoost":  maxBoost = v; break;
            case "threshold": threshold = v; break;
            case "mix":       mix = v; break;
        }
    }

    // RBJ high-shelf coefs.
    private static float[] highShelf(float fc, float gainDb, int sr) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double S = 1.0;
        double alpha = s / 2.0 * Math.sqrt((A + 1.0/A) * (1.0/S - 1.0) + 2.0);
        double beta = 2.0 * Math.sqrt(A) * alpha;
        double a0 = (A + 1.0) - (A - 1.0) * c + beta;
        return new float[] {
            (float) (A * ((A + 1.0) + (A - 1.0) * c + beta) / a0),
            (float) (-2.0 * A * ((A - 1.0) + (A + 1.0) * c) / a0),
            (float) (A * ((A + 1.0) + (A - 1.0) * c - beta) / a0),
            (float) (2.0 * ((A - 1.0) - (A + 1.0) * c) / a0),
            (float) (((A + 1.0) - (A - 1.0) * c - beta) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        // Envelope: 30 ms attack-ish.
        final float envCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.030));
        final float boostCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.100));
        final float threshLin = (float) Math.pow(10.0, threshold / 20.0);
        final float maxBoostLocal = maxBoost;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        // We re-derive the high-shelf coefficients once per block at a
        // representative gain — then crossfade with dry by currentBoost
        // / maxBoost to approximate dynamic gain. Recomputing per sample
        // is theoretically purer but a per-block update is inaudible
        // at vocal envelope time-scales.
        final float[] hs = highShelf(frequency, maxBoostLocal, sampleRate);
        float e = env, cb = currentBoost;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float rect = x < 0 ? -x : x;
            e = e + envCoef * (rect - e);
            // Map env above threshold → 0..1 boost amount.
            float boostAmount = 0f;
            if (e > threshLin) {
                boostAmount = (e - threshLin) / (threshLin * 4f);
                if (boostAmount > 1f) boostAmount = 1f;
            }
            cb = cb + boostCoef * (boostAmount - cb);

            float shelfed = hs[0]*x + hs[1]*hsA[0] + hs[2]*hsA[1] - hs[3]*hsB[0] - hs[4]*hsB[1];
            hsA[1] = hsA[0]; hsA[0] = x;
            hsB[1] = hsB[0]; hsB[0] = shelfed;

            float wet = x + (shelfed - x) * cb;  // crossfade shelf in by cb
            output[i] = x * dry + wet * mixLocal;
        }
        env = e; currentBoost = cb;
    }
}
