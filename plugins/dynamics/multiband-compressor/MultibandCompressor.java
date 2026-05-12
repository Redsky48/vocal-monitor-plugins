package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Multiband Compressor — three-band LR2 crossover (300 Hz / 3 kHz) with
// an independent feedforward peak compressor per band. Each band has
// its own threshold; one shared ratio/attack/release keeps the UI
// simple. Useful when wideband compression squashes the wrong band:
// e.g. a heavy bass hit pumping the highs, or a vocal "ess" triggering
// the lows.

public final class MultibandCompressor implements VocalMonitorNativePlugin {

    // LR2 crossover state (one biquad per filter, two crossovers).
    private final float[] lp1A = new float[2], lp1B = new float[2];
    private final float[] hp1A = new float[2], hp1B = new float[2];
    private final float[] lp2A = new float[2], lp2B = new float[2];
    private final float[] hp2A = new float[2], hp2B = new float[2];

    private float envLow = 0f, envMid = 0f, envHigh = 0f;
    private float gainLow = 1f, gainMid = 1f, gainHigh = 1f;
    private int sampleRate = 44100;

    private float threshLow = -18f, threshMid = -18f, threshHigh = -18f;
    private float ratio = 4f;
    private float attack = 8f;
    private float release = 120f;
    private float makeup = 0f;

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
        return new String[] { "threshLow", "threshMid", "threshHigh", "ratio", "attack", "release", "makeup" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "threshLow": case "threshMid": case "threshHigh": return -60f;
            case "ratio":   return 1f;
            case "attack":  return 0.1f;
            case "release": return 5f;
            case "makeup":  return 0f;
            default:        return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "threshLow": case "threshMid": case "threshHigh": return 0f;
            case "ratio":   return 20f;
            case "attack":  return 100f;
            case "release": return 1000f;
            case "makeup":  return 24f;
            default:        return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "threshLow": case "threshMid": case "threshHigh": return -18f;
            case "ratio":   return 4f;
            case "attack":  return 8f;
            case "release": return 120f;
            case "makeup":  return 0f;
            default:        return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "threshLow":  return "Low Thr (dB)";
            case "threshMid":  return "Mid Thr (dB)";
            case "threshHigh": return "High Thr (dB)";
            case "ratio":      return "Ratio";
            case "attack":     return "Att (ms)";
            case "release":    return "Rel (ms)";
            case "makeup":     return "Makeup (dB)";
            default:           return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "threshLow":  threshLow = v; break;
            case "threshMid":  threshMid = v; break;
            case "threshHigh": threshHigh = v; break;
            case "ratio":      ratio = v; break;
            case "attack":     attack = v; break;
            case "release":    release = v; break;
            case "makeup":     makeup = v; break;
        }
    }

    private static float[] bqLP(float fc, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) ((1.0 - c) * 0.5 / a0), (float) ((1.0 - c) / a0), (float) ((1.0 - c) * 0.5 / a0),
            (float) (-2.0 * c / a0), (float) ((1.0 - alpha) / a0)
        };
    }
    private static float[] bqHP(float fc, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) ((1.0 + c) * 0.5 / a0), (float) (-(1.0 + c) / a0), (float) ((1.0 + c) * 0.5 / a0),
            (float) (-2.0 * c / a0), (float) ((1.0 - alpha) / a0)
        };
    }

    private static float computeGain(float envDb, float thresh, float invRatio, float halfKnee, float knee) {
        float gr = 0f;
        float diff = envDb - thresh;
        if (diff > -halfKnee) {
            if (diff < halfKnee && knee > 0f) {
                float t = (diff + halfKnee) / knee;
                gr = (1f - invRatio) * t * t * halfKnee;
            } else {
                gr = (envDb - thresh) * (1f - invRatio);
            }
        }
        return (float) Math.pow(10.0, -gr / 20.0);
    }

    @Override
    public void process(float[] input, float[] output) {
        final float[] lp1 = bqLP(300f, sampleRate);
        final float[] hp1 = bqHP(300f, sampleRate);
        final float[] lp2 = bqLP(3000f, sampleRate);
        final float[] hp2 = bqHP(3000f, sampleRate);
        final float invRatio = 1f / ratio;
        final float knee = 6f;
        final float halfKnee = knee * 0.5f;
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * attack / 1000.0));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        final float makeupLin = (float) Math.pow(10.0, makeup / 20.0);
        final float ln10 = (float) Math.log(10);
        float eL = envLow, eM = envMid, eH = envHigh;
        float gL = gainLow, gM = gainMid, gH = gainHigh;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float lpLow = lp1[0]*x + lp1[1]*lp1A[0] + lp1[2]*lp1A[1] - lp1[3]*lp1B[0] - lp1[4]*lp1B[1];
            lp1A[1] = lp1A[0]; lp1A[0] = x;
            lp1B[1] = lp1B[0]; lp1B[0] = lpLow;
            float hpMidPlus = hp1[0]*x + hp1[1]*hp1A[0] + hp1[2]*hp1A[1] - hp1[3]*hp1B[0] - hp1[4]*hp1B[1];
            hp1A[1] = hp1A[0]; hp1A[0] = x;
            hp1B[1] = hp1B[0]; hp1B[0] = hpMidPlus;
            float midOnly = lp2[0]*hpMidPlus + lp2[1]*lp2A[0] + lp2[2]*lp2A[1] - lp2[3]*lp2B[0] - lp2[4]*lp2B[1];
            lp2A[1] = lp2A[0]; lp2A[0] = hpMidPlus;
            lp2B[1] = lp2B[0]; lp2B[0] = midOnly;
            float highOnly = hp2[0]*hpMidPlus + hp2[1]*hp2A[0] + hp2[2]*hp2A[1] - hp2[3]*hp2B[0] - hp2[4]*hp2B[1];
            hp2A[1] = hp2A[0]; hp2A[0] = hpMidPlus;
            hp2B[1] = hp2B[0]; hp2B[0] = highOnly;

            float rL = lpLow < 0 ? -lpLow : lpLow;
            float rM = midOnly < 0 ? -midOnly : midOnly;
            float rH = highOnly < 0 ? -highOnly : highOnly;
            float cL = rL > eL ? attCoef : relCoef; eL = eL + cL * (rL - eL);
            float cM = rM > eM ? attCoef : relCoef; eM = eM + cM * (rM - eM);
            float cH = rH > eH ? attCoef : relCoef; eH = eH + cH * (rH - eH);
            float eLDb = eL > 1e-6f ? 20f * (float) Math.log(eL) / ln10 : -120f;
            float eMDb = eM > 1e-6f ? 20f * (float) Math.log(eM) / ln10 : -120f;
            float eHDb = eH > 1e-6f ? 20f * (float) Math.log(eH) / ln10 : -120f;
            gL = computeGain(eLDb, threshLow,  invRatio, halfKnee, knee);
            gM = computeGain(eMDb, threshMid,  invRatio, halfKnee, knee);
            gH = computeGain(eHDb, threshHigh, invRatio, halfKnee, knee);

            output[i] = (lpLow * gL + midOnly * gM + highOnly * gH) * makeupLin;
        }
        envLow = eL; envMid = eM; envHigh = eH;
        gainLow = gL; gainMid = gM; gainHigh = gH;
    }
}
