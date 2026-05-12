package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Bit Crusher (native port) — quantises sample depth + holds each sample
// for N hops. Chiptune crunch.
public final class Bitcrusher implements VocalMonitorNativePlugin {
    private int holdCounter = 0;
    private float held = 0f;
    private float bits = 8f, rate = 1f;

    @Override public void init(int sr) { holdCounter = 0; held = 0f; }
    @Override public String[] parameterNames() { return new String[] { "bits", "rate" }; }
    @Override public float parameterMin(String n) { return 1f; }
    @Override public float parameterMax(String n) { return "bits".equals(n) ? 16f : 64f; }
    @Override public float parameterDefault(String n) { return "bits".equals(n) ? 8f : 1f; }
    @Override public String parameterLabel(String n) { return "bits".equals(n) ? "Bits" : "Hold (smp)"; }
    @Override public void setParameter(String n, float v) {
        if ("bits".equals(n)) bits = v;
        else if ("rate".equals(n)) rate = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        final int bitsInt = Math.max(1, (int) Math.floor(bits));
        final int hold = Math.max(1, (int) Math.floor(rate));
        final float steps = (float) Math.pow(2.0, bitsInt - 1);
        final float invSteps = 1f / steps;
        int hc = holdCounter;
        float h = held;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            if (hc <= 0) {
                h = Math.round(input[i] * steps) * invSteps;
                hc = hold;
            }
            output[i] = h;
            hc--;
        }
        holdCounter = hc;
        held = h;
    }
}
