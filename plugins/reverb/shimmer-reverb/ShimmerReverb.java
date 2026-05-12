package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Shimmer Reverb (native port) — direct translation of shimmer-reverb.js.
// A Schroeder 4-comb + 2-allpass core whose feedback path runs through a
// granular pitch shifter set to +12 semitones (read at 2x speed with two
// Hann-windowed grains crossfaded). Each generation of echoes climbs an
// octave — Eno-style ambient bloom.
//
// The JS version was the single most CPU-hungry plugin in this registry
// because the granular pitch shifter does two cos() calls per sample on
// top of the comb network. Pre-computing the Hann window into a lookup
// table and running everything as JVM bytecode brings it well under
// realtime even with several instances stacked.
public final class ShimmerReverb implements VocalMonitorNativePlugin {

    private float srScale = 1f;

    private final int[] combBase = { 1116, 1188, 1277, 1356 };
    private final int[] apBase = { 225, 556 };
    private final float[][] combs = new float[4][];
    private final int[] combLen = new int[4];
    private final int[] combIdx = new int[4];
    private final float[] combLp = new float[4];
    private final float[][] aps = new float[2][];
    private final int[] apLen = new int[2];
    private final int[] apIdx = new int[2];

    private float[] gBuf;
    private int gBufLen;
    private int gWrite;
    private int grainSize;
    private int gPhase;

    // Pre-computed Hann envelope: hann[i] = 0.5 - 0.5 * cos(2π i / grainSize).
    // Cutting the two per-sample cos() calls out of the inner loop is the
    // single biggest win moving from Rhino interpreter to JVM bytecode.
    private float[] hann;

    private float decay = 0.85f, shimmer = 0.5f, damping = 0.35f, mix = 0.4f;

    @Override
    public void init(int sampleRate) {
        srScale = sampleRate / 44100f;
        for (int c = 0; c < 4; c++) {
            int L = (int) Math.ceil(combBase[c] * 1.6 * srScale);
            combs[c] = new float[L]; combLen[c] = L; combIdx[c] = 0; combLp[c] = 0f;
        }
        for (int a = 0; a < 2; a++) {
            int L = (int) Math.ceil(apBase[a] * 1.6 * srScale);
            aps[a] = new float[L]; apLen[a] = L; apIdx[a] = 0;
        }
        gBufLen = (int) Math.ceil(sampleRate * 0.25);
        gBuf = new float[gBufLen];
        gWrite = 0;
        grainSize = (int) Math.floor(sampleRate * 0.08);
        gPhase = 0;
        hann = new float[grainSize];
        for (int i = 0; i < grainSize; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / grainSize));
        }
    }

    @Override
    public String[] parameterNames() {
        return new String[] { "decay", "shimmer", "damping", "mix" };
    }

    @Override
    public float parameterMin(String name) {
        if ("decay".equals(name)) return 0.5f;
        return 0f;
    }

    @Override
    public float parameterMax(String name) {
        if ("decay".equals(name)) return 0.97f;
        return 1f;
    }

    @Override
    public float parameterDefault(String name) {
        switch (name) {
            case "decay":   return 0.85f;
            case "shimmer": return 0.5f;
            case "damping": return 0.35f;
            case "mix":     return 0.4f;
            default:        return 0f;
        }
    }

    @Override
    public String parameterLabel(String name) {
        switch (name) {
            case "decay":   return "Decay";
            case "shimmer": return "Shimmer";
            case "damping": return "Damping";
            case "mix":     return "Mix";
            default:        return name;
        }
    }

    @Override
    public void setParameter(String name, float value) {
        switch (name) {
            case "decay":   decay = value; break;
            case "shimmer": shimmer = value; break;
            case "damping": damping = value; break;
            case "mix":     mix = value; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float lpA = 1f - damping * 0.85f;
        final float apG = 0.5f;
        final int cl0 = combLen[0] - 2, cl1 = combLen[1] - 2;
        final int cl2 = combLen[2] - 2, cl3 = combLen[3] - 2;
        final int al0 = apLen[0] - 2, al1 = apLen[1] - 2;
        final int gs = grainSize;
        final int halfGs = gs / 2;
        final int gBL = gBufLen;
        final float grainInc = 2f;
        final float[] hannLocal = hann;

        final float[] b0 = combs[0], b1 = combs[1], b2 = combs[2], b3 = combs[3];
        final int bl0 = combLen[0], bl1 = combLen[1], bl2 = combLen[2], bl3 = combLen[3];
        int i0 = combIdx[0], i1 = combIdx[1], i2 = combIdx[2], i3 = combIdx[3];
        float l0 = combLp[0], l1 = combLp[1], l2 = combLp[2], l3 = combLp[3];

        final float[] ap0 = aps[0], ap1 = aps[1];
        final int ap0Len = apLen[0], ap1Len = apLen[1];
        int ap0i = apIdx[0], ap1i = apIdx[1];

        int gW = gWrite;
        int gP = gPhase;
        final float[] gB = gBuf;
        final float shimmerScaled = shimmer * 0.7f;
        final float decayLocal = decay;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            final float x = input[i];

            // --- Granular octave-up read from the feedback buffer. ---
            final int gpA = gP;
            final int gpB = (gP + halfGs) % gs;
            float rA = gW - gs * grainInc + gpA * grainInc;
            float rB = gW - gs * grainInc + gpB * grainInc;
            while (rA < 0) rA += gBL;
            while (rA >= gBL) rA -= gBL;
            while (rB < 0) rB += gBL;
            while (rB >= gBL) rB -= gBL;
            int iA = (int) rA; float fA = rA - iA;
            int jA = iA + 1; if (jA >= gBL) jA = 0;
            float sA = gB[iA] * (1f - fA) + gB[jA] * fA;
            int iB = (int) rB; float fB = rB - iB;
            int jB = iB + 1; if (jB >= gBL) jB = 0;
            float sB = gB[iB] * (1f - fB) + gB[jB] * fB;
            float pitched = sA * hannLocal[gpA] + sB * hannLocal[gpB];
            gP++; if (gP >= gs) gP = 0;

            final float rvIn = x + pitched * shimmerScaled;

            int r0 = i0 - cl0; if (r0 < 0) r0 += bl0;
            float d0 = b0[r0];
            l0 = l0 + lpA * (d0 - l0);
            b0[i0] = rvIn + l0 * decayLocal;
            i0++; if (i0 >= bl0) i0 = 0;

            int r1 = i1 - cl1; if (r1 < 0) r1 += bl1;
            float d1 = b1[r1];
            l1 = l1 + lpA * (d1 - l1);
            b1[i1] = rvIn + l1 * decayLocal;
            i1++; if (i1 >= bl1) i1 = 0;

            int r2 = i2 - cl2; if (r2 < 0) r2 += bl2;
            float d2 = b2[r2];
            l2 = l2 + lpA * (d2 - l2);
            b2[i2] = rvIn + l2 * decayLocal;
            i2++; if (i2 >= bl2) i2 = 0;

            int r3 = i3 - cl3; if (r3 < 0) r3 += bl3;
            float d3 = b3[r3];
            l3 = l3 + lpA * (d3 - l3);
            b3[i3] = rvIn + l3 * decayLocal;
            i3++; if (i3 >= bl3) i3 = 0;

            float combSum = (l0 + l1 + l2 + l3) * 0.25f;

            int aR0 = ap0i - al0; if (aR0 < 0) aR0 += ap0Len;
            float aD0 = ap0[aR0];
            float aIn0 = combSum + aD0 * apG;
            ap0[ap0i] = aIn0;
            float ap0Out = aD0 - aIn0 * apG;
            ap0i++; if (ap0i >= ap0Len) ap0i = 0;

            int aR1 = ap1i - al1; if (aR1 < 0) aR1 += ap1Len;
            float aD1 = ap1[aR1];
            float aIn1 = ap0Out + aD1 * apG;
            ap1[ap1i] = aIn1;
            float wet = aD1 - aIn1 * apG;
            ap1i++; if (ap1i >= ap1Len) ap1i = 0;

            gB[gW] = wet;
            gW++; if (gW >= gBL) gW = 0;

            output[i] = x * (1f - mix) + wet * mix;
        }

        combIdx[0] = i0; combIdx[1] = i1; combIdx[2] = i2; combIdx[3] = i3;
        combLp[0] = l0; combLp[1] = l1; combLp[2] = l2; combLp[3] = l3;
        apIdx[0] = ap0i; apIdx[1] = ap1i;
        gWrite = gW; gPhase = gP;
    }
}
