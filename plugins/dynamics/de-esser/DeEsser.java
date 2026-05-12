package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// De-esser (native port) — split-band sibilance ducker. Sidechain HP
// feeds an envelope follower that drops gain only on the HF band of
// the main signal — voice body stays intact, only the harsh sssss
// gets tamed.
public final class DeEsser implements VocalMonitorNativePlugin {
    private final float[] hpA = new float[2], hpB = new float[2];
    private final float[] lpA = new float[2], lpB = new float[2];
    private final float[] scA = new float[2], scB = new float[2];
    private final float[] scA2 = new float[2], scB2 = new float[2];
    private float env = 0f;
    private int sampleRate = 44100;
    private float frequency = 6500f, threshold = -28f, reduction = 12f, release = 60f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) {
            hpA[i] = hpB[i] = lpA[i] = lpB[i] = 0f;
            scA[i] = scB[i] = scA2[i] = scB2[i] = 0f;
        }
        env = 0f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "frequency", "threshold", "reduction", "release" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "frequency": return 2000f;
            case "threshold": return -60f;
            case "reduction": return 0f;
            case "release":   return 5f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "frequency": return 12000f;
            case "threshold": return 0f;
            case "reduction": return 24f;
            case "release":   return 400f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "frequency": return 6500f;
            case "threshold": return -28f;
            case "reduction": return 12f;
            case "release":   return 60f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "frequency": return "Freq (Hz)";
            case "threshold": return "Thresh (dB)";
            case "reduction": return "Max GR (dB)";
            case "release":   return "Rel (ms)";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "frequency": frequency = v; break;
            case "threshold": threshold = v; break;
            case "reduction": reduction = v; break;
            case "release":   release = v; break;
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

    @Override
    public void process(float[] input, float[] output) {
        final float[] lp = bqLP(frequency, sampleRate);
        final float[] hp = bqHP(frequency, sampleRate);
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * 0.001));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        final float threshLin = (float) Math.pow(10.0, threshold / 20.0);
        final float maxGrLin = (float) Math.pow(10.0, -reduction / 20.0);
        float e = env;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float lpOut = lp[0]*x + lp[1]*lpA[0] + lp[2]*lpA[1] - lp[3]*lpB[0] - lp[4]*lpB[1];
            lpA[1] = lpA[0]; lpA[0] = x;
            lpB[1] = lpB[0]; lpB[0] = lpOut;
            float hpOut = hp[0]*x + hp[1]*hpA[0] + hp[2]*hpA[1] - hp[3]*hpB[0] - hp[4]*hpB[1];
            hpA[1] = hpA[0]; hpA[0] = x;
            hpB[1] = hpB[0]; hpB[0] = hpOut;
            float sc1 = hp[0]*x + hp[1]*scA[0] + hp[2]*scA[1] - hp[3]*scB[0] - hp[4]*scB[1];
            scA[1] = scA[0]; scA[0] = x;
            scB[1] = scB[0]; scB[0] = sc1;
            float sc = hp[0]*sc1 + hp[1]*scA2[0] + hp[2]*scA2[1] - hp[3]*scB2[0] - hp[4]*scB2[1];
            scA2[1] = scA2[0]; scA2[0] = sc1;
            scB2[1] = scB2[0]; scB2[0] = sc;
            float rect = sc < 0 ? -sc : sc;
            float coef = rect > e ? attCoef : relCoef;
            e = e + coef * (rect - e);
            float g = 1f;
            if (e > threshLin) {
                g = threshLin / e;
                if (g < maxGrLin) g = maxGrLin;
            }
            output[i] = lpOut + hpOut * g;
        }
        env = e;
    }
}
