package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Plate Reverb (native port) — same Schroeder topology as the JS sibling
// that lived at plate-reverb.js: four parallel feedback combs with a
// one-pole damping LP in each loop, summed and run through two series
// allpasses for diffusion, with a separate pre-delay tap on the input.
//
// The JS version's per-sample inner loop costs ~30 multiply-adds and 6
// array index wraps; running through Rhino's bytecode interpreter that's
// the bulk of CPU on a vocal preview. JVM bytecode collapses the same
// loop to a few hundred nanoseconds per sample.
public final class PlateReverb implements VocalMonitorNativePlugin {

    private float[] preBuf;
    private int preBufLen;
    private int preWrite;

    private final float[][] combs = new float[4][];
    private final int[] combLen = new int[4];
    private final int[] combIdx = new int[4];
    private final float[] combLp = new float[4];
    private final int[] combBase = { 1687, 1601, 2053, 2251 };

    private final float[][] aps = new float[2][];
    private final int[] apLen = new int[2];
    private final int[] apIdx = new int[2];
    private final int[] apBase = { 347, 113 };

    private float srScale = 1f;
    private int sampleRate = 44100;

    private float size = 0.75f, decay = 0.82f, damping = 0.4f, preDelayMs = 0f, mix = 0.32f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        srScale = sr / 44100f;
        for (int c = 0; c < 4; c++) {
            int len = (int) Math.ceil(combBase[c] * 1.6 * srScale);
            combs[c] = new float[len];
            combLen[c] = len;
            combIdx[c] = 0;
            combLp[c] = 0f;
        }
        for (int a = 0; a < 2; a++) {
            int len = (int) Math.ceil(apBase[a] * 1.6 * srScale);
            aps[a] = new float[len];
            apLen[a] = len;
            apIdx[a] = 0;
        }
        preBufLen = (int) Math.ceil(sr * 0.1);
        preBuf = new float[preBufLen];
        preWrite = 0;
    }

    @Override
    public String[] parameterNames() {
        return new String[] { "size", "decay", "damping", "preDelay", "mix" };
    }

    @Override
    public float parameterMin(String name) {
        switch (name) {
            case "size":     return 0.3f;
            case "decay":    return 0f;
            case "damping":  return 0f;
            case "preDelay": return 0f;
            case "mix":      return 0f;
            default:         return 0f;
        }
    }

    @Override
    public float parameterMax(String name) {
        switch (name) {
            case "size":     return 1f;
            case "decay":    return 0.97f;
            case "damping":  return 1f;
            case "preDelay": return 100f;
            case "mix":      return 1f;
            default:         return 1f;
        }
    }

    @Override
    public float parameterDefault(String name) {
        switch (name) {
            case "size":     return 0.75f;
            case "decay":    return 0.82f;
            case "damping":  return 0.4f;
            case "preDelay": return 0f;
            case "mix":      return 0.32f;
            default:         return 0f;
        }
    }

    @Override
    public String parameterLabel(String name) {
        switch (name) {
            case "size":     return "Size";
            case "decay":    return "Decay";
            case "damping":  return "Damping";
            case "preDelay": return "Pre (ms)";
            case "mix":      return "Mix";
            default:         return name;
        }
    }

    @Override
    public void setParameter(String name, float value) {
        switch (name) {
            case "size":     size = value; break;
            case "decay":    decay = value; break;
            case "damping":  damping = value; break;
            case "preDelay": preDelayMs = value; break;
            case "mix":      mix = value; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float lpA = 1f - damping * 0.85f;
        final float apG = 0.5f;
        final int preSampClean = Math.max(0, Math.min(preBufLen - 1,
                (int) Math.floor(preDelayMs * sampleRate / 1000f)));

        final int cl0 = Math.max(8, (int) Math.floor(combBase[0] * srScale * (0.6f + 0.4f * size)));
        final int cl1 = Math.max(8, (int) Math.floor(combBase[1] * srScale * (0.6f + 0.4f * size)));
        final int cl2 = Math.max(8, (int) Math.floor(combBase[2] * srScale * (0.6f + 0.4f * size)));
        final int cl3 = Math.max(8, (int) Math.floor(combBase[3] * srScale * (0.6f + 0.4f * size)));
        final int al0 = Math.max(4, (int) Math.floor(apBase[0] * srScale));
        final int al1 = Math.max(4, (int) Math.floor(apBase[1] * srScale));

        final float[] b0 = combs[0], b1 = combs[1], b2 = combs[2], b3 = combs[3];
        final int bl0 = combLen[0], bl1 = combLen[1], bl2 = combLen[2], bl3 = combLen[3];
        int i0 = combIdx[0], i1 = combIdx[1], i2 = combIdx[2], i3 = combIdx[3];
        float l0 = combLp[0], l1 = combLp[1], l2 = combLp[2], l3 = combLp[3];

        final float[] ap0buf = aps[0], ap1buf = aps[1];
        final int ap0Len = apLen[0], ap1Len = apLen[1];
        int ap0i = apIdx[0], ap1i = apIdx[1];
        int pW = preWrite;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            final float x = input[i];
            preBuf[pW] = x;
            int pR = pW - preSampClean;
            if (pR < 0) pR += preBufLen;
            final float pre = preBuf[pR];
            pW++;
            if (pW >= preBufLen) pW = 0;

            int r0 = i0 - cl0; if (r0 < 0) r0 += bl0;
            float d0 = b0[r0];
            l0 = l0 + lpA * (d0 - l0);
            b0[i0] = pre + l0 * decay;
            i0++; if (i0 >= bl0) i0 = 0;

            int r1 = i1 - cl1; if (r1 < 0) r1 += bl1;
            float d1 = b1[r1];
            l1 = l1 + lpA * (d1 - l1);
            b1[i1] = pre + l1 * decay;
            i1++; if (i1 >= bl1) i1 = 0;

            int r2 = i2 - cl2; if (r2 < 0) r2 += bl2;
            float d2 = b2[r2];
            l2 = l2 + lpA * (d2 - l2);
            b2[i2] = pre + l2 * decay;
            i2++; if (i2 >= bl2) i2 = 0;

            int r3 = i3 - cl3; if (r3 < 0) r3 += bl3;
            float d3 = b3[r3];
            l3 = l3 + lpA * (d3 - l3);
            b3[i3] = pre + l3 * decay;
            i3++; if (i3 >= bl3) i3 = 0;

            float combSum = (l0 + l1 + l2 + l3) * 0.25f;

            int aR0 = ap0i - al0; if (aR0 < 0) aR0 += ap0Len;
            float aD0 = ap0buf[aR0];
            float aIn0 = combSum + aD0 * apG;
            ap0buf[ap0i] = aIn0;
            float ap0Out = aD0 - aIn0 * apG;
            ap0i++; if (ap0i >= ap0Len) ap0i = 0;

            int aR1 = ap1i - al1; if (aR1 < 0) aR1 += ap1Len;
            float aD1 = ap1buf[aR1];
            float aIn1 = ap0Out + aD1 * apG;
            ap1buf[ap1i] = aIn1;
            float wet = aD1 - aIn1 * apG;
            ap1i++; if (ap1i >= ap1Len) ap1i = 0;

            output[i] = x * (1f - mix) + wet * mix;
        }

        combIdx[0] = i0; combIdx[1] = i1; combIdx[2] = i2; combIdx[3] = i3;
        combLp[0] = l0; combLp[1] = l1; combLp[2] = l2; combLp[3] = l3;
        apIdx[0] = ap0i; apIdx[1] = ap1i;
        preWrite = pW;
    }
}
