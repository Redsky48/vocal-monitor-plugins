package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Pitch Shifter (native port) — direct translation of pitch-shifter.js.
// Two overlapping Hann-windowed grains from a circular buffer, read at
// speed = 2^(semitones/12). Buffer headroom = grainSize × ratioMax so the
// grain never reads ahead of the write head.
//
// The Hann envelope is a per-sample lookup against a pre-computed table
// instead of a live cos() — that alone is a major win over the JS path,
// where Rhino's bytecode dispatch dominates trig ops.
public final class PitchShifter implements VocalMonitorNativePlugin {

    private float[] buf;
    private int bufLen;
    private int write;
    private int grainSize;
    private int phase;
    private float[] hann;

    private float semitones = 0f, cents = 0f, mix = 1f;

    @Override
    public void init(int sampleRate) {
        // 350 ms covers ratio = 4x (= +24 semis) at the default 80 ms grain.
        bufLen = (int) Math.ceil(sampleRate * 0.35);
        buf = new float[bufLen];
        write = 0;
        grainSize = (int) Math.floor(sampleRate * 0.08);
        phase = 0;
        hann = new float[grainSize];
        for (int i = 0; i < grainSize; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / grainSize));
        }
    }

    @Override
    public String[] parameterNames() {
        return new String[] { "semitones", "cents", "mix" };
    }

    @Override
    public float parameterMin(String name) {
        switch (name) {
            case "semitones": return -24f;
            case "cents":     return -100f;
            case "mix":       return 0f;
            default:          return 0f;
        }
    }

    @Override
    public float parameterMax(String name) {
        switch (name) {
            case "semitones": return 24f;
            case "cents":     return 100f;
            case "mix":       return 1f;
            default:          return 1f;
        }
    }

    @Override
    public float parameterDefault(String name) {
        if ("mix".equals(name)) return 1f;
        return 0f;
    }

    @Override
    public String parameterLabel(String name) {
        switch (name) {
            case "semitones": return "Semitones";
            case "cents":     return "Cents";
            case "mix":       return "Mix";
            default:          return name;
        }
    }

    @Override
    public void setParameter(String name, float value) {
        switch (name) {
            case "semitones": semitones = value; break;
            case "cents":     cents = value; break;
            case "mix":       mix = value; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float ratio = (float) Math.pow(2.0, (semitones + cents * 0.01) / 12.0);
        final int gs = grainSize;
        final int halfGs = gs / 2;
        final int bL = bufLen;
        final float[] hannLocal = hann;
        final float[] bufLocal = buf;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        int w = write;
        int p = phase;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            final float x = input[i];
            bufLocal[w] = x;

            final int pA = p;
            final int pB = (p + halfGs) % gs;
            float rA = w - gs * ratio + pA * ratio;
            float rB = w - gs * ratio + pB * ratio;
            while (rA < 0) rA += bL;
            while (rA >= bL) rA -= bL;
            while (rB < 0) rB += bL;
            while (rB >= bL) rB -= bL;
            int iA = (int) rA; float fA = rA - iA;
            int jA = iA + 1; if (jA >= bL) jA = 0;
            float sA = bufLocal[iA] * (1f - fA) + bufLocal[jA] * fA;
            int iB = (int) rB; float fB = rB - iB;
            int jB = iB + 1; if (jB >= bL) jB = 0;
            float sB = bufLocal[iB] * (1f - fB) + bufLocal[jB] * fB;
            float wet = sA * hannLocal[pA] + sB * hannLocal[pB];

            p++; if (p >= gs) p = 0;
            w++; if (w >= bL) w = 0;

            output[i] = x * dry + wet * mixLocal;
        }

        write = w;
        phase = p;
    }
}
