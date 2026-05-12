package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// De-plosive — kills the "thump" you get when a singer pops their P/B
// consonants too close to the mic. A plosive is a brief burst of energy
// concentrated below ~150 Hz that doesn't follow the surrounding vocal
// envelope; we detect that by running a sidechain low-pass + fast peak
// follower, and when its envelope spikes well above the (much slower)
// running average we crossfade an aggressive HPF in on the main path
// for the duration of the event.
//
// The HPF is two cascaded biquads at ~150 Hz — steep enough to remove
// the thump entirely while leaving the rest of the vocal body intact.
// Same architecture as iZotope RX De-plosive.

public final class DePlosive implements VocalMonitorNativePlugin {

    // Sidechain LP biquads (2nd-order Butterworth at 200 Hz, cascade for 24 dB/oct).
    private final float[] scA1 = new float[2], scB1 = new float[2];
    private final float[] scA2 = new float[2], scB2 = new float[2];
    // Main-path HPF biquads (24 dB/oct cascade).
    private final float[] hpA1 = new float[2], hpB1 = new float[2];
    private final float[] hpA2 = new float[2], hpB2 = new float[2];
    private float fastEnv = 0f, slowEnv = 1e-4f;
    private float gateGain = 0f; // 0 = dry only, 1 = full HPF

    private int sampleRate = 44100;
    private float sensitivity = 0.6f;
    private float frequency = 150f;
    private float strength = 1f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) {
            scA1[i] = scB1[i] = scA2[i] = scB2[i] = 0f;
            hpA1[i] = hpB1[i] = hpA2[i] = hpB2[i] = 0f;
        }
        fastEnv = 0f; slowEnv = 1e-4f; gateGain = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "sensitivity", "frequency", "strength" }; }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "sensitivity": return 0f;
            case "frequency":   return 60f;
            case "strength":    return 0f;
            default:            return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "sensitivity": return 1f;
            case "frequency":   return 300f;
            case "strength":    return 1f;
            default:            return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "sensitivity": return 0.6f;
            case "frequency":   return 150f;
            case "strength":    return 1f;
            default:            return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "sensitivity": return "Sensitivity";
            case "frequency":   return "Cutoff (Hz)";
            case "strength":    return "Strength";
            default:            return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "sensitivity": sensitivity = v; break;
            case "frequency":   frequency = v; break;
            case "strength":    strength = v; break;
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
        final float[] lp = bqLP(200f, sampleRate);
        final float[] hp = bqHP(frequency, sampleRate);
        // Sensitivity → how many times the fast envelope must overshoot
        // the slow one before we fire. Lower threshold = more triggers.
        final float threshMul = 6f - sensitivity * 4f; // 2..6
        final float fastCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.003));   // 3 ms
        final float slowCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.300));   // 300 ms
        final float openCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));   // 5 ms attack
        final float closeCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.080));  // 80 ms release
        final float strLocal = strength;
        float fE = fastEnv, sE = slowEnv, gg = gateGain;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            // Sidechain LP cascade (2 × biquad = 24 dB/oct).
            float sc1 = lp[0]*x + lp[1]*scA1[0] + lp[2]*scA1[1] - lp[3]*scB1[0] - lp[4]*scB1[1];
            scA1[1] = scA1[0]; scA1[0] = x;
            scB1[1] = scB1[0]; scB1[0] = sc1;
            float sc = lp[0]*sc1 + lp[1]*scA2[0] + lp[2]*scA2[1] - lp[3]*scB2[0] - lp[4]*scB2[1];
            scA2[1] = scA2[0]; scA2[0] = sc1;
            scB2[1] = scB2[0]; scB2[0] = sc;
            float rect = sc < 0 ? -sc : sc;
            fE = fE + fastCoef * (rect - fE);
            // Slow env only follows on the way DOWN — that way a sustained
            // bass note doesn't gradually raise the floor and arm us against
            // ourselves. Plosives are short, so the slow env keeps low.
            if (rect < sE) sE = sE + slowCoef * (rect - sE);
            else sE = sE + slowCoef * 0.05f * (rect - sE);
            if (sE < 1e-6f) sE = 1e-6f;

            float target = fE > sE * threshMul ? 1f : 0f;
            float coef = target > gg ? openCoef : closeCoef;
            gg = gg + coef * (target - gg);

            // Main path: HPF cascade.
            float h1 = hp[0]*x + hp[1]*hpA1[0] + hp[2]*hpA1[1] - hp[3]*hpB1[0] - hp[4]*hpB1[1];
            hpA1[1] = hpA1[0]; hpA1[0] = x;
            hpB1[1] = hpB1[0]; hpB1[0] = h1;
            float h2 = hp[0]*h1 + hp[1]*hpA2[0] + hp[2]*hpA2[1] - hp[3]*hpB2[0] - hp[4]*hpB2[1];
            hpA2[1] = hpA2[0]; hpA2[0] = h1;
            hpB2[1] = hpB2[0]; hpB2[0] = h2;

            float crossfade = gg * strLocal;
            output[i] = x * (1f - crossfade) + h2 * crossfade;
        }
        fastEnv = fE; slowEnv = sE; gateGain = gg;
    }
}
