package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Harmonizer (native port) — direct translation of harmonizer.js. Two
// parallel granular pitch shifters at musically-spaced intervals, summed
// under the dry voice. The JS version did 4 grain reads × per-sample cos
// = ~16 trig ops per sample, which was the most expensive plugin in this
// registry after shimmer-reverb. Hann LUT + JVM speed makes it free.
public final class Harmonizer implements VocalMonitorNativePlugin {

    private float[] buf;
    private int bufLen;
    private int write;
    private int grainSize;
    private int phase1;
    private int phase2;
    private float[] hann;

    private float voice1 = 3f, voice2 = 7f, spread = 0.7f, mix = 0.5f;

    @Override
    public void init(int sampleRate) {
        bufLen = (int) Math.ceil(sampleRate * 0.2);
        buf = new float[bufLen];
        write = 0;
        grainSize = (int) Math.floor(sampleRate * 0.08);
        phase1 = 0;
        phase2 = grainSize / 3;
        hann = new float[grainSize];
        for (int i = 0; i < grainSize; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / grainSize));
        }
    }

    @Override
    public String[] parameterNames() {
        return new String[] { "voice1", "voice2", "spread", "mix" };
    }

    @Override
    public float parameterMin(String name) {
        if ("voice1".equals(name) || "voice2".equals(name)) return -12f;
        return 0f;
    }

    @Override
    public float parameterMax(String name) {
        if ("voice1".equals(name) || "voice2".equals(name)) return 12f;
        return 1f;
    }

    @Override
    public float parameterDefault(String name) {
        switch (name) {
            case "voice1": return 3f;
            case "voice2": return 7f;
            case "spread": return 0.7f;
            case "mix":    return 0.5f;
            default:       return 0f;
        }
    }

    @Override
    public String parameterLabel(String name) {
        switch (name) {
            case "voice1": return "V1 semi";
            case "voice2": return "V2 semi";
            case "spread": return "Spread";
            case "mix":    return "Mix";
            default:       return name;
        }
    }

    @Override
    public void setParameter(String name, float value) {
        switch (name) {
            case "voice1": voice1 = value; break;
            case "voice2": voice2 = value; break;
            case "spread": spread = value; break;
            case "mix":    mix = value; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float r1 = (float) Math.pow(2.0, voice1 / 12.0);
        final float r2 = (float) Math.pow(2.0, voice2 / 12.0);
        final int gs = grainSize;
        final int halfGs = gs / 2;
        final int bL = bufLen;
        final float[] hannLocal = hann;
        final float[] bufLocal = buf;
        final float spreadScaled = spread * 0.5f;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal * 0.5f;
        int w = write;
        int p1 = phase1;
        int p2 = phase2;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            final float x = input[i];
            bufLocal[w] = x;

            // Voice 1, two grains.
            float v1 = readGrainSum(bufLocal, w, bL, p1, halfGs, gs, r1, hannLocal);
            // Voice 2.
            float v2 = readGrainSum(bufLocal, w, bL, p2, halfGs, gs, r2, hannLocal);

            float wet = (v1 + v2) * spreadScaled;

            p1++; if (p1 >= gs) p1 = 0;
            p2++; if (p2 >= gs) p2 = 0;
            w++;  if (w  >= bL) w  = 0;

            output[i] = x * dry + wet * mixLocal;
        }

        write = w;
        phase1 = p1;
        phase2 = p2;
    }

    // Two crossfaded grain reads at `ratio` speed, summed. Inlined into
    // the loop above would be marginally faster; pulling it out keeps the
    // bytecode smaller and lets the JIT spot the pattern.
    private static float readGrainSum(float[] buf, int write, int bufLen,
                                      int phase, int halfGs, int gs,
                                      float ratio, float[] hann) {
        int pA = phase;
        int pB = phase + halfGs;
        if (pB >= gs) pB -= gs;
        float rA = write - gs * ratio + pA * ratio;
        float rB = write - gs * ratio + pB * ratio;
        while (rA < 0) rA += bufLen;
        while (rA >= bufLen) rA -= bufLen;
        while (rB < 0) rB += bufLen;
        while (rB >= bufLen) rB -= bufLen;
        int iA = (int) rA; float fA = rA - iA;
        int jA = iA + 1; if (jA >= bufLen) jA = 0;
        float sA = buf[iA] * (1f - fA) + buf[jA] * fA;
        int iB = (int) rB; float fB = rB - iB;
        int jB = iB + 1; if (jB >= bufLen) jB = 0;
        float sB = buf[iB] * (1f - fB) + buf[jB] * fB;
        return sA * hann[pA] + sB * hann[pB];
    }
}
