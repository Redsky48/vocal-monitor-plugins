package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Transient Shaper (native port) — independent attack/sustain via the
// difference of fast and slow envelopes. SPL Transient Designer in
// spirit — fatten snares, soften plosives, add bite to vocals.
public final class TransientShaper implements VocalMonitorNativePlugin {
    private float fastEnv = 0f, slowEnv = 0f;
    private int sampleRate = 44100;
    private float attack = 0f, sustain = 0f, mix = 1f;

    @Override
    public void init(int sr) { this.sampleRate = sr; fastEnv = 0f; slowEnv = 0f; }

    @Override public String[] parameterNames() { return new String[] { "attack", "sustain", "mix" }; }
    @Override public float parameterMin(String n) { return "mix".equals(n) ? 0f : -1f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "attack":  return 0f;
            case "sustain": return 0f;
            case "mix":     return 1f;
            default:        return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "attack":  return "Attack";
            case "sustain": return "Sustain";
            case "mix":     return "Mix";
            default:        return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "attack":  attack = v; break;
            case "sustain": sustain = v; break;
            case "mix":     mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * 0.002));
        final float fastRelCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * 0.030));
        final float slowRelCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * 0.200));
        final float attGain = attack >= 0 ? 1f + attack * 3f : 1f + attack * 0.95f;
        final float susGain = sustain >= 0 ? 1f + sustain * 3f : 1f + sustain * 0.95f;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        float fE = fastEnv, sE = slowEnv;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float rect = x < 0 ? -x : x;
            float fc = rect > fE ? attCoef : fastRelCoef;
            fE = fE + fc * (rect - fE);
            float sc = rect > sE ? attCoef : slowRelCoef;
            sE = sE + sc * (rect - sE);
            float trans = fE - sE;
            if (trans < 0f) trans = 0f;
            float origAmp = sE > 1e-6f ? sE : 1e-6f;
            float transMul = 1f + (attGain - 1f) * (trans / (origAmp + trans));
            float w = trans / (trans + origAmp);
            float g = susGain * (1f - w) + susGain * transMul * w;
            float wet = x * g;
            output[i] = x * dry + wet * mixLocal;
        }
        fastEnv = fE; slowEnv = sE;
    }
}
