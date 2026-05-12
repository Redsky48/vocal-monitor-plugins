package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Saturator (native port) — soft-clip tube saturation via tanh. Drive
// pushes the input deeper into the curve; mix blends back the dry path
// so even extreme drive can sit in a mix.
public final class Saturator implements VocalMonitorNativePlugin {
    private float drive = 0.5f, mix = 1f;

    @Override public void init(int sr) {}
    @Override public String[] parameterNames() { return new String[] { "drive", "mix" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return "drive".equals(n) ? 0.5f : 1f; }
    @Override public String parameterLabel(String n) { return "drive".equals(n) ? "Drive" : "Mix"; }
    @Override public void setParameter(String n, float v) {
        if ("drive".equals(n)) drive = v;
        else if ("mix".equals(n)) mix = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float k = 1f + drive * 8f;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float wet = (float) Math.tanh(input[i] * k);
            output[i] = input[i] * dry + wet * mixLocal;
        }
    }
}
