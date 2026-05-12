package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Noise Gate (native port) — opens on rising-edge of openLin, closes
// only after env drops below closeLin AND hold has elapsed. Cleans up
// background hiss between phrases, kills mic bleed.
public final class NoiseGate implements VocalMonitorNativePlugin {
    private float env = 0f;
    private float gain = 0f;
    private int state = 0;
    private int holdSamples = 0;
    private int sampleRate = 44100;
    private float threshold = -45f, hysteresis = 6f, attack = 2f, hold = 30f, release = 80f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        env = 0f; gain = 0f; state = 0; holdSamples = 0;
    }

    @Override public String[] parameterNames() {
        return new String[] { "threshold", "hysteresis", "attack", "hold", "release" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "threshold":  return -80f;
            case "hysteresis": return 0f;
            case "attack":     return 0.1f;
            case "hold":       return 0f;
            case "release":    return 5f;
            default:           return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "threshold":  return 0f;
            case "hysteresis": return 24f;
            case "attack":     return 50f;
            case "hold":       return 500f;
            case "release":    return 1000f;
            default:           return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "threshold":  return -45f;
            case "hysteresis": return 6f;
            case "attack":     return 2f;
            case "hold":       return 30f;
            case "release":    return 80f;
            default:           return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "threshold":  return "Thresh (dB)";
            case "hysteresis": return "Hyst (dB)";
            case "attack":     return "Att (ms)";
            case "hold":       return "Hold (ms)";
            case "release":    return "Rel (ms)";
            default:           return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "threshold":  threshold = v; break;
            case "hysteresis": hysteresis = v; break;
            case "attack":     attack = v; break;
            case "hold":       hold = v; break;
            case "release":    release = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float openLin = (float) Math.pow(10.0, threshold / 20.0);
        final float closeLin = (float) Math.pow(10.0, (threshold - hysteresis) / 20.0);
        final float envCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * 0.005));
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * attack / 1000.0));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        final int holdN = (int) Math.floor(hold * sampleRate / 1000f);
        float e = env, g = gain;
        int st = state, hs = holdSamples;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float rect = x < 0 ? -x : x;
            e = e + envCoef * (rect - e);
            if (st == 0) {
                if (e > openLin) { st = 1; hs = holdN; }
            } else {
                if (e < closeLin) {
                    if (hs > 0) hs--;
                    else st = 0;
                } else {
                    hs = holdN;
                }
            }
            float target = st;
            float coef = target > g ? attCoef : relCoef;
            g = g + coef * (target - g);
            output[i] = x * g;
        }
        env = e; gain = g; state = st; holdSamples = hs;
    }
}
