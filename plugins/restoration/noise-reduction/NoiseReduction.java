package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Noise Reduction — three-band downward expander. The signal is split
// into low (<300 Hz), mid (300–3000 Hz) and high (>3000 Hz) bands via
// Linkwitz-Riley 2nd-order crossovers. Each band gets its own envelope
// follower and its own threshold; samples below threshold are attenuated
// according to the expansion ratio. Steady-state noise in any band
// (mains buzz, mic hiss, breath/room rumble) sits below the threshold
// for that band and gets pulled down; transient vocal energy stays
// above the threshold and is passed through unchanged.
//
// Simpler than a phase-vocoder spectral de-noiser, but with sensible
// defaults it punches well above its weight on broadband mic noise.

public final class NoiseReduction implements VocalMonitorNativePlugin {

    // LR2 crossover state for low/mid split (at 300 Hz).
    private final float[] lp1A = new float[2], lp1B = new float[2];
    private final float[] hp1A = new float[2], hp1B = new float[2];
    // LR2 crossover state for mid/high split (at 3000 Hz).
    private final float[] lp2A = new float[2], lp2B = new float[2];
    private final float[] hp2A = new float[2], hp2B = new float[2];

    private float envLow = 0f, envMid = 0f, envHigh = 0f;
    private float gainLow = 1f, gainMid = 1f, gainHigh = 1f;
    private int sampleRate = 44100;

    private float threshold = -50f;
    private float reduction = 18f;
    private float release = 80f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) {
            lp1A[i] = lp1B[i] = hp1A[i] = hp1B[i] = 0f;
            lp2A[i] = lp2B[i] = hp2A[i] = hp2B[i] = 0f;
        }
        envLow = envMid = envHigh = 0f;
        gainLow = gainMid = gainHigh = 1f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "threshold", "reduction", "release" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "threshold": return -80f;
            case "reduction": return 0f;
            case "release":   return 5f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "threshold": return -10f;
            case "reduction": return 36f;
            case "release":   return 500f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "threshold": return -50f;
            case "reduction": return 18f;
            case "release":   return 80f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "threshold": return "Threshold (dB)";
            case "reduction": return "Max GR (dB)";
            case "release":   return "Release (ms)";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
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
        final float[] lp1 = bqLP(300f, sampleRate);
        final float[] hp1 = bqHP(300f, sampleRate);
        final float[] lp2 = bqLP(3000f, sampleRate);
        final float[] hp2 = bqHP(3000f, sampleRate);
        final float threshLin = (float) Math.pow(10.0, threshold / 20.0);
        final float minGain = (float) Math.pow(10.0, -reduction / 20.0);
        final float attCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1.0, sampleRate * release / 1000.0));
        final float envCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.010));
        float eL = envLow, eM = envMid, eH = envHigh;
        float gL = gainLow, gM = gainMid, gH = gainHigh;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float lpLow  = lp1[0]*x + lp1[1]*lp1A[0] + lp1[2]*lp1A[1] - lp1[3]*lp1B[0] - lp1[4]*lp1B[1];
            lp1A[1] = lp1A[0]; lp1A[0] = x;
            lp1B[1] = lp1B[0]; lp1B[0] = lpLow;
            float hpMidPlus = hp1[0]*x + hp1[1]*hp1A[0] + hp1[2]*hp1A[1] - hp1[3]*hp1B[0] - hp1[4]*hp1B[1];
            hp1A[1] = hp1A[0]; hp1A[0] = x;
            hp1B[1] = hp1B[0]; hp1B[0] = hpMidPlus;
            // Split the "mid+high" output again at 3 kHz.
            float midOnly = lp2[0]*hpMidPlus + lp2[1]*lp2A[0] + lp2[2]*lp2A[1] - lp2[3]*lp2B[0] - lp2[4]*lp2B[1];
            lp2A[1] = lp2A[0]; lp2A[0] = hpMidPlus;
            lp2B[1] = lp2B[0]; lp2B[0] = midOnly;
            float highOnly = hp2[0]*hpMidPlus + hp2[1]*hp2A[0] + hp2[2]*hp2A[1] - hp2[3]*hp2B[0] - hp2[4]*hp2B[1];
            hp2A[1] = hp2A[0]; hp2A[0] = hpMidPlus;
            hp2B[1] = hp2B[0]; hp2B[0] = highOnly;

            float rL = lpLow  < 0 ? -lpLow  : lpLow;
            float rM = midOnly < 0 ? -midOnly : midOnly;
            float rH = highOnly < 0 ? -highOnly : highOnly;
            eL = eL + envCoef * (rL - eL);
            eM = eM + envCoef * (rM - eM);
            eH = eH + envCoef * (rH - eH);

            float tL = eL > threshLin ? 1f : Math.max(minGain, eL / threshLin);
            float tM = eM > threshLin ? 1f : Math.max(minGain, eM / threshLin);
            float tH = eH > threshLin ? 1f : Math.max(minGain, eH / threshLin);
            float cL = tL > gL ? attCoef : relCoef; gL = gL + cL * (tL - gL);
            float cM = tM > gM ? attCoef : relCoef; gM = gM + cM * (tM - gM);
            float cH = tH > gH ? attCoef : relCoef; gH = gH + cH * (tH - gH);

            output[i] = lpLow * gL + midOnly * gM + highOnly * gH;
        }
        envLow = eL; envMid = eM; envHigh = eH;
        gainLow = gL; gainMid = gM; gainHigh = gH;
    }
}
