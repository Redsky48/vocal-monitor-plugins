package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Dub Delay — tape-delay with an LFO-swept bandpass filter and soft
// saturation in the feedback path. Each repeat passes through a moving
// filter window and gets gently distorted, so the echoes drift away
// from the source toward a smeared, harmonically-rich tail. The dub
// reggae console trick — King Tubby / Lee Perry territory — with
// modern self-oscillation control via the feedback knob.

public final class DubDelay implements VocalMonitorNativePlugin {

    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    // Bandpass biquad on feedback.
    private final float[] s = new float[4];
    private float lfoPhase = 0f;
    private int sampleRate = 44100;

    private float time = 380f;
    private float feedback = 0.55f;
    private float filterRate = 0.3f;
    private float filterDepth = 0.6f;
    private float drive = 0.4f;
    private float mix = 0.45f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = sr * 2;
        buf = new float[bufLen];
        writeIdx = 0;
        for (int i = 0; i < 4; i++) s[i] = 0f;
        lfoPhase = 0f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "time", "feedback", "filterRate", "filterDepth", "drive", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "time":       return 50f;
            case "filterRate": return 0.05f;
            default:           return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "time":       return 1500f;
            case "feedback":   return 0.95f;
            case "filterRate": return 5f;
            default:           return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "time":        return 380f;
            case "feedback":    return 0.55f;
            case "filterRate":  return 0.3f;
            case "filterDepth": return 0.6f;
            case "drive":       return 0.4f;
            case "mix":         return 0.45f;
            default:            return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "time":        return "Time (ms)";
            case "feedback":    return "Feedback";
            case "filterRate":  return "Filt Rate (Hz)";
            case "filterDepth": return "Filt Depth";
            case "drive":       return "Drive";
            case "mix":         return "Mix";
            default:            return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "time":        time = v; break;
            case "feedback":    feedback = v; break;
            case "filterRate":  filterRate = v; break;
            case "filterDepth": filterDepth = v; break;
            case "drive":       drive = v; break;
            case "mix":         mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final int d = Math.max(1, Math.min(bufLen - 1, (int) (time * sampleRate / 1000f)));
        final float fb = feedback;
        final float phaseInc = (float) (2.0 * Math.PI * filterRate / sampleRate);
        final float twoPi = (float) (2.0 * Math.PI);
        final float depthLocal = filterDepth;
        final float driveK = 1f + drive * 5f;
        final float driveNorm = 1f / (float) Math.tanh(driveK);
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float twoPiOverSr = (float) (2.0 * Math.PI / sampleRate);
        final float[] b = buf;
        final int bL = bufLen;
        int w = writeIdx;
        float ph = lfoPhase;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            // Sweep BP cutoff between 200..3000 Hz logarithmically.
            float lfo = 0.5f + 0.5f * (float) Math.sin(ph);
            float swing = depthLocal * lfo + (1f - depthLocal) * 0.5f;
            float cutoff = 200f * (float) Math.pow(15.0, swing);
            float w0 = twoPiOverSr * cutoff;
            float sinW = (float) Math.sin(w0);
            float cosW = (float) Math.cos(w0);
            float alpha = sinW / (2f * 1.5f);   // Q = 1.5
            float a0 = 1f + alpha;
            float nb0 = alpha / a0;
            float na1 = -2f * cosW / a0;
            float na2 = (1f - alpha) / a0;

            int r = w - d; if (r < 0) r += bL;
            float delayed = b[r];

            // Bandpass biquad on the wet signal.
            float bpOut = nb0 * delayed + (-nb0) * s[1] - na1 * s[2] - na2 * s[3];
            s[1] = s[0]; s[0] = delayed;
            s[3] = s[2]; s[2] = bpOut;
            // Drive (asymmetric soft clip).
            float driven = (float) Math.tanh(bpOut * driveK) * driveNorm;

            b[w] = input[i] + driven * fb;
            w++; if (w >= bL) w = 0;
            output[i] = input[i] * dry + driven * mixLocal;

            ph += phaseInc;
            if (ph > twoPi) ph -= twoPi;
        }
        writeIdx = w;
        lfoPhase = ph;
    }
}
