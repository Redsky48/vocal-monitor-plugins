package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Vibrato (native port) — pure pitch wobble: LFO-modulated delay tap,
// no dry blend. Unlike chorus this doesn't create comb-filter notches,
// just the seasick wobble itself.
public final class Vibrato implements VocalMonitorNativePlugin {
    private float[] buf;
    private int bufLen;
    private int writeIdx;
    private float phase;
    private int sampleRate = 44100;
    private float rate = 5f, depth = 2f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = 4000;
        buf = new float[bufLen];
        writeIdx = 0;
        phase = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "rate", "depth" }; }
    @Override public float parameterMin(String n) { return "rate".equals(n) ? 0.5f : 0f; }
    @Override public float parameterMax(String n) { return "rate".equals(n) ? 12f : 8f; }
    @Override public float parameterDefault(String n) { return "rate".equals(n) ? 5f : 2f; }
    @Override public String parameterLabel(String n) { return "rate".equals(n) ? "Rate (Hz)" : "Depth (ms)"; }
    @Override public void setParameter(String n, float v) {
        if ("rate".equals(n)) rate = v;
        else if ("depth".equals(n)) depth = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float baseSamp = sampleRate * 0.005f;
        final float depthSamp = sampleRate * depth * 0.001f;
        final float phaseInc = (float) (2.0 * Math.PI * rate / sampleRate);
        final float twoPi = (float) (2.0 * Math.PI);
        final float[] b = buf;
        final int bL = bufLen;
        int w = writeIdx;
        float ph = phase;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float lfo = 0.5f + 0.5f * (float) Math.sin(ph);
            float read = w - (baseSamp + depthSamp * lfo);
            while (read < 0) read += bL;
            int i0 = (int) read;
            float frac = read - i0;
            int i1 = i0 + 1; if (i1 >= bL) i1 = 0;
            output[i] = b[i0] * (1f - frac) + b[i1] * frac;
            b[w] = input[i];
            w++; if (w >= bL) w = 0;
            ph += phaseInc;
            if (ph > twoPi) ph -= twoPi;
        }
        writeIdx = w;
        phase = ph;
    }
}
