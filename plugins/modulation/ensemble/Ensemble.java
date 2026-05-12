package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Ensemble — six chorus voices, each pulling from a shared delay buffer
// at a slightly different base time and modulated by its own LFO at a
// slightly different rate. The classic Solina String Ensemble / Eminent
// 310 "string section" sound: a single note sung into the input comes
// out as a thick, undulating mass of slightly-out-of-tune copies. Goes
// from subtle Roland Juno chorus at low depth to seasick wow-and-flutter
// at high depth.

public final class Ensemble implements VocalMonitorNativePlugin {

    private static final int VOICES = 6;
    private static final float[] PHASE_OFFSETS = { 0f, 1.047f, 2.094f, 3.141f, 4.188f, 5.235f };
    private static final float[] RATE_MULTIPLIERS = { 1.0f, 0.83f, 1.21f, 0.71f, 1.39f, 0.94f };

    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    private final float[] phases = new float[VOICES];
    private int sampleRate = 44100;

    private float rate = 0.6f;
    private float depth = 5f;
    private float mix = 0.5f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = sr / 20;  // 50 ms — covers max 20 ms delay + 25 ms mod
        buf = new float[bufLen];
        writeIdx = 0;
        for (int v = 0; v < VOICES; v++) phases[v] = PHASE_OFFSETS[v];
    }

    @Override public String[] parameterNames() { return new String[] { "rate", "depth", "mix" }; }
    @Override public float parameterMin(String n) {
        if ("rate".equals(n)) return 0.1f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "rate":  return 5f;
            case "depth": return 12f;
            default:      return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "rate":  return 0.6f;
            case "depth": return 5f;
            case "mix":   return 0.5f;
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
        final float baseSamp = sampleRate * 0.012f;
        final float depthSamp = sampleRate * depth * 0.001f;
        final float twoPiOverSr = (float) (2.0 * Math.PI / sampleRate);
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float voiceScale = 1f / VOICES;
        final float twoPi = (float) (2.0 * Math.PI);
        final float[] b = buf;
        final int bL = bufLen;
        int w = writeIdx;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            b[w] = x;
            w++; if (w >= bL) w = 0;

            float wet = 0f;
            for (int v = 0; v < VOICES; v++) {
                float lfo = (float) Math.sin(phases[v]);
                float delay = baseSamp + depthSamp * (0.5f + 0.5f * lfo) * (0.6f + v * 0.07f);
                float read = w - delay;
                while (read < 0) read += bL;
                int i0 = (int) read;
                float frac = read - i0;
                int i1 = i0 + 1; if (i1 >= bL) i1 = 0;
                wet += b[i0] * (1f - frac) + b[i1] * frac;
                phases[v] += twoPiOverSr * rate * RATE_MULTIPLIERS[v];
                if (phases[v] > twoPi) phases[v] -= twoPi;
            }
            wet *= voiceScale;
            output[i] = x * dry + wet * mixLocal;
        }
        writeIdx = w;
    }
}
