package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Upward Compressor — the opposite of a downward compressor: instead
// of pulling loud signals DOWN, this pushes quiet signals UP toward a
// target level. Useful for vocal clarity on whispered passages without
// touching the louder sung sections, or for dialogue intelligibility
// in podcast mixes. Below the threshold the signal is boosted up to a
// configurable ceiling; above threshold it passes through unchanged.

public final class UpwardCompressor implements VocalMonitorNativePlugin {

    private float env = 0f;
    private float gain = 1f;
    private int sampleRate = 44100;

    private float threshold = -30f;
    private float ratio = 3f;
    private float maxBoost = 12f;
    private float attack = 30f;
    private float release = 200f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        env = 0f; gain = 1f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "threshold", "ratio", "maxBoost", "attack", "release" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "threshold": return -60f;
            case "ratio":     return 1f;
            case "maxBoost":  return 0f;
            case "attack":    return 1f;
            case "release":   return 10f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "threshold": return -3f;
            case "ratio":     return 10f;
            case "maxBoost":  return 24f;
            case "attack":    return 200f;
            case "release":   return 1000f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "threshold": return -30f;
            case "ratio":     return 3f;
            case "maxBoost":  return 12f;
            case "attack":    return 30f;
            case "release":   return 200f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "threshold": return "Thresh (dB)";
            case "ratio":     return "Ratio";
            case "maxBoost":  return "Max Boost (dB)";
            case "attack":    return "Att (ms)";
            case "release":   return "Rel (ms)";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "threshold": threshold = v; break;
            case "ratio":     ratio = v; break;
            case "maxBoost":  maxBoost = v; break;
            case "attack":    attack = v; break;
            case "release":   release = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float thresh = threshold;
        final float invRatio = 1f / ratio;
        final float maxBoostLin = (float) Math.pow(10.0, maxBoost / 20.0);
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * attack / 1000.0));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        // Floor below which we don't boost (gate against pure noise).
        final float gateFloor = (float) Math.pow(10.0, (threshold - 30.0) / 20.0);
        final float ln10 = (float) Math.log(10);
        float e = env, g = gain;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float rect = x < 0 ? -x : x;
            float coef = rect > e ? attCoef : relCoef;
            e = e + coef * (rect - e);
            float envDb = e > 1e-6f ? 20f * (float) Math.log(e) / ln10 : -120f;
            // Below threshold: boost the signal up. The deeper below
            // thresh we are, the more boost is applied (up to maxBoost).
            // gain_dB = (thresh - envDb) * (1 - 1/ratio), clamped at maxBoost.
            float targetGain = 1f;
            if (e > gateFloor && envDb < thresh) {
                float boostDb = (thresh - envDb) * (1f - invRatio);
                targetGain = (float) Math.pow(10.0, boostDb / 20.0);
                if (targetGain > maxBoostLin) targetGain = maxBoostLin;
            }
            g = targetGain;
            output[i] = x * g;
        }
        env = e; gain = g;
    }
}
