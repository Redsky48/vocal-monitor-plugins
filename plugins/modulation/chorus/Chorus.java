package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Chorus (native port) — LFO-modulated short delay (~18 ms base) blended
// with dry. Thickens a single voice into a small ensemble. 80s pad sheen
// at slow rates, watery shimmer at higher ones.
public final class Chorus implements VocalMonitorNativePlugin {
    private float[] buf;
    private int bufLen;
    private int writeIdx;
    private float phase;
    private int sampleRate = 44100;
    private float rate = 0.8f, depth = 6f, mix = 0.45f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = 20000;
        buf = new float[bufLen];
        writeIdx = 0;
        phase = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "rate", "depth", "mix" }; }
    @Override public float parameterMin(String n) { return "rate".equals(n) ? 0.1f : 0f; }
    @Override public float parameterMax(String n) {
        if ("rate".equals(n)) return 5f;
        if ("depth".equals(n)) return 15f;
        return 1f;
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "rate":  return 0.8f;
            case "depth": return 6f;
            case "mix":   return 0.45f;
            default:      return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "rate":  return "Rate (Hz)";
            case "depth": return "Depth (ms)";
            case "mix":   return "Mix";
            default:      return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "rate":  rate = v; break;
            case "depth": depth = v; break;
            case "mix":   mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float baseSamp = sampleRate * 0.018f;
        final float depthSamp = sampleRate * depth * 0.001f;
        final float phaseInc = (float) (2.0 * Math.PI * rate / sampleRate);
        final float twoPi = (float) (2.0 * Math.PI);
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float[] b = buf;
        final int bL = bufLen;
        int w = writeIdx;
        float ph = phase;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float lfo = (float) Math.sin(ph);
            float read = w - (baseSamp + depthSamp * lfo);
            while (read < 0) read += bL;
            int i0 = (int) read;
            float frac = read - i0;
            int i1 = i0 + 1; if (i1 >= bL) i1 = 0;
            float wet = b[i0] * (1f - frac) + b[i1] * frac;
            b[w] = input[i];
            w++; if (w >= bL) w = 0;
            output[i] = input[i] * dry + wet * mixLocal;
            ph += phaseInc;
            if (ph > twoPi) ph -= twoPi;
        }
        writeIdx = w;
        phase = ph;
    }
}
