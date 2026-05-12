package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Click Suite — one knob, three repair engines fused into a single
// chain: a linear-predictor click remover (the De-clicker), a 2.5 kHz
// bandpass mouth-click ducker (the De-mouth) and a sidechain-driven
// plosive HPF (the De-plosive). Driving one shared "intensity" parameter
// scales each engine's sensitivity in lock-step, so users who don't
// want to deal with three plugins on the chain can grab this and get
// 80% of the result for one knob-turn.

public final class ClickSuite implements VocalMonitorNativePlugin {

    // De-clicker state.
    private float h1 = 0, h2 = 0, h3 = 0;
    private float residualAvg = 1e-6f;
    private int repairCounter = 0;

    // De-mouth state.
    private final float[] bpA = new float[2], bpB = new float[2];
    private float bpEnvFast = 0f, bpEnvSlow = 1e-4f;
    private float wideEnv = 0f;
    private float mouthDuck = 1f;

    // De-plosive state.
    private final float[] scA1 = new float[2], scB1 = new float[2];
    private final float[] scA2 = new float[2], scB2 = new float[2];
    private final float[] hpA1 = new float[2], hpB1 = new float[2];
    private final float[] hpA2 = new float[2], hpB2 = new float[2];
    private float fastEnv = 0f, slowEnv = 1e-4f;
    private float plosiveGate = 0f;

    private int sampleRate = 44100;
    private float intensity = 0.5f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        h1 = h2 = h3 = 0f;
        residualAvg = 1e-6f; repairCounter = 0;
        for (int i = 0; i < 2; i++) {
            bpA[i] = bpB[i] = 0f;
            scA1[i] = scB1[i] = scA2[i] = scB2[i] = 0f;
            hpA1[i] = hpB1[i] = hpA2[i] = hpB2[i] = 0f;
        }
        bpEnvFast = 0f; bpEnvSlow = 1e-4f; wideEnv = 0f; mouthDuck = 1f;
        fastEnv = 0f; slowEnv = 1e-4f; plosiveGate = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "intensity" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0.5f; }
    @Override public String parameterLabel(String n) { return "Intensity"; }
    @Override public void setParameter(String n, float v) {
        if ("intensity".equals(n)) intensity = v;
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
    private static float[] bqBP(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / (2.0 * q);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) (alpha / a0), 0f, (float) (-alpha / a0),
            (float) (-2.0 * c / a0), (float) ((1.0 - alpha) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        // De-clicker: residual threshold drops with intensity.
        final float threshMul = 14f - intensity * 10f;
        final int repairLen = 8;
        final float envCoefClick = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.020));

        // De-mouth: bandpass + envelopes.
        final float[] bp = bqBP(2500f, 1.4f, sampleRate);
        final float fastCoefM = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.002));
        final float slowCoefM = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.200));
        final float wideCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.030));
        final float openCoefM = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));
        final float closeCoefM = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.080));
        final float threshMulM = 5f - intensity * 3f;
        final float voiceThresh = 0.06f - intensity * 0.04f;
        final float minGainM = (float) Math.pow(10.0, -14.0 / 20.0);

        // De-plosive: sidechain LP + HPF crossfade.
        final float[] scLp = bqLP(200f, sampleRate);
        final float[] mainHp = bqHP(150f, sampleRate);
        final float threshMulP = 6f - intensity * 4f;
        final float fastCoefP = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.003));
        final float slowCoefP = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.300));
        final float openCoefP = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));
        final float closeCoefP = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.080));

        float p1 = h1, p2 = h2, p3 = h3;
        float rAvg = residualAvg;
        int rc = repairCounter;
        float bF = bpEnvFast, bS = bpEnvSlow, wE = wideEnv, gM = mouthDuck;
        float fE = fastEnv, sE = slowEnv, gP = plosiveGate;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];

            // --- Stage 1: De-clicker (predictor + interpolation). ---
            float prediction = 3f * p1 - 3f * p2 + p3;
            float residual = x - prediction;
            float absRes = residual < 0 ? -residual : residual;
            if (absRes < rAvg) rAvg = rAvg + envCoefClick * (absRes - rAvg);
            else rAvg = rAvg + envCoefClick * 0.1f * (absRes - rAvg);
            if (rAvg < 1e-7f) rAvg = 1e-7f;
            float stage1 = x;
            if (rc <= 0 && absRes > threshMul * rAvg && absRes > 0.005f) rc = repairLen;
            if (rc > 0) { stage1 = prediction; rc--; }
            p3 = p2; p2 = p1; p1 = stage1;

            // --- Stage 2: De-mouth (bandpass ducker). ---
            float bpOut = bp[0]*stage1 + bp[1]*bpA[0] + bp[2]*bpA[1] - bp[3]*bpB[0] - bp[4]*bpB[1];
            bpA[1] = bpA[0]; bpA[0] = stage1;
            bpB[1] = bpB[0]; bpB[0] = bpOut;
            float rect = bpOut < 0 ? -bpOut : bpOut;
            bF = bF + fastCoefM * (rect - bF);
            if (rect < bS) bS = bS + slowCoefM * (rect - bS);
            else bS = bS + slowCoefM * 0.05f * (rect - bS);
            if (bS < 1e-6f) bS = 1e-6f;
            float wide = stage1 < 0 ? -stage1 : stage1;
            wE = wE + wideCoef * (wide - wE);
            boolean spike = bF > bS * threshMulM;
            boolean quiet = wE < voiceThresh;
            float targetM = (spike && quiet) ? minGainM : 1f;
            float coefM = targetM < gM ? openCoefM : closeCoefM;
            gM = gM + coefM * (targetM - gM);
            float stage2 = stage1 * gM;

            // --- Stage 3: De-plosive (LF detector + HPF crossfade). ---
            float sc1 = scLp[0]*stage2 + scLp[1]*scA1[0] + scLp[2]*scA1[1] - scLp[3]*scB1[0] - scLp[4]*scB1[1];
            scA1[1] = scA1[0]; scA1[0] = stage2;
            scB1[1] = scB1[0]; scB1[0] = sc1;
            float sc = scLp[0]*sc1 + scLp[1]*scA2[0] + scLp[2]*scA2[1] - scLp[3]*scB2[0] - scLp[4]*scB2[1];
            scA2[1] = scA2[0]; scA2[0] = sc1;
            scB2[1] = scB2[0]; scB2[0] = sc;
            float lfRect = sc < 0 ? -sc : sc;
            fE = fE + fastCoefP * (lfRect - fE);
            if (lfRect < sE) sE = sE + slowCoefP * (lfRect - sE);
            else sE = sE + slowCoefP * 0.05f * (lfRect - sE);
            if (sE < 1e-6f) sE = 1e-6f;
            float targetP = fE > sE * threshMulP ? 1f : 0f;
            float coefP = targetP > gP ? openCoefP : closeCoefP;
            gP = gP + coefP * (targetP - gP);
            float hp1Out = mainHp[0]*stage2 + mainHp[1]*hpA1[0] + mainHp[2]*hpA1[1] - mainHp[3]*hpB1[0] - mainHp[4]*hpB1[1];
            hpA1[1] = hpA1[0]; hpA1[0] = stage2;
            hpB1[1] = hpB1[0]; hpB1[0] = hp1Out;
            float hp2Out = mainHp[0]*hp1Out + mainHp[1]*hpA2[0] + mainHp[2]*hpA2[1] - mainHp[3]*hpB2[0] - mainHp[4]*hpB2[1];
            hpA2[1] = hpA2[0]; hpA2[0] = hp1Out;
            hpB2[1] = hpB2[0]; hpB2[0] = hp2Out;

            output[i] = stage2 * (1f - gP) + hp2Out * gP;
        }

        h1 = p1; h2 = p2; h3 = p3;
        residualAvg = rAvg; repairCounter = rc;
        bpEnvFast = bF; bpEnvSlow = bS; wideEnv = wE; mouthDuck = gM;
        fastEnv = fE; slowEnv = sE; plosiveGate = gP;
    }
}
