package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Trance Gate — 16-step on/off gate, the trademark "stuttered pad"
// rhythm from late-90s trance. Four hardcoded patterns: dotted-eighth,
// straight sixteenth, syncopated and 1+3 emphasis. Smoothness controls
// the slew between on and off so you can choose between hard chops and
// breathy fade-ins.

public final class TranceGate implements VocalMonitorNativePlugin {

    private static final int STEPS = 16;
    private static final int[][] PATTERNS = {
        { 1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 1, 0, 1, 0, 1 },   // syncopated
        { 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0 },   // straight 8th
        { 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0 },   // 2x dotted
        { 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1 },   // 1+3 emphasis
    };

    private int sampleCounter = 0;
    private int stepIdx = 0;
    private float currentGain = 1f;
    private int sampleRate = 44100;

    private float rate = 4f;       // steps per second
    private float pattern = 0f;    // 0..3
    private float smoothness = 0.2f;
    private float depth = 1f;
    private float mix = 1f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        sampleCounter = 0;
        stepIdx = 0;
        currentGain = 1f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "rate", "pattern", "smoothness", "depth", "mix" };
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
            case "rate":       return 4f;
            case "smoothness": return 0.2f;
            case "depth":      return 1f;
            case "mix":        return 1f;
            default:           return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "rate":       return "Rate (Hz)";
            case "pattern":    return "Pattern";
            case "smoothness": return "Smoothness";
            case "depth":      return "Depth";
            case "mix":        return "Mix";
            default:           return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "rate":       rate = v; break;
            case "pattern":    pattern = v; break;
            case "smoothness": smoothness = v; break;
            case "depth":      depth = v; break;
            case "mix":        mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final int stepLen = Math.max(1, (int) (sampleRate / rate));
        final int patIdx = Math.max(0, Math.min(PATTERNS.length - 1, (int) pattern));
        final int[] pat = PATTERNS[patIdx];
        final float depthLocal = depth;
        final float floor = 1f - depthLocal;
        final float smoothCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * smoothness * 0.05));
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
            counter--;
            float target = pat[idx] == 1 ? 1f : floor;
            g = g + smoothCoef * (target - g);
            output[i] = input[i] * (dry + g * mixLocal);
        }
        sampleCounter = counter;
        stepIdx = idx;
        currentGain = g;
    }
}
