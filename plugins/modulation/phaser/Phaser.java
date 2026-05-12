package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Phaser (native port) — 4-stage allpass cascade swept by a sine LFO and
// fed back into itself. Each allpass introduces a 90° phase shift at
// cutoff; mixed back with the dry signal that produces the deep
// notch-and-sweep characteristic of MXR / Small Stone phasers. Feedback
// sharpens the notches into resonant peaks.
public final class Phaser implements VocalMonitorNativePlugin {
    private final float[] s = new float[4];
    private float fbState = 0f;
    private float phase = 0f;
    private int sampleRate = 44100;
    private float rate = 0.4f, depth = 0.8f, feedback = 0.5f, mix = 0.5f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 4; i++) s[i] = 0f;
        fbState = 0f;
        phase = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "rate", "depth", "feedback", "mix" }; }
    @Override public float parameterMin(String n) {
        if ("rate".equals(n)) return 0.05f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "rate":     return 4f;
            case "feedback": return 0.95f;
            default:         return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "rate":     return 0.4f;
            case "depth":    return 0.8f;
            case "feedback": return 0.5f;
            case "mix":      return 0.5f;
            default:         return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "rate":     return "Rate (Hz)";
            case "depth":    return "Depth";
            case "feedback": return "Feedback";
            case "mix":      return "Mix";
            default:         return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "rate":     rate = v; break;
            case "depth":    depth = v; break;
            case "feedback": feedback = v; break;
            case "mix":      mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float phaseInc = (float) (2.0 * Math.PI * rate / sampleRate);
        final float twoPi = (float) (2.0 * Math.PI);
        final float fb = feedback;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float depthLocal = depth;
        final float invSr = 1f / sampleRate;
        float ph = phase;
        float s0 = s[0], s1 = s[1], s2 = s[2], s3 = s[3];
        float fbS = fbState;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float lfo = 0.5f + 0.5f * (float) Math.sin(ph);
            float cutoff = 250f + depthLocal * 2250f * lfo;
            float w = (float) Math.tan(Math.PI * cutoff * invSr);
            float a = (1f - w) / (1f + w);

            float x = input[i] + fbS * fb;
            float y;
            y = a * x + s0; s0 = x - a * y; x = y;
            y = a * x + s1; s1 = x - a * y; x = y;
            y = a * x + s2; s2 = x - a * y; x = y;
            y = a * x + s3; s3 = x - a * y; x = y;
            fbS = x;
            output[i] = input[i] * dry + x * mixLocal;

            ph += phaseInc;
            if (ph > twoPi) ph -= twoPi;
        }
        s[0] = s0; s[1] = s1; s[2] = s2; s[3] = s3;
        fbState = fbS;
        phase = ph;
    }
}
