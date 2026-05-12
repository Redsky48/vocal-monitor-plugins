package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Detune Doubler (native port) — ADT-style doubler: two granular voices
// flat/sharp by N cents and a few ms late, blended under the dry.
// Beatles ADT → subtle thickening; pushed hard → Bee Gees / Imogen Heap.
// Hann window is a pre-computed LUT.
public final class DetuneDoubler implements VocalMonitorNativePlugin {
    private float[] buf;
    private int bufLen;
    private int write = 0;
    private int grainSize;
    private int phaseLo = 0, phaseHi = 0;
    private float[] hann;
    private int sampleRate = 44100;
    private float detune = 18f, delay = 12f, width = 0.8f, mix = 0.45f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = (int) Math.ceil(sr * 0.1);
        buf = new float[bufLen];
        write = 0;
        grainSize = (int) Math.floor(sr * 0.06);
        phaseLo = 0;
        phaseHi = grainSize / 2;
        hann = new float[grainSize];
        for (int i = 0; i < grainSize; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / grainSize));
        }
    }

    @Override public String[] parameterNames() { return new String[] { "detune", "delay", "width", "mix" }; }
    @Override public float parameterMin(String n) {
        if ("detune".equals(n)) return 1f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "detune": return 60f;
            case "delay":  return 40f;
            default:       return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "detune": return 18f;
            case "delay":  return 12f;
            case "width":  return 0.8f;
            case "mix":    return 0.45f;
            default:       return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "detune": return "Detune (¢)";
            case "delay":  return "Delay (ms)";
            case "width":  return "Width";
            case "mix":    return "Mix";
            default:       return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "detune": detune = v; break;
            case "delay":  delay = v; break;
            case "width":  width = v; break;
            case "mix":    mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float rLo = (float) Math.pow(2.0, -detune / 1200.0);
        final float rHi = (float) Math.pow(2.0,  detune / 1200.0);
        final int delaySamp = Math.max(1, (int) Math.floor(delay * sampleRate / 1000f));
        final int dHi = (int) Math.floor(delaySamp * 1.6);
        final int gs = grainSize;
        final int halfGs = gs / 2;
        final float widthScaled = 0.5f * (0.5f + 0.5f * width);
        final float mixLocal = mix;
        final float dry = 1f - mixLocal * 0.5f;
        final int bL = bufLen;
        final float[] b = buf;
        final float[] hannLocal = hann;
        int w = write, pLo = phaseLo, pHi = phaseHi;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            b[w] = x;

            int pA = pLo;
            int pB = pLo + halfGs; if (pB >= gs) pB -= gs;
            float rA = w - gs * rLo - delaySamp + pA * rLo;
            float rB = w - gs * rLo - delaySamp + pB * rLo;
            while (rA < 0) rA += bL; while (rA >= bL) rA -= bL;
            while (rB < 0) rB += bL; while (rB >= bL) rB -= bL;
            int iA = (int) rA; float fA = rA - iA; int jA = iA + 1; if (jA >= bL) jA = 0;
            int iB = (int) rB; float fB = rB - iB; int jB = iB + 1; if (jB >= bL) jB = 0;
            float lo = (b[iA] * (1f - fA) + b[jA] * fA) * hannLocal[pA]
                     + (b[iB] * (1f - fB) + b[jB] * fB) * hannLocal[pB];

            int pC = pHi;
            int pD = pHi + halfGs; if (pD >= gs) pD -= gs;
            float rC = w - gs * rHi - dHi + pC * rHi;
            float rD = w - gs * rHi - dHi + pD * rHi;
            while (rC < 0) rC += bL; while (rC >= bL) rC -= bL;
            while (rD < 0) rD += bL; while (rD >= bL) rD -= bL;
            int iC = (int) rC; float fC = rC - iC; int jC = iC + 1; if (jC >= bL) jC = 0;
            int iD = (int) rD; float fD = rD - iD; int jD = iD + 1; if (jD >= bL) jD = 0;
            float hi = (b[iC] * (1f - fC) + b[jC] * fC) * hannLocal[pC]
                     + (b[iD] * (1f - fD) + b[jD] * fD) * hannLocal[pD];

            float wet = (lo + hi) * widthScaled;
            output[i] = x * dry + wet * mixLocal;

            pLo++; if (pLo >= gs) pLo = 0;
            pHi++; if (pHi >= gs) pHi = 0;
            w++;   if (w   >= bL) w   = 0;
        }
        write = w; phaseLo = pLo; phaseHi = pHi;
    }
}
