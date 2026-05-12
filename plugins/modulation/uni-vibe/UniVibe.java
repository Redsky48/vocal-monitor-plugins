package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Uni-Vibe — Shin-Ei Univibe-style phaser. Unlike a textbook 4-stage
// phaser where every all-pass shares one LFO, here each stage gets its
// own offset-tuned LFO (90° apart), and each stage's cutoff sweeps a
// different frequency range. The result is the asymmetric, vocal,
// "rotary-speaker-with-bite" character Hendrix and Trower made famous.

public final class UniVibe implements VocalMonitorNativePlugin {

    private static final int STAGES = 4;
    private static final float[] CENTRE = { 100f, 400f, 1300f, 2500f };
    private static final float[] PHASE_OFFSET = { 0f, 1.5708f, 3.1416f, 4.7124f };

    private final float[] s = new float[STAGES];
    private float fbState = 0f;
    private final float[] phases = new float[STAGES];
    private int sampleRate = 44100;

    private float rate = 0.6f;
    private float depth = 0.7f;
    private float intensity = 0.5f;
    private float mix = 0.6f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < STAGES; i++) {
            s[i] = 0f;
            phases[i] = PHASE_OFFSET[i];
        }
        fbState = 0f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "rate", "depth", "intensity", "mix" };
    }
    @Override public float parameterMin(String n) {
        if ("rate".equals(n)) return 0.05f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        return "rate".equals(n) ? 6f : 1f;
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "rate":      return 0.6f;
            case "depth":     return 0.7f;
            case "intensity": return 0.5f;
            case "mix":       return 0.6f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "rate":      return "Rate (Hz)";
            case "depth":     return "Depth";
            case "intensity": return "Intensity";
            case "mix":       return "Mix";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "rate":      rate = v; break;
            case "depth":     depth = v; break;
            case "intensity": intensity = v; break;
            case "mix":       mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float phaseInc = (float) (2.0 * Math.PI * rate / sampleRate);
        final float twoPi = (float) (2.0 * Math.PI);
        final float depthLocal = depth;
        final float fb = intensity * 0.85f;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float piOverSr = (float) (Math.PI / sampleRate);
        float fbS = fbState;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float xIn = input[i] + fbS * fb;
            float v = xIn;
            for (int st = 0; st < STAGES; st++) {
                float lfo = 0.5f + 0.5f * (float) Math.sin(phases[st]);
                // Per-stage cutoff sweeps around its centre — ±octave
                // controlled by depth.
                float octaveSwing = (lfo - 0.5f) * 2f * depthLocal;
                float cutoff = CENTRE[st] * (float) Math.pow(2.0, octaveSwing);
                if (cutoff < 30f) cutoff = 30f;
                if (cutoff > sampleRate * 0.45f) cutoff = sampleRate * 0.45f;
                float w = (float) Math.tan(piOverSr * cutoff);
                float a = (1f - w) / (1f + w);
                float y = a * v + s[st];
                s[st] = v - a * y;
                v = y;
                phases[st] += phaseInc;
                if (phases[st] > twoPi) phases[st] -= twoPi;
            }
            fbS = v;
            output[i] = input[i] * dry + v * mixLocal;
        }
        fbState = fbS;
    }
}
