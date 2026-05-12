package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Stutter (native port) — beat-repeat. First slice of each cycle is
// captured into a buffer and replayed N-1 times before re-arming.
// Held vowels turn into percussive chops, drums into glitch fills.
public final class Stutter implements VocalMonitorNativePlugin {
    private float[] buf;
    private int cursor = 0;
    private int sampleRate = 44100;
    private float slice = 120f, repeat = 4f, mix = 0.7f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        buf = new float[96000];
        cursor = 0;
    }

    @Override public String[] parameterNames() { return new String[] { "slice", "repeat", "mix" }; }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "slice":  return 20f;
            case "repeat": return 1f;
            default:       return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "slice":  return 500f;
            case "repeat": return 8f;
            default:       return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "slice":  return 120f;
            case "repeat": return 4f;
            case "mix":    return 0.7f;
            default:       return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "slice":  return "Slice (ms)";
            case "repeat": return "Repeats";
            case "mix":    return "Mix";
            default:       return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "slice":  slice = v; break;
            case "repeat": repeat = v; break;
            case "mix":    mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        int sliceLen = Math.max(1, (int) Math.floor(sampleRate * slice / 1000f));
        if (sliceLen > buf.length) sliceLen = buf.length;
        final int repeats = Math.max(1, (int) Math.floor(repeat));
        final int totalLen = sliceLen * repeats;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float[] b = buf;
        int c = cursor;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            int phase = c % totalLen;
            if (phase < sliceLen) {
                b[phase] = input[i];
                output[i] = input[i];
            } else {
                int inSlice = phase % sliceLen;
                float wet = b[inSlice];
                output[i] = input[i] * dry + wet * mixLocal;
            }
            c++;
        }
        cursor = c;
    }
}
