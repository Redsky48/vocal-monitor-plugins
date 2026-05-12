package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Gated Reverb (native port) — 6-comb plate reverb followed by an
// envelope-triggered hard gate with hold. The Phil Collins / Hugh
// Padgham 80s drum sound on a vocal.
public final class GatedReverb implements VocalMonitorNativePlugin {
    private static final int[] COMB_BASE = { 1687, 1601, 2053, 2251, 1499, 1789 };
    private static final int[] AP_BASE = { 347, 113, 41 };
    private float[][] combs = new float[6][];
    private final int[] combLen = new int[6];
    private final int[] combIdx = new int[6];
    private final float[] combLp = new float[6];
    private float[][] aps = new float[3][];
    private final int[] apLen = new int[3];
    private final int[] apIdx = new int[3];
    private float env = 0f, gateGain = 0f;
    private int holdSamples = 0;
    private int sampleRate = 44100;
    private float decay = 0.85f, threshold = 0.03f, hold = 220f, release = 30f, mix = 0.5f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        float srScale = sr / 44100f;
        for (int c = 0; c < 6; c++) {
            int L = (int) Math.ceil(COMB_BASE[c] * srScale);
            combs[c] = new float[L];
            combLen[c] = L;
            combIdx[c] = 0;
            combLp[c] = 0f;
        }
        for (int a = 0; a < 3; a++) {
            int L = (int) Math.ceil(AP_BASE[a] * srScale);
            aps[a] = new float[L];
            apLen[a] = L;
            apIdx[a] = 0;
        }
        env = gateGain = 0f;
        holdSamples = 0;
    }

    @Override public String[] parameterNames() {
        return new String[] { "decay", "threshold", "hold", "release", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "decay":     return 0.5f;
            case "threshold": return 0.001f;
            case "hold":      return 30f;
            case "release":   return 1f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "decay":     return 0.97f;
            case "threshold": return 0.3f;
            case "hold":      return 800f;
            case "release":   return 300f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "decay":     return 0.85f;
            case "threshold": return 0.03f;
            case "hold":      return 220f;
            case "release":   return 30f;
            case "mix":       return 0.5f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "decay":     return "Decay";
            case "threshold": return "Threshold";
            case "hold":      return "Hold (ms)";
            case "release":   return "Rel (ms)";
            case "mix":       return "Mix";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "decay":     decay = v; break;
            case "threshold": threshold = v; break;
            case "hold":      hold = v; break;
            case "release":   release = v; break;
            case "mix":       mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float envAttack = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));
        final float envRelease = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.05));
        final int holdN = (int) Math.floor(hold * sampleRate / 1000f);
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        final float apG = 0.5f;
        final float decayLocal = decay;
        final float threshLocal = threshold;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final int[] cls = new int[6];
        for (int c = 0; c < 6; c++) cls[c] = combLen[c] - 2;
        float e = env, gg = gateGain;
        int hs = holdSamples;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float rect = x < 0 ? -x : x;
            float coef = rect > e ? envAttack : envRelease;
            e = e + coef * (rect - e);
            float target;
            if (e > threshLocal) { target = 1f; hs = holdN; }
            else if (hs > 0) { target = 1f; hs--; }
            else target = 0f;
            gg = gg + relCoef * (target - gg);

            float rvIn = x;
            float sum = 0f;
            for (int c = 0; c < 6; c++) {
                float[] b = combs[c];
                int idx = combIdx[c];
                int r = idx - cls[c]; if (r < 0) r += combLen[c];
                float d = b[r];
                combLp[c] = combLp[c] + 0.35f * (d - combLp[c]);
                b[idx] = rvIn + combLp[c] * decayLocal;
                idx++; if (idx >= combLen[c]) idx = 0;
                combIdx[c] = idx;
                sum += combLp[c];
            }
            float v = sum * (1f / 6f);
            for (int a = 0; a < 3; a++) {
                float[] ab = aps[a];
                int aIdx = apIdx[a];
                int aL = apLen[a] - 2;
                int ar = aIdx - aL; if (ar < 0) ar += apLen[a];
                float ad = ab[ar];
                float ai = v + ad * apG;
                ab[aIdx] = ai;
                v = ad - ai * apG;
                aIdx++; if (aIdx >= apLen[a]) aIdx = 0;
                apIdx[a] = aIdx;
            }
            float wet = v * gg;
            output[i] = x * dry + wet * mixLocal;
        }
        env = e; gateGain = gg; holdSamples = hs;
    }
}
