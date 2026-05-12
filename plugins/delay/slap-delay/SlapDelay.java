package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Slap Delay — a single 80–150 ms tap with no feedback, the classic
// 1950s rockabilly vocal effect (Sam Phillips' Sun Studio sound). The
// short tap thickens the voice without producing audible repeats, like
// a tape doubler set to one bounce. Optional tone knob lets you dull
// the tap so it sits behind the dry signal instead of competing with it.

public final class SlapDelay implements VocalMonitorNativePlugin {

    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    private float lpState = 0f;
    private int sampleRate = 44100;

    private float time = 110f;
    private float tone = 0.5f;
    private float mix = 0.35f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = sr / 4;
        buf = new float[bufLen];
        writeIdx = 0;
        lpState = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "time", "tone", "mix" }; }
    @Override public float parameterMin(String n) { return "time".equals(n) ? 30f : 0f; }
    @Override public float parameterMax(String n) { return "time".equals(n) ? 200f : 1f; }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "time": return 110f;
            case "tone": return 0.5f;
            case "mix":  return 0.35f;
            default:     return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "time": return "Time (ms)";
            case "tone": return "Tone";
            case "mix":  return "Mix";
            default:     return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "time": time = v; break;
            case "tone": tone = v; break;
            case "mix":  mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final int d = Math.max(1, Math.min(bufLen - 1, (int) (time * sampleRate / 1000f)));
        final float lpA = 0.05f + 0.93f * tone;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float[] b = buf;
        final int bL = bufLen;
        int w = writeIdx;
        float lp = lpState;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            int r = w - d; if (r < 0) r += bL;
            float delayed = b[r];
            lp = lp + lpA * (delayed - lp);
            b[w] = input[i];
            w++; if (w >= bL) w = 0;
            output[i] = input[i] * dry + lp * mixLocal;
        }
        writeIdx = w;
        lpState = lp;
    }
}
