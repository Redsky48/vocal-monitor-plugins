package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Tape Stop (native port) — variable-speed playback from a rolling
// buffer. speed → 0 grinds the audio to a halt with the pitch dropping
// with it. The classic EDM-drop tape-pull effect.
public final class TapeStop implements VocalMonitorNativePlugin {
    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    private float readPos = 0f;
    private float speed = 1f, mix = 1f;

    @Override
    public void init(int sr) {
        bufLen = 96000;
        buf = new float[bufLen];
        writeIdx = 0;
        readPos = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "speed", "mix" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 1f; }
    @Override public String parameterLabel(String n) { return "speed".equals(n) ? "Speed" : "Mix"; }
    @Override public void setParameter(String n, float v) {
        if ("speed".equals(n)) speed = v;
        else if ("mix".equals(n)) mix = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float spd = speed;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float[] b = buf;
        final int bL = bufLen;
        int w = writeIdx;
        float r = readPos;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            b[w] = input[i];
            w++; if (w >= bL) w = 0;
            int idx = (int) Math.floor(r) % bL;
            if (idx < 0) idx += bL;
            int i1 = idx + 1; if (i1 >= bL) i1 = 0;
            float frac = r - (float) Math.floor(r);
            float wet = b[idx] * (1f - frac) + b[i1] * frac;
            output[i] = input[i] * dry + wet * mixLocal;
            r += spd;
            if (r >= bL) r -= bL;
        }
        writeIdx = w;
        readPos = r;
    }
}
