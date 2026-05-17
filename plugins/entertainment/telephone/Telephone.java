package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Telephone (native port) — POTS-handset bandpass (~300 Hz – 3.4 kHz)
// plus a soft codec crunch. Classic "caller on the other end" effect.
public final class Telephone implements VocalMonitorNativePlugin {
    private float hpPrev = 0f, hpOut = 0f, lpOut = 0f;
    private int sampleRate = 44100;
    private float lowCut = 300f, highCut = 3400f, crunch = 0.3f, mix = 1f;

    @Override
    public void init(int sr) { this.sampleRate = sr; hpPrev = hpOut = lpOut = 0f; }

    @Override public String[] parameterNames() { return new String[] { "lowCut", "highCut", "crunch", "mix" }; }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "lowCut":  return 100f;
            case "highCut": return 1500f;
            default:        return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "lowCut":  return 1000f;
            case "highCut": return 8000f;
            default:        return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "lowCut":  return 300f;
            case "highCut": return 3400f;
            case "crunch":  return 0.3f;
            case "mix":     return 1f;
            default:        return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "lowCut":  return "Low Hz";
            case "highCut": return "High Hz";
            case "crunch":  return "Crunch";
            case "mix":     return "Mix";
            default:        return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "lowCut":  lowCut = v; break;
            case "highCut": highCut = v; break;
            case "crunch":  crunch = v; break;
            case "mix":     mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float hc = Math.max(lowCut + 100f, highCut);
        final float dt = 1f / sampleRate;
        final float hpRc = (float) (1.0 / (2.0 * Math.PI * lowCut));
        final float hpA = hpRc / (hpRc + dt);
        final float lpRc = (float) (1.0 / (2.0 * Math.PI * hc));
        final float lpA = dt / (lpRc + dt);
        final float k = 1f + crunch * 12f;
        final float kNorm = 1f / (float) Math.tanh(k);
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        float hpP = hpPrev, hpO = hpOut, lpO = lpOut;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            hpO = hpA * (hpO + x - hpP);
            hpP = x;
            lpO = lpO + lpA * (hpO - lpO);
            float wet = (float) Math.tanh(lpO * k) * kNorm;
            output[i] = x * dry + wet * mixLocal;
        }
        hpPrev = hpP; hpOut = hpO; lpOut = lpO;
    }
}
