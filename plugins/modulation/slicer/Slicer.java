package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Slicer — 8-step rhythmic gate. Shorter pattern than the 16-step trance
// gate, plus a duty-cycle knob that morphs each "on" step between a
// sharp 1/8 chop and a near-full hold. Use it for rhythmic gating on
// pads, vocals or drones.

public final class Slicer implements VocalMonitorNativePlugin {

    private static final int STEPS = 8;
    private static final int[][] PATTERNS = {
        { 1, 1, 1, 1, 1, 1, 1, 1 },   // all on (use duty cycle to chop)
        { 1, 0, 1, 0, 1, 0, 1, 0 },   // straight
        { 1, 1, 0, 1, 0, 1, 1, 0 },   // syncopated
        { 1, 0, 0, 1, 0, 1, 0, 0 },   // sparse
    };

    private int sampleCounter = 0;
    private int stepIdx = 0;
    private float currentGain = 1f;
    private int sampleRate = 44100;

    private float rate = 6f;
    private float pattern = 0f;
    private float duty = 0.5f;
    private float smoothness = 0.15f;
    private float mix = 1f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        sampleCounter = 0; stepIdx = 0; currentGain = 1f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "rate", "pattern", "duty", "smoothness", "mix" };
    }
    @Override public float parameterMin(String n) {
        if ("rate".equals(n)) return 0.5f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "rate":    return 30f;
            case "pattern": return 3f;
            default:        return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "rate":       return 6f;
            case "duty":       return 0.5f;
            case "smoothness": return 0.15f;
            case "mix":        return 1f;
            default:           return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "rate":       return "Rate (Hz)";
            case "pattern":    return "Pattern";
            case "duty":       return "Duty";
            case "smoothness": return "Smoothness";
            case "mix":        return "Mix";
            default:           return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "rate":       rate = v; break;
            case "pattern":    pattern = v; break;
            case "duty":       duty = v; break;
            case "smoothness": smoothness = v; break;
            case "mix":        mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final int stepLen = Math.max(1, (int) (sampleRate / rate));
        final int patIdx = Math.max(0, Math.min(PATTERNS.length - 1, (int) pattern));
        final int[] pat = PATTERNS[patIdx];
        final int onSamples = Math.max(1, (int) (stepLen * duty));
        final float smoothCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * smoothness * 0.03));
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        int counter = sampleCounter;
        int idx = stepIdx;
        float g = currentGain;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            if (counter <= 0) {
                counter = stepLen;
                idx++;
                if (idx >= STEPS) idx = 0;
            }
            // Step phase from 0 (start) to stepLen (next).
            int posInStep = stepLen - counter;
            // Gate on for first onSamples of an "on" step.
            float target = (pat[idx] == 1 && posInStep < onSamples) ? 1f : 0f;
            g = g + smoothCoef * (target - g);
            counter--;
            output[i] = input[i] * (dry + g * mixLocal);
        }
        sampleCounter = counter; stepIdx = idx; currentGain = g;
    }
}
