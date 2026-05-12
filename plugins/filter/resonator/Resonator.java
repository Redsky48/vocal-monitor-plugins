package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Resonator (native port) — Karplus-Strong tuned comb. Delay-line
// feedback rings at the selected pitch, damped by a one-pole LP.
// Sympathetic-resonance harmonics; high feedback = infinite drone.
public final class Resonator implements VocalMonitorNativePlugin {
    private float[] buf;
    private int bufLen;
    private int idx = 0;
    private float lp = 0f;
    private int sampleRate = 44100;
    private float pitch = 220f, feedback = 0.92f, damping = 0.3f, mix = 0.5f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = (int) Math.ceil(sr / 50.0);
        buf = new float[bufLen];
        idx = 0;
        lp = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "pitch", "feedback", "damping", "mix" }; }
    @Override public float parameterMin(String n) {
        if ("pitch".equals(n)) return 50f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "pitch":    return 2000f;
            case "feedback": return 0.999f;
            default:         return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "pitch":    return 220f;
            case "feedback": return 0.92f;
            case "damping":  return 0.3f;
            case "mix":      return 0.5f;
            default:         return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "pitch":    return "Pitch (Hz)";
            case "feedback": return "Feedback";
            case "damping":  return "Damping";
            case "mix":      return "Mix";
            default:         return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "pitch":    pitch = v; break;
            case "feedback": feedback = v; break;
            case "damping":  damping = v; break;
            case "mix":      mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        float period = sampleRate / pitch;
        if (period < 2f) period = 2f;
        if (period >= bufLen) period = bufLen - 1;
        final float fb = feedback;
        final float lpA = 1f - damping * 0.95f;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float[] b = buf;
        final int bL = bufLen;
        int i_ = idx;
        float lpS = lp;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float read = i_ - period;
            while (read < 0) read += bL;
            while (read >= bL) read -= bL;
            int i0 = (int) read;
            float frac = read - i0;
            int i1 = i0 + 1; if (i1 >= bL) i1 = 0;
            float delayed = b[i0] * (1f - frac) + b[i1] * frac;
            lpS = lpS + lpA * (delayed - lpS);
            float wet = lpS;
            b[i_] = x + wet * fb;
            i_++; if (i_ >= bL) i_ = 0;
            output[i] = x * dry + wet * mixLocal;
        }
        idx = i_; lp = lpS;
    }
}
