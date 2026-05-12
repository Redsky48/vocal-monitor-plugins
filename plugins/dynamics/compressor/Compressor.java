package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Compressor (native port) — feedforward peak compressor with split
// attack/release detector, soft-knee gain computer in the dB domain,
// and makeup gain.
public final class Compressor implements VocalMonitorNativePlugin {
    private float env = 0f;
    private float gain = 1f;
    private int sampleRate = 44100;
    private float threshold = -18f, ratio = 4f, attack = 8f, release = 120f,
                  knee = 6f, makeup = 0f;

    @Override
    public void init(int sr) { this.sampleRate = sr; env = 0f; gain = 1f; }

    @Override public String[] parameterNames() {
        return new String[] { "threshold", "ratio", "attack", "release", "knee", "makeup" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "threshold": return -60f;
            case "ratio":     return 1f;
            case "attack":    return 0.1f;
            case "release":   return 5f;
            case "knee":      return 0f;
            case "makeup":    return 0f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "threshold": return 0f;
            case "ratio":     return 20f;
            case "attack":    return 100f;
            case "release":   return 1000f;
            case "knee":      return 24f;
            case "makeup":    return 24f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "threshold": return -18f;
            case "ratio":     return 4f;
            case "attack":    return 8f;
            case "release":   return 120f;
            case "knee":      return 6f;
            case "makeup":    return 0f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "threshold": return "Thresh (dB)";
            case "ratio":     return "Ratio";
            case "attack":    return "Att (ms)";
            case "release":   return "Rel (ms)";
            case "knee":      return "Knee (dB)";
            case "makeup":    return "Makeup (dB)";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "threshold": threshold = v; break;
            case "ratio":     ratio = v; break;
            case "attack":    attack = v; break;
            case "release":   release = v; break;
            case "knee":      knee = v; break;
            case "makeup":    makeup = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float thresh = threshold;
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * attack / 1000.0));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        final float makeupLin = (float) Math.pow(10.0, makeup / 20.0);
        final float halfKnee = knee * 0.5f;
        final float invRatio = 1f / ratio;
        final float kneeLocal = knee;
        final float ln10 = (float) Math.log(10);
        float e = env;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float rect = x < 0 ? -x : x;
            float coef = rect > e ? attCoef : relCoef;
            e = e + coef * (rect - e);

            float envDb = e > 1e-6f ? (float) (20.0 * Math.log(e) / ln10) : -120f;

            float gr = 0f;
            float diff = envDb - thresh;
            if (diff > -halfKnee) {
                if (diff < halfKnee && kneeLocal > 0f) {
                    float t = (diff + halfKnee) / kneeLocal;
                    gr = (1f - invRatio) * t * t * halfKnee;
                } else {
                    gr = (envDb - thresh) * (1f - invRatio);
                }
            }
            float targetGain = (float) Math.pow(10.0, -gr / 20.0);
            output[i] = x * targetGain * makeupLin;
        }
        env = e;
    }
}
