package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Tape Delay (native port) — single-tap feedback delay with a one-pole
// low-pass on each repeat ("tone"). Each echo loses high-end like real
// analogue tape, so long feedback degrades into warm low-freq soup
// rather than harsh repeating spikes.
public final class TapeDelay implements VocalMonitorNativePlugin {
    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    private float lpState = 0f;
    private int sampleRate = 44100;
    private float time = 350f, feedback = 0.4f, tone = 0.5f, mix = 0.4f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = 150000;
        buf = new float[bufLen];
        writeIdx = 0;
        lpState = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "time", "feedback", "tone", "mix" }; }
    @Override public float parameterMin(String n) {
        if ("time".equals(n)) return 5f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "time":     return 1500f;
            case "feedback": return 0.95f;
            default:         return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "time":     return 350f;
            case "feedback": return 0.4f;
            case "tone":     return 0.5f;
            case "mix":      return 0.4f;
            default:         return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "time":     return "Time (ms)";
            case "feedback": return "Feedback";
            case "tone":     return "Tone";
            case "mix":      return "Mix";
            default:         return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "time":     time = v; break;
            case "feedback": feedback = v; break;
            case "tone":     tone = v; break;
            case "mix":      mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final int delaySamples = Math.max(1, (int) Math.floor(sampleRate * time / 1000f));
        final float lpA = 0.05f + 0.93f * tone;
        final float fb = feedback;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float[] b = buf;
        final int bL = bufLen;
        int w = writeIdx;
        float lp = lpState;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            int r = w - delaySamples;
            while (r < 0) r += bL;
            float delayed = b[r];
            lp = lp + lpA * (delayed - lp);
            b[w] = input[i] + lp * fb;
            w++; if (w >= bL) w = 0;
            output[i] = input[i] * dry + lp * mixLocal;
        }
        writeIdx = w;
        lpState = lp;
    }
}
