package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// De-rumble — high-pass cascade to clean up subsonic mic stand thumps,
// HVAC drone and wind buffeting. Two cascaded biquad HPFs give a clean
// 24 dB/oct slope without the resonant peak you'd get from a single
// 4-pole. Cutoff sweepable from 30 Hz (subsonic only — preserves bass
// vocal warmth) up to 200 Hz (aggressive, for problem broadcast).

public final class DeRumble implements VocalMonitorNativePlugin {

    private final float[] hp1A = new float[2], hp1B = new float[2];
    private final float[] hp2A = new float[2], hp2B = new float[2];
    private int sampleRate = 44100;
    private float frequency = 80f, mix = 1f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) {
            hp1A[i] = hp1B[i] = hp2A[i] = hp2B[i] = 0f;
        }
    }

    @Override public String[] parameterNames() { return new String[] { "frequency", "mix" }; }
    @Override public float parameterMin(String n) {
        return "frequency".equals(n) ? 20f : 0f;
    }
    @Override public float parameterMax(String n) {
        return "frequency".equals(n) ? 250f : 1f;
    }
    @Override public float parameterDefault(String n) {
        return "frequency".equals(n) ? 80f : 1f;
    }
    @Override public String parameterLabel(String n) {
        return "frequency".equals(n) ? "Cutoff (Hz)" : "Mix";
    }
    @Override public void setParameter(String n, float v) {
        if ("frequency".equals(n)) frequency = v;
        else if ("mix".equals(n)) mix = v;
    }

    private static float[] bqHP(float fc, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) ((1.0 + c) * 0.5 / a0),
            (float) (-(1.0 + c) / a0),
            (float) ((1.0 + c) * 0.5 / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        final float[] hp = bqHP(frequency, sampleRate);
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float y1 = hp[0]*x + hp[1]*hp1A[0] + hp[2]*hp1A[1] - hp[3]*hp1B[0] - hp[4]*hp1B[1];
            hp1A[1] = hp1A[0]; hp1A[0] = x;
            hp1B[1] = hp1B[0]; hp1B[0] = y1;
            float y2 = hp[0]*y1 + hp[1]*hp2A[0] + hp[2]*hp2A[1] - hp[3]*hp2B[0] - hp[4]*hp2B[1];
            hp2A[1] = hp2A[0]; hp2A[0] = y1;
            hp2B[1] = hp2B[0]; hp2B[0] = y2;
            output[i] = x * dry + y2 * mixLocal;
        }
    }
}
