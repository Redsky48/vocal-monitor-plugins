package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Flanger (native port) — short variable delay with feedback. Comb
// notches sweep through the spectrum to produce the trademark jet-plane
// whoosh; high feedback turns notches into resonant peaks.
public final class Flanger implements VocalMonitorNativePlugin {
    private float[] buf;
    private int bufLen;
    private int writeIdx;
    private float phase;
    private int sampleRate = 44100;
    private float rate = 0.3f, depth = 3f, feedback = 0.6f, mix = 0.5f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = 4000;
        buf = new float[bufLen];
        writeIdx = 0;
        phase = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "rate", "depth", "feedback", "mix" }; }
    @Override public float parameterMin(String n) {
        if ("rate".equals(n)) return 0.05f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "rate":     return 5f;
            case "depth":    return 8f;
            case "feedback": return 0.95f;
            default:         return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "rate":     return 0.3f;
            case "depth":    return 3f;
            case "feedback": return 0.6f;
            case "mix":      return 0.5f;
            default:         return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "rate":     return "Rate (Hz)";
            case "depth":    return "Depth (ms)";
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
        final float baseSamp = sampleRate * 0.0005f;
        final float depthSamp = sampleRate * depth * 0.001f;
        final float phaseInc = (float) (2.0 * Math.PI * rate / sampleRate);
        final float twoPi = (float) (2.0 * Math.PI);
        final float fb = feedback;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
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
            float wet = b[i0] * (1f - frac) + b[i1] * frac;
            b[w] = input[i] + wet * fb;
            w++; if (w >= bL) w = 0;
            output[i] = input[i] * dry + wet * mixLocal;
            ph += phaseInc;
            if (ph > twoPi) ph -= twoPi;
        }
        writeIdx = w;
        phase = ph;
    }
}
