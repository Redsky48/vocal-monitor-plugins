package com.vocalmonitor.plugin.examples;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

/**
 * Convolver (native) — 256-tap FIR convolution, identical DSP to the
 * convolver-js sibling, but written in plain Java so the inner loop runs
 * as JVM bytecode at native speed.
 *
 * Compiled to DEX with:
 *
 *     javac --release 8 -cp <interface.jar> Convolver.java
 *     d8 com/vocalmonitor/plugin/examples/Convolver.class --output dist/
 *
 * The produced classes.dex is what the host downloads at install time.
 */
public final class Convolver implements VocalMonitorNativePlugin {

    private static final int TAPS = 256;

    private final float[] ir = new float[TAPS];
    private final float[] history = new float[TAPS];
    private int historyIdx = 0;
    private float mix = 0.4f;

    @Override
    public void init(int sampleRate) {
        // Numerical-Recipes LCG — matches convolver-js exactly so the
        // two siblings produce the same impulse response. We keep the
        // running state in a long because the intermediate product
        // (seed * 1664525) needs 32 + 21 = 53 bits before the modulus.
        long seed = 1;
        float sum = 0;
        for (int i = 0; i < TAPS; i++) {
            seed = (seed * 1664525L + 1013904223L) % 2147483648L;
            float noise = ((float) seed / 2147483648f) * 2f - 1f;
            ir[i] = noise * (float) Math.exp(-i / 80.0);
            sum += Math.abs(ir[i]);
        }
        if (sum > 0f) for (int i = 0; i < TAPS; i++) ir[i] /= sum;
        for (int i = 0; i < TAPS; i++) history[i] = 0f;
        historyIdx = 0;
    }

    @Override public String[] parameterNames() { return new String[] {"mix"}; }
    @Override public float parameterMin(String name) { return 0f; }
    @Override public float parameterMax(String name) { return 1f; }
    @Override public float parameterDefault(String name) { return 0.4f; }
    @Override public String parameterLabel(String name) { return "Mix"; }

    @Override
    public void setParameter(String name, float value) {
        if ("mix".equals(name)) mix = value;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float[] ir = this.ir;
        final float[] history = this.history;
        final int taps = TAPS;
        int hIdx = this.historyIdx;
        final float m = mix;
        final float dry = 1f - m;
        final int n = input.length;

        for (int i = 0; i < n; i++) {
            history[hIdx] = input[i];
            float y = 0f;
            int idx = hIdx;
            for (int k = 0; k < taps; k++) {
                y += ir[k] * history[idx];
                idx--;
                if (idx < 0) idx += taps;
            }
            hIdx = hIdx + 1;
            if (hIdx >= taps) hIdx = 0;
            output[i] = input[i] * dry + y * m;
        }

        this.historyIdx = hIdx;
    }
}
