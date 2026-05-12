package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Multi-tap Delay — four taps reading from the same delay buffer at
// independent times and gains. Set the four times to rhythmic ratios
// (e.g. 100/200/300/400 ms or musically-spaced 187/375/562/750) and
// you get a programmable rhythmic delay pattern without needing a
// separate plugin instance per tap. Roland SDE-3000 territory.

public final class MultiTapDelay implements VocalMonitorNativePlugin {

    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    private int sampleRate = 44100;

    private float t1 = 125f, t2 = 250f, t3 = 375f, t4 = 500f;
    private float g1 = 0.7f, g2 = 0.55f, g3 = 0.4f, g4 = 0.25f;
    private float feedback = 0.2f;
    private float mix = 0.4f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = sr * 2;   // 2 s max
        buf = new float[bufLen];
        writeIdx = 0;
    }

    @Override public String[] parameterNames() {
        return new String[] { "time1", "time2", "time3", "time4", "gain1", "gain2", "gain3", "gain4", "feedback", "mix" };
    }
    @Override public float parameterMin(String n) {
        if (n.startsWith("time")) return 5f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        if (n.startsWith("time"))  return 1500f;
        if (n.equals("feedback"))  return 0.9f;
        return 1f;
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "time1": return 125f;
            case "time2": return 250f;
            case "time3": return 375f;
            case "time4": return 500f;
            case "gain1": return 0.7f;
            case "gain2": return 0.55f;
            case "gain3": return 0.4f;
            case "gain4": return 0.25f;
            case "feedback": return 0.2f;
            case "mix":   return 0.4f;
            default:      return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        if (n.startsWith("time")) return "T" + n.charAt(4) + " (ms)";
        if (n.startsWith("gain")) return "G" + n.charAt(4);
        if (n.equals("feedback")) return "Feedback";
        if (n.equals("mix"))      return "Mix";
        return n;
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "time1": t1 = v; break;
            case "time2": t2 = v; break;
            case "time3": t3 = v; break;
            case "time4": t4 = v; break;
            case "gain1": g1 = v; break;
            case "gain2": g2 = v; break;
            case "gain3": g3 = v; break;
            case "gain4": g4 = v; break;
            case "feedback": feedback = v; break;
            case "mix":      mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final int d1 = Math.max(1, Math.min(bufLen - 1, (int) (t1 * sampleRate / 1000f)));
        final int d2 = Math.max(1, Math.min(bufLen - 1, (int) (t2 * sampleRate / 1000f)));
        final int d3 = Math.max(1, Math.min(bufLen - 1, (int) (t3 * sampleRate / 1000f)));
        final int d4 = Math.max(1, Math.min(bufLen - 1, (int) (t4 * sampleRate / 1000f)));
        final float fb = feedback;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float[] b = buf;
        final int bL = bufLen;
        int w = writeIdx;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            int r1 = w - d1; if (r1 < 0) r1 += bL;
            int r2 = w - d2; if (r2 < 0) r2 += bL;
            int r3 = w - d3; if (r3 < 0) r3 += bL;
            int r4 = w - d4; if (r4 < 0) r4 += bL;
            float wet = b[r1] * g1 + b[r2] * g2 + b[r3] * g3 + b[r4] * g4;
            b[w] = input[i] + wet * fb;
            w++; if (w >= bL) w = 0;
            output[i] = input[i] * dry + wet * mixLocal;
        }
        writeIdx = w;
    }
}
