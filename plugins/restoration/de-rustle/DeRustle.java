package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// De-rustle — lavalier-mic clothing rustle, mic-stand handling and
// similar fast HF crackle. The events are short bursts in the 4–10 kHz
// band; we run a sidechain HP detector with a very fast attack, and any
// spike that overshoots the slow envelope reduces the high band of the
// signal for a few ms. Same idea as iZotope RX De-rustle but built on
// envelope-following instead of spectral analysis.

public final class DeRustle implements VocalMonitorNativePlugin {

    private final float[] scA1 = new float[2], scB1 = new float[2];
    private final float[] scA2 = new float[2], scB2 = new float[2];
    private final float[] hsA = new float[2], hsB = new float[2];   // high shelf for cut
    private float fastEnv = 0f, slowEnv = 1e-4f;
    private float gateGain = 0f;
    private int sampleRate = 44100;

    private float sensitivity = 0.5f;
    private float reduction = 12f;
    private float frequency = 5000f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) {
            scA1[i] = scB1[i] = scA2[i] = scB2[i] = 0f;
            hsA[i] = hsB[i] = 0f;
        }
        fastEnv = 0f; slowEnv = 1e-4f; gateGain = 0f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "sensitivity", "reduction", "frequency" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "frequency": return 2000f;
            case "reduction": return 0f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "frequency": return 12000f;
            case "reduction": return 24f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "sensitivity": return 0.5f;
            case "reduction":   return 12f;
            case "frequency":   return 5000f;
            default:            return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "sensitivity": return "Sensitivity";
            case "reduction":   return "Max GR (dB)";
            case "frequency":   return "Freq (Hz)";
            default:            return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "sensitivity": sensitivity = v; break;
            case "reduction":   reduction = v; break;
            case "frequency":   frequency = v; break;
        }
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
        final float[] sc = bqHP(frequency, sampleRate);
        final float[] hs = highShelf(frequency, -reduction, sampleRate);
        final float fastCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.002));
        final float slowCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.200));
        final float openCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.003));
        final float closeCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.060));
        final float threshMul = 6f - sensitivity * 4f;
        float fE = fastEnv, sE = slowEnv, gg = gateGain;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float sc1 = sc[0]*x + sc[1]*scA1[0] + sc[2]*scA1[1] - sc[3]*scB1[0] - sc[4]*scB1[1];
            scA1[1] = scA1[0]; scA1[0] = x;
            scB1[1] = scB1[0]; scB1[0] = sc1;
            float sc2 = sc[0]*sc1 + sc[1]*scA2[0] + sc[2]*scA2[1] - sc[3]*scB2[0] - sc[4]*scB2[1];
            scA2[1] = scA2[0]; scA2[0] = sc1;
            scB2[1] = scB2[0]; scB2[0] = sc2;
            float rect = sc2 < 0 ? -sc2 : sc2;
            fE = fE + fastCoef * (rect - fE);
            if (rect < sE) sE = sE + slowCoef * (rect - sE);
            else sE = sE + slowCoef * 0.05f * (rect - sE);
            if (sE < 1e-6f) sE = 1e-6f;
            float target = fE > sE * threshMul ? 1f : 0f;
            float coef = target > gg ? openCoef : closeCoef;
            gg = gg + coef * (target - gg);

            float shelved = hs[0]*x + hs[1]*hsA[0] + hs[2]*hsA[1] - hs[3]*hsB[0] - hs[4]*hsB[1];
            hsA[1] = hsA[0]; hsA[0] = x;
            hsB[1] = hsB[0]; hsB[0] = shelved;

            output[i] = x * (1f - gg) + shelved * gg;
        }
        fastEnv = fE; slowEnv = sE; gateGain = gg;
    }
}
