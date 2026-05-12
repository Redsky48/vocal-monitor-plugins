package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Megaphone (native port) — steep band-pass + 2 kHz honk resonance +
// asymmetric soft-clip cone distortion + post-LP to tame fizz. Shouted
// vocals / radio-comm chatter.
public final class Megaphone implements VocalMonitorNativePlugin {
    private final float[] hpA = new float[2], hpB = new float[2];
    private final float[] pkA = new float[2], pkB = new float[2];
    private final float[] lpA = new float[2], lpB = new float[2];
    private int sampleRate = 44100;
    private float lowCut = 500f, highCut = 4500f, honk = 8f, crunch = 0.5f, mix = 1f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) {
            hpA[i] = hpB[i] = pkA[i] = pkB[i] = lpA[i] = lpB[i] = 0f;
        }
    }

    @Override public String[] parameterNames() {
        return new String[] { "lowCut", "highCut", "honk", "crunch", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "lowCut":  return 100f;
            case "highCut": return 1500f;
            default:        return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "lowCut":  return 1500f;
            case "highCut": return 8000f;
            case "honk":    return 18f;
            default:        return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "lowCut":  return 500f;
            case "highCut": return 4500f;
            case "honk":    return 8f;
            case "crunch":  return 0.5f;
            case "mix":     return 1f;
            default:        return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "lowCut":  return "Low Hz";
            case "highCut": return "High Hz";
            case "honk":    return "Honk";
            case "crunch":  return "Crunch";
            case "mix":     return "Mix";
            default:        return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "lowCut":  lowCut = v; break;
            case "highCut": highCut = v; break;
            case "honk":    honk = v; break;
            case "crunch":  crunch = v; break;
            case "mix":     mix = v; break;
        }
    }

    private static float[] bqLP(float fc, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) ((1.0 - c) * 0.5 / a0),
            (float) ((1.0 - c) / a0),
            (float) ((1.0 - c) * 0.5 / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha) / a0)
        };
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
    private static float[] bqPeak(float fc, float gainDb, float q, int sr) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / (2.0 * q);
        double a0 = 1.0 + alpha / A;
        return new float[] {
            (float) ((1.0 + alpha * A) / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha * A) / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha / A) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        final float hc = Math.max(lowCut + 200f, highCut);
        final float[] hp = bqHP(lowCut, sampleRate);
        final float[] pk = bqPeak(2000f, honk, 2.5f, sampleRate);
        final float[] lp = bqLP(hc, sampleRate);
        final float k = 1f + crunch * 12f;
        final float normalize = 1f / (float) Math.tanh(k);
        final float kNeg = k * 0.55f;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float y1 = hp[0]*x + hp[1]*hpA[0] + hp[2]*hpA[1] - hp[3]*hpB[0] - hp[4]*hpB[1];
            hpA[1] = hpA[0]; hpA[0] = x;
            hpB[1] = hpB[0]; hpB[0] = y1;
            float y2 = pk[0]*y1 + pk[1]*pkA[0] + pk[2]*pkA[1] - pk[3]*pkB[0] - pk[4]*pkB[1];
            pkA[1] = pkA[0]; pkA[0] = y1;
            pkB[1] = pkB[0]; pkB[0] = y2;
            float clipped;
            if (y2 >= 0f) clipped = (float) Math.tanh(y2 * k) * normalize;
            else clipped = (float) Math.tanh(y2 * kNeg) * normalize;
            float y3 = lp[0]*clipped + lp[1]*lpA[0] + lp[2]*lpA[1] - lp[3]*lpB[0] - lp[4]*lpB[1];
            lpA[1] = lpA[0]; lpA[0] = clipped;
            lpB[1] = lpB[0]; lpB[0] = y3;
            output[i] = x * dry + y3 * mixLocal;
        }
    }
}
