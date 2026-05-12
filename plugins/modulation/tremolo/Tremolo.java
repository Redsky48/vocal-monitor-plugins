package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Tremolo (native port) — amplitude modulated by a sine LFO. The LFO
// range is 1-depth .. 1 so the wave never inverts polarity — classic
// surf-rock / vintage-amp wobble that preserves formants.
public final class Tremolo implements VocalMonitorNativePlugin {
    private float phase = 0f;
    private int sampleRate = 44100;
    private float rate = 5f, depth = 0.6f;

    @Override public void init(int sr) { this.sampleRate = sr; phase = 0f; }
    @Override public String[] parameterNames() { return new String[] { "rate", "depth" }; }
    @Override public float parameterMin(String n) { return "rate".equals(n) ? 0.1f : 0f; }
    @Override public float parameterMax(String n) { return "rate".equals(n) ? 20f : 1f; }
    @Override public float parameterDefault(String n) { return "rate".equals(n) ? 5f : 0.6f; }
    @Override public String parameterLabel(String n) { return "rate".equals(n) ? "Rate (Hz)" : "Depth"; }
    @Override public void setParameter(String n, float v) {
        if ("rate".equals(n)) rate = v;
        else if ("depth".equals(n)) depth = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float phaseInc = (float) (2.0 * Math.PI * rate / sampleRate);
        final float twoPi = (float) (2.0 * Math.PI);
        final float halfDepth = depth * 0.5f;
        float ph = phase;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float lfo = 1f - halfDepth * (1f - (float) Math.cos(ph));
            output[i] = input[i] * lfo;
            ph += phaseInc;
            if (ph > twoPi) ph -= twoPi;
        }
        phase = ph;
    }
}
