package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Ring Mod (native port) — multiplies input by a sine carrier. Low
// carrier = tremolo; speech-band = Dalek robot voice; >1 kHz = bell-like
// clangorous inharmonic texture.
public final class RingMod implements VocalMonitorNativePlugin {
    private float phase = 0f;
    private int sampleRate = 44100;
    private float frequency = 220f, mix = 1f;

    @Override public void init(int sr) { this.sampleRate = sr; phase = 0f; }
    @Override public String[] parameterNames() { return new String[] { "frequency", "mix" }; }
    @Override public float parameterMin(String n) { return "frequency".equals(n) ? 1f : 0f; }
    @Override public float parameterMax(String n) { return "frequency".equals(n) ? 2000f : 1f; }
    @Override public float parameterDefault(String n) { return "frequency".equals(n) ? 220f : 1f; }
    @Override public String parameterLabel(String n) { return "frequency".equals(n) ? "Carrier (Hz)" : "Mix"; }
    @Override public void setParameter(String n, float v) {
        if ("frequency".equals(n)) frequency = v;
        else if ("mix".equals(n)) mix = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float phaseInc = (float) (2.0 * Math.PI * frequency / sampleRate);
        final float twoPi = (float) (2.0 * Math.PI);
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        float ph = phase;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float mod = (float) Math.sin(ph);
            float wet = input[i] * mod;
            output[i] = input[i] * dry + wet * mixLocal;
            ph += phaseInc;
            if (ph > twoPi) ph -= twoPi;
        }
        phase = ph;
    }
}
