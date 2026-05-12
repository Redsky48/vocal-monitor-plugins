package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Limiter (native port) — brickwall peak limiter with 8 ms lookahead.
// The detector sees what's coming and clamps gain before peaks reach
// the output. Last-stage master-bus glue at 0 dBFS.
public final class Limiter implements VocalMonitorNativePlugin {
    private float[] buf;
    private int bufLen;
    private int idx = 0;
    private float env = 0f;
    private float gain = 1f;
    private float ceiling = -0.3f, release = 60f;
    private int sampleRate = 44100;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = (int) Math.floor(sr * 0.008);
        buf = new float[bufLen];
        idx = 0;
        env = 0f;
        gain = 1f;
    }

    @Override public String[] parameterNames() { return new String[] { "ceiling", "release" }; }
    @Override public float parameterMin(String n) {
        if ("ceiling".equals(n)) return -12f;
        return 5f;
    }
    @Override public float parameterMax(String n) {
        if ("ceiling".equals(n)) return 0f;
        return 500f;
    }
    @Override public float parameterDefault(String n) {
        return "ceiling".equals(n) ? -0.3f : 60f;
    }
    @Override public String parameterLabel(String n) {
        return "ceiling".equals(n) ? "Ceil (dB)" : "Rel (ms)";
    }
    @Override public void setParameter(String n, float v) {
        if ("ceiling".equals(n)) ceiling = v;
        else if ("release".equals(n)) release = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float ceilLin = (float) Math.pow(10.0, ceiling / 20.0);
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, bufLen * 0.25));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        final float[] b = buf;
        final int bL = bufLen;
        int ix = idx;
        float e = env, g = gain;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float played = b[ix];
            b[ix] = x;
            ix++; if (ix >= bL) ix = 0;
            float rect = x < 0 ? -x : x;
            float coef = rect > e ? attCoef : relCoef;
            e = e + coef * (rect - e);
            float target = e > ceilLin ? ceilLin / e : 1f;
            float gCoef = target < g ? attCoef : relCoef;
            g = g + gCoef * (target - g);
            output[i] = played * g;
        }
        idx = ix; env = e; gain = g;
    }
}
