package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Parametric EQ (native port) — three RBJ biquad bands (low-shelf,
// peaking bell with Q, high-shelf). Channel-strip tone shaping.
public final class ParametricEq implements VocalMonitorNativePlugin {
    private final float[] ls = new float[4];
    private final float[] pk = new float[4];
    private final float[] hs = new float[4];
    private int sampleRate = 44100;
    private float lowFreq = 120f, lowGain = 0f, midFreq = 1000f, midGain = 0f,
                  midQ = 1f, highFreq = 8000f, highGain = 0f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 4; i++) { ls[i] = 0f; pk[i] = 0f; hs[i] = 0f; }
    }

    @Override public String[] parameterNames() {
        return new String[] { "lowFreq", "lowGain", "midFreq", "midGain", "midQ", "highFreq", "highGain" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "lowFreq":  return 30f;
            case "midFreq":  return 200f;
            case "midQ":     return 0.3f;
            case "highFreq": return 1500f;
            case "lowGain": case "midGain": case "highGain": return -18f;
            default:         return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "lowFreq":  return 500f;
            case "midFreq":  return 6000f;
            case "midQ":     return 8f;
            case "highFreq": return 16000f;
            case "lowGain": case "midGain": case "highGain": return 18f;
            default:         return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "lowFreq":  return 120f;
            case "midFreq":  return 1000f;
            case "midQ":     return 1f;
            case "highFreq": return 8000f;
            default:         return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "lowFreq":  return "Low Hz";
            case "lowGain":  return "Low dB";
            case "midFreq":  return "Mid Hz";
            case "midGain":  return "Mid dB";
            case "midQ":     return "Mid Q";
            case "highFreq": return "High Hz";
            case "highGain": return "High dB";
            default:         return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "lowFreq":  lowFreq = v; break;
            case "lowGain":  lowGain = v; break;
            case "midFreq":  midFreq = v; break;
            case "midGain":  midGain = v; break;
            case "midQ":     midQ = v; break;
            case "highFreq": highFreq = v; break;
            case "highGain": highGain = v; break;
        }
    }

    private static float[] lowShelf(float fc, float gainDb, int sr) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / 2.0 * Math.sqrt((A + 1.0/A) * (1.0/1.0 - 1.0) + 2.0);
        double beta = 2.0 * Math.sqrt(A) * alpha;
        double a0 = (A + 1.0) + (A - 1.0) * c + beta;
        return new float[] {
            (float) (A * ((A + 1.0) - (A - 1.0) * c + beta) / a0),
            (float) (2.0 * A * ((A - 1.0) - (A + 1.0) * c) / a0),
            (float) (A * ((A + 1.0) - (A - 1.0) * c - beta) / a0),
            (float) (-2.0 * ((A - 1.0) + (A + 1.0) * c) / a0),
            (float) (((A + 1.0) + (A - 1.0) * c - beta) / a0)
        };
    }
    private static float[] peaking(float fc, float gainDb, float q, int sr) {
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
    private static float[] highShelf(float fc, float gainDb, int sr) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / 2.0 * Math.sqrt((A + 1.0/A) * (1.0/1.0 - 1.0) + 2.0);
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
        float[] lc = lowShelf(lowFreq, lowGain, sampleRate);
        float[] mc = peaking(midFreq, midGain, midQ, sampleRate);
        float[] hc = highShelf(highFreq, highGain, sampleRate);
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float y1 = lc[0]*x + lc[1]*ls[0] + lc[2]*ls[1] - lc[3]*ls[2] - lc[4]*ls[3];
            ls[1] = ls[0]; ls[0] = x;
            ls[3] = ls[2]; ls[2] = y1;
            float y2 = mc[0]*y1 + mc[1]*pk[0] + mc[2]*pk[1] - mc[3]*pk[2] - mc[4]*pk[3];
            pk[1] = pk[0]; pk[0] = y1;
            pk[3] = pk[2]; pk[2] = y2;
            float y3 = hc[0]*y2 + hc[1]*hs[0] + hc[2]*hs[1] - hc[3]*hs[2] - hc[4]*hs[3];
            hs[1] = hs[0]; hs[0] = y2;
            hs[3] = hs[2]; hs[2] = y3;
            output[i] = y3;
        }
    }
}
