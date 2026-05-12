package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import java.util.Random;

// Sample-and-Hold filter — a random LFO that picks a new value at every
// step and holds it flat until the next step (the "S&H" waveform from
// analog modular synths). The held value drives the cutoff of a band-
// pass biquad. Classic 80s arcade-bleeps / Daft Punk talkbox-y filter
// stutter. Optionally smooth the steps for a less staircase-y, more
// burbly motion.

public final class SampleHoldFilter implements VocalMonitorNativePlugin {

    private final float[] s = new float[4];   // DF1 biquad state
    private float heldValue = 0.5f;
    private float smoothedValue = 0.5f;
    private int sampleCounter = 0;
    private final Random rng = new Random();
    private int sampleRate = 44100;

    private float rate = 4f;            // Hz (steps per second)
    private float minFreq = 250f;
    private float maxFreq = 4000f;
    private float q = 4f;
    private float smoothness = 0f;       // 0 = pure stairstep, 1 = LFO-like
    private float mix = 1f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 4; i++) s[i] = 0f;
        heldValue = smoothedValue = 0.5f;
        sampleCounter = 0;
    }

    @Override public String[] parameterNames() {
        return new String[] { "rate", "minFreq", "maxFreq", "q", "smoothness", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "rate":    return 0.5f;
            case "minFreq": return 100f;
            case "maxFreq": return 500f;
            case "q":       return 1f;
            default:        return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "rate":    return 30f;
            case "minFreq": return 1000f;
            case "maxFreq": return 8000f;
            case "q":       return 20f;
            default:        return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "rate":       return 4f;
            case "minFreq":    return 250f;
            case "maxFreq":    return 4000f;
            case "q":          return 4f;
            case "smoothness": return 0f;
            case "mix":        return 1f;
            default:           return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "rate":       return "Rate (Hz)";
            case "minFreq":    return "Min Hz";
            case "maxFreq":    return "Max Hz";
            case "q":          return "Q";
            case "smoothness": return "Smoothness";
            case "mix":        return "Mix";
            default:           return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "rate":       rate = v; break;
            case "minFreq":    minFreq = v; break;
            case "maxFreq":    maxFreq = v; break;
            case "q":          q = v; break;
            case "smoothness": smoothness = v; break;
            case "mix":        mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final int stepLen = Math.max(1, (int) (sampleRate / rate));
        final float maxF = Math.max(minFreq + 50f, maxFreq);
        final float logMin = (float) Math.log(minFreq);
        final float logMax = (float) Math.log(maxF);
        final float qLocal = q;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float smoothCoef = smoothness < 0.01f ? 1f
                : 1f - (float) Math.exp(-1.0 / (sampleRate * 0.001 * (1f + smoothness * 100f)));
        final float twoPiOverSr = (float) (2.0 * Math.PI / sampleRate);
        int counter = sampleCounter;
        float held = heldValue, smoothed = smoothedValue;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            if (counter <= 0) {
                held = rng.nextFloat();
                counter = stepLen;
            }
            counter--;
            smoothed = smoothed + smoothCoef * (held - smoothed);
            // Log-frequency mapping.
            float cutoff = (float) Math.exp(logMin + (logMax - logMin) * smoothed);
            float w0 = twoPiOverSr * cutoff;
            float sinW = (float) Math.sin(w0);
            float cosW = (float) Math.cos(w0);
            float alpha = sinW / (2f * qLocal);
            float a0 = 1f + alpha;
            float nb0 = alpha / a0;
            float na1 = -2f * cosW / a0;
            float na2 = (1f - alpha) / a0;
            float x = input[i];
            float y = nb0 * x + (-nb0) * s[1] - na1 * s[2] - na2 * s[3];
            s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = y;
            output[i] = x * dry + y * mixLocal;
        }
        sampleCounter = counter;
        heldValue = held;
        smoothedValue = smoothed;
    }
}
