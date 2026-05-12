package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Octaver (native port) — full-wave rectification doubles the perceived
// pitch (one octave up). The rectified signal is DC-blocked by a leaky
// integrator before being mixed back under the dry voice.
public final class Octaver implements VocalMonitorNativePlugin {
    private float prevX = 0f;
    private float prevY = 0f;
    private float octave = 0.5f, dry = 0.7f;

    @Override public void init(int sr) { prevX = 0f; prevY = 0f; }
    @Override public String[] parameterNames() { return new String[] { "octave", "dry" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return "octave".equals(n) ? 0.5f : 0.7f; }
    @Override public String parameterLabel(String n) { return "octave".equals(n) ? "Octave Up" : "Dry"; }
    @Override public void setParameter(String n, float v) {
        if ("octave".equals(n)) octave = v;
        else if ("dry".equals(n)) dry = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float R = 0.995f;
        final float octGain = octave * 2f;
        final float dryLocal = dry;
        float pX = prevX, pY = prevY;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float rect = x >= 0 ? x : -x;
            float y = rect - pX + R * pY;
            pX = rect; pY = y;
            output[i] = x * dryLocal + y * octGain;
        }
        prevX = pX; prevY = pY;
    }
}
