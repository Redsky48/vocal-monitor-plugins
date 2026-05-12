package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Reverse Delay (native port) — record a window of length T, play it
// back in reverse. The read head sweeps backwards through the same
// chunk the write head advances through. Pink Floyd / shoegaze swells.
// Feedback piles nested reversals into a swelling cloud.
public final class ReverseDelay implements VocalMonitorNativePlugin {
    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    private int readOffset = 0;
    private int sampleRate = 44100;
    private float time = 600f, feedback = 0.3f, mix = 0.5f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = 192000;
        buf = new float[bufLen];
        writeIdx = 0;
        readOffset = 0;
    }

    @Override public String[] parameterNames() { return new String[] { "time", "feedback", "mix" }; }
    @Override public float parameterMin(String n) {
        if ("time".equals(n)) return 100f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "time":     return 4000f;
            case "feedback": return 0.9f;
            default:         return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "time":     return 600f;
            case "feedback": return 0.3f;
            case "mix":      return 0.5f;
            default:         return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "time":     return "Time (ms)";
            case "feedback": return "Feedback";
            case "mix":      return "Mix";
            default:         return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "time":     time = v; break;
            case "feedback": feedback = v; break;
            case "mix":      mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        int len = Math.max(64, (int) Math.floor(sampleRate * time / 1000f));
        if (len > bufLen) len = bufLen;
        final float fb = feedback;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float[] b = buf;
        final int bL = bufLen;
        int w = writeIdx;
        int ro = readOffset;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            int idx = w - 1 - ro;
            while (idx < 0) idx += bL;
            float wet = b[idx];
            b[w] = input[i] + wet * fb;
            w++; if (w >= bL) w = 0;
            ro++; if (ro >= len) ro = 0;
            output[i] = input[i] * dry + wet * mixLocal;
        }
        writeIdx = w;
        readOffset = ro;
    }
}
