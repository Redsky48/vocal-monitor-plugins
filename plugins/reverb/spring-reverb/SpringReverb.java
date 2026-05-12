package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Spring Reverb (native port) — 8-stage allpass dispersion feeding two
// cross-coupled delay lines (the springs). Drippy, chirpy boing of a
// Fender guitar amp tank or Hammond organ.
public final class SpringReverb implements VocalMonitorNativePlugin {
    private static final int[] AP_LENS = { 37, 53, 71, 89, 107, 131, 149, 167 };
    private float[][] aps;
    private int[] apIdx;
    private float[] d1, d2;
    private int d1Len, d2Len;
    private int d1Idx = 0, d2Idx = 0;
    private float lp1 = 0f, lp2 = 0f;
    private float hp1Prev = 0f, hp1Out = 0f;
    private float decay = 0.65f, boing = 0.7f, tone = 0.45f, mix = 0.4f;

    @Override
    public void init(int sr) {
        float srScale = sr / 44100f;
        aps = new float[AP_LENS.length][];
        apIdx = new int[AP_LENS.length];
        for (int a = 0; a < AP_LENS.length; a++) {
            int L = (int) Math.ceil(AP_LENS[a] * srScale);
            aps[a] = new float[L];
            apIdx[a] = 0;
        }
        d1Len = (int) Math.ceil(sr * 0.13);
        d2Len = (int) Math.ceil(sr * 0.16);
        d1 = new float[d1Len];
        d2 = new float[d2Len];
        d1Idx = d2Idx = 0;
        lp1 = lp2 = 0f;
        hp1Prev = hp1Out = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "decay", "boing", "tone", "mix" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) {
        return "decay".equals(n) ? 0.92f : 1f;
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "decay": return 0.65f;
            case "boing": return 0.7f;
            case "tone":  return 0.45f;
            case "mix":   return 0.4f;
            default:      return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "decay": return "Decay";
            case "boing": return "Boing";
            case "tone":  return "Tone";
            case "mix":   return "Mix";
            default:      return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "decay": decay = v; break;
            case "boing": boing = v; break;
            case "tone":  tone = v; break;
            case "mix":   mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float apG = 0.3f + 0.5f * boing;
        final float lpA = 0.05f + 0.85f * tone;
        final float hpA = 0.99f;
        final float decayLocal = decay;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final int numAps = AP_LENS.length;
        final float[][] apsLocal = aps;
        final int[] apIdxLocal = apIdx;
        final float[] d1Local = d1, d2Local = d2;
        final int d1L = d1Len, d2L = d2Len;
        int d1i = d1Idx, d2i = d2Idx;
        float l1 = lp1, l2 = lp2;
        float hpP = hp1Prev, hpO = hp1Out;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            hpO = hpA * (hpO + x - hpP);
            hpP = x;
            float hp = hpO;
            int d1r = d1i - (d1L - 1); if (d1r < 0) d1r += d1L;
            int d2r = d2i - (d2L - 1); if (d2r < 0) d2r += d2L;
            float s1Out = d1Local[d1r];
            float s2Out = d2Local[d2r];
            float v = hp + s1Out * decayLocal * 0.7f + s2Out * decayLocal * 0.3f;
            for (int a = 0; a < numAps; a++) {
                float[] ab = apsLocal[a];
                int L = ab.length;
                int idx = apIdxLocal[a];
                int r = idx - (L - 1); if (r < 0) r += L;
                float del = ab[r];
                float inAp = v + del * apG;
                ab[idx] = inAp;
                v = del - inAp * apG;
                idx++; if (idx >= L) idx = 0;
                apIdxLocal[a] = idx;
            }
            l1 = l1 + lpA * (v - l1);
            l2 = l2 + lpA * (v - l2);
            d1Local[d1i] = l1 + s2Out * decayLocal * 0.2f;
            d2Local[d2i] = l2 + s1Out * decayLocal * 0.2f;
            d1i++; if (d1i >= d1L) d1i = 0;
            d2i++; if (d2i >= d2L) d2i = 0;
            float wet = (s1Out + s2Out) * 0.5f;
            output[i] = x * dry + wet * mixLocal;
        }
        d1Idx = d1i; d2Idx = d2i;
        lp1 = l1; lp2 = l2;
        hp1Prev = hpP; hp1Out = hpO;
    }
}
