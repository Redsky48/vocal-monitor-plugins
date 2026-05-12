package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// De-breath — ducks the inter-phrase inhalations that singers leave on
// every track. A breath has two giveaway features compared to sung
// vowels: (a) the total envelope sits in the noise-floor-but-above-room
// zone, and (b) the spectrum is HF-skewed (lots of fricative-like noise,
// no fundamental, no harmonic stack). We track three envelopes — full
// band, < 800 Hz "voice body" band, and > 4 kHz "breath/fricative"
// band — and combine them into a per-sample breath probability that
// drives a gain reduction.
//
// A small hysteresis state machine prevents the ducker from chattering
// at word boundaries where breath fades into pitched content.

public final class DeBreath implements VocalMonitorNativePlugin {

    private final float[] lpA = new float[2], lpB = new float[2]; // 800 Hz LP
    private final float[] hpA = new float[2], hpB = new float[2]; // 4 kHz HP
    private float envFull = 0f, envLow = 0f, envHigh = 0f;
    private float duckGain = 1f;
    private int sampleRate = 44100;

    private float sensitivity = 0.5f;
    private float reduction = 12f;       // dB max GR
    private float threshold = -36f;      // dB total-envelope ceiling for "breath candidate"

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) {
            lpA[i] = lpB[i] = hpA[i] = hpB[i] = 0f;
        }
        envFull = envLow = envHigh = 0f;
        duckGain = 1f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "sensitivity", "threshold", "reduction" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "sensitivity": return 0f;
            case "threshold":   return -70f;
            case "reduction":   return 0f;
            default:            return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "sensitivity": return 1f;
            case "threshold":   return -10f;
            case "reduction":   return 24f;
            default:            return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "sensitivity": return 0.5f;
            case "threshold":   return -36f;
            case "reduction":   return 12f;
            default:            return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "sensitivity": return "Sensitivity";
            case "threshold":   return "Thresh (dB)";
            case "reduction":   return "Max GR (dB)";
            default:            return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "sensitivity": sensitivity = v; break;
            case "threshold":   threshold = v; break;
            case "reduction":   reduction = v; break;
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
        final float[] lp = bqLP(800f, sampleRate);
        final float[] hp = bqHP(4000f, sampleRate);
        final float envCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.020));
        final float openCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.050));  // 50 ms attack-of-duck
        final float closeCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.200)); // 200 ms release
        final float threshLin = (float) Math.pow(10.0, threshold / 20.0);
        final float minGain = (float) Math.pow(10.0, -reduction / 20.0);
        // Sensitivity controls how strongly the HF/LF imbalance must
        // tilt before we call it breath. Higher sens = catches subtler
        // breaths but risks ducking faint sustained "sss" phonemes.
        final float ratioThresh = 0.8f - sensitivity * 0.4f; // 0.4..0.8
        float eF = envFull, eL = envLow, eH = envHigh, g = duckGain;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float lo = lp[0]*x + lp[1]*lpA[0] + lp[2]*lpA[1] - lp[3]*lpB[0] - lp[4]*lpB[1];
            lpA[1] = lpA[0]; lpA[0] = x;
            lpB[1] = lpB[0]; lpB[0] = lo;
            float hi = hp[0]*x + hp[1]*hpA[0] + hp[2]*hpA[1] - hp[3]*hpB[0] - hp[4]*hpB[1];
            hpA[1] = hpA[0]; hpA[0] = x;
            hpB[1] = hpB[0]; hpB[0] = hi;

            float rectF = x < 0 ? -x : x;
            float rectL = lo < 0 ? -lo : lo;
            float rectH = hi < 0 ? -hi : hi;
            eF = eF + envCoef * (rectF - eF);
            eL = eL + envCoef * (rectL - eL);
            eH = eH + envCoef * (rectH - eH);

            // Breath probability: signal is below threshold AND HF
            // dominates total (i.e. mostly fricative-noise content).
            float breath = 0f;
            if (eF < threshLin && eF > threshLin * 0.05f) {
                float hfRatio = eH / (eF + 1e-6f);    // 0..>1
                if (hfRatio > ratioThresh) {
                    // Confidence rises from 0 (at ratioThresh) up to 1.
                    breath = (hfRatio - ratioThresh) / (1f - ratioThresh + 1e-6f);
                    if (breath > 1f) breath = 1f;
                }
            }
            float target = 1f - breath * (1f - minGain);
            float coef = target < g ? openCoef : closeCoef;
            g = g + coef * (target - g);

            output[i] = x * g;
        }
        envFull = eF; envLow = eL; envHigh = eH; duckGain = g;
    }
}
