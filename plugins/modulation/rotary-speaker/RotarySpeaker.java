package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Rotary Speaker (native port) — Leslie cabinet sim. Crossover splits
// the signal into bass-rotor drum (lows) and horn-rotor treble (highs);
// each rotor adds Doppler delay modulation + amplitude tremolo at its
// own LFO rate. Slow/fast switching ramps like real motor inertia.
public final class RotarySpeaker implements VocalMonitorNativePlugin {
    private float lpState = 0f;
    private float hpPrev = 0f;
    private float hpOut = 0f;
    private float[] bassBuf;
    private float[] hornBuf;
    private int bassLen, hornLen;
    private int bassIdx = 0, hornIdx = 0;
    private float bassPhase = 0f;
    private float hornPhase = (float) Math.PI;
    private float bassRate = 0.8f;
    private float hornRate = 1.0f;
    private int sampleRate = 44100;
    private float speedKnob = 0f, depth = 0.7f, drive = 0.2f, mix = 1f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bassLen = (int) Math.floor(sr * 0.03);
        hornLen = (int) Math.floor(sr * 0.02);
        bassBuf = new float[bassLen];
        hornBuf = new float[hornLen];
        bassIdx = hornIdx = 0;
        bassPhase = 0f;
        hornPhase = (float) Math.PI;
        bassRate = 0.8f; hornRate = 1.0f;
        lpState = hpPrev = hpOut = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "speed", "depth", "drive", "mix" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "speed": return 0f;
            case "depth": return 0.7f;
            case "drive": return 0.2f;
            case "mix":   return 1f;
            default:      return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "speed": return "Speed";
            case "depth": return "Depth";
            case "drive": return "Drive";
            case "mix":   return "Mix";
            default:      return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "speed": speedKnob = v; break;
            case "depth": depth = v; break;
            case "drive": drive = v; break;
            case "mix":   mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float targetBass = 0.7f + speedKnob * 5.8f;
        final float targetHorn = 1.0f + speedKnob * 6.5f;
        final float rampCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * 0.6));
        final float dt = 1f / sampleRate;
        final float rcLp = (float) (1.0 / (2.0 * Math.PI * 800.0));
        final float lpA = dt / (rcLp + dt);
        final float hpRc = (float) (1.0 / (2.0 * Math.PI * 200.0));
        final float hpA = hpRc / (hpRc + dt);
        final float bassMaxDepth = sampleRate * 0.004f;
        final float hornMaxDepth = sampleRate * 0.0015f;
        final float driveK = 1f + drive * 5f;
        final float driveNorm = 1f / (float) Math.tanh(driveK);
        final float depthLocal = depth;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float twoPi = (float) (2.0 * Math.PI);
        final float invSr = 1f / sampleRate;
        final float[] bb = bassBuf, hb = hornBuf;
        final int bL = bassLen, hL = hornLen;
        int bIdx = bassIdx, hIdx = hornIdx;
        float bP = bassPhase, hP = hornPhase;
        float bR = bassRate, hR = hornRate;
        float lpS = lpState, hpP = hpPrev, hpO = hpOut;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float driven = (float) Math.tanh(x * driveK) * driveNorm;
            lpS = lpS + lpA * (driven - lpS);
            float lows = lpS;
            hpO = hpA * (hpO + driven - hpP);
            hpP = driven;
            float highs = driven - lows;

            bR = bR + rampCoef * (targetBass - bR);
            hR = hR + rampCoef * (targetHorn - hR);

            float bassLfo = (float) Math.sin(bP);
            float bassAmp = 1f - depthLocal * 0.3f * bassLfo;
            float bassDelay = bassMaxDepth * depthLocal * (1f + bassLfo) * 0.5f;
            bb[bIdx] = lows;
            float bRead = bIdx - bassDelay;
            while (bRead < 0) bRead += bL;
            int bI = (int) bRead; float bF = bRead - bI;
            int bJ = bI + 1; if (bJ >= bL) bJ = 0;
            float bassOut = (bb[bI] * (1f - bF) + bb[bJ] * bF) * bassAmp;
            bIdx++; if (bIdx >= bL) bIdx = 0;

            float hornLfo = (float) Math.sin(hP);
            float hornAmp = 1f - depthLocal * 0.45f * hornLfo;
            float hornDelay = hornMaxDepth * depthLocal * (1f + hornLfo) * 0.5f;
            hb[hIdx] = highs;
            float hRead = hIdx - hornDelay;
            while (hRead < 0) hRead += hL;
            int hI = (int) hRead; float hF = hRead - hI;
            int hJ = hI + 1; if (hJ >= hL) hJ = 0;
            float hornOut = (hb[hI] * (1f - hF) + hb[hJ] * hF) * hornAmp;
            hIdx++; if (hIdx >= hL) hIdx = 0;

            float wet = bassOut + hornOut;
            output[i] = x * dry + wet * mixLocal;

            bP += twoPi * bR * invSr; if (bP > twoPi) bP -= twoPi;
            hP += twoPi * hR * invSr; if (hP > twoPi) hP -= twoPi;
        }
        bassIdx = bIdx; hornIdx = hIdx;
        bassPhase = bP; hornPhase = hP;
        bassRate = bR; hornRate = hR;
        lpState = lpS; hpPrev = hpP; hpOut = hpO;
    }
}
