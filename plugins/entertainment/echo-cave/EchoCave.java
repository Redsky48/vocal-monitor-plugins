package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

/**
 * Echo Cave — feedback delay line.  Stores up to 2 seconds of audio
 * in a circular buffer; each output sample mixes the dry input with
 * a tap taken `delayMs` ago, while feeding the tap back through the
 * buffer at `feedback` gain so the same sound bounces multiple
 * times.  Classic "shout into a canyon" effect that kids love.
 */
public final class EchoCave implements VocalMonitorNativePlugin {

    private int sampleRate = 44100;
    private float delayMs  = 350f;
    private float feedback = 0.55f;
    private float mix      = 0.45f;
    private float[] buf;
    private int bufIdx = 0;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        this.buf = new float[sr * 2];  // 2-second max delay
        this.bufIdx = 0;
    }

    @Override public String[] parameterNames() {
        return new String[] { "delayMs", "feedback", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "delayMs":  return 30f;
            default:         return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "delayMs":  return 2000f;
            case "feedback": return 0.92f;     // <1 to stay stable
            default:         return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "delayMs":  return 350f;
            case "feedback": return 0.55f;
            case "mix":      return 0.45f;
            default:         return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "delayMs":  return "Delay ms";
            case "feedback": return "Feedback";
            case "mix":      return "Mix";
            default:         return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "delayMs":  delayMs = v; break;
            case "feedback": feedback = v; break;
            case "mix":      mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        int delaySamples = (int) (delayMs * sampleRate / 1000f);
        if (delaySamples < 1)              delaySamples = 1;
        if (delaySamples >= buf.length)    delaySamples = buf.length - 1;
        final int N = buf.length;
        for (int i = 0; i < input.length; i++) {
            int readIdx = bufIdx - delaySamples;
            if (readIdx < 0) readIdx += N;
            float delayed = buf[readIdx];
            buf[bufIdx] = input[i] + delayed * feedback;
            bufIdx++;
            if (bufIdx >= N) bufIdx = 0;
            output[i] = input[i] * (1f - mix) + delayed * mix;
        }
    }
}
