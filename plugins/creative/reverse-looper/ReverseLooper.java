package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Reverse Looper — continuously records a sliding window of the most
// recent N seconds; while `record` is held, the input flows into the
// buffer; while `play` is held, the buffer plays back in REVERSE. Useful
// for shoegaze-style backwards swells, special-effect transitions, or
// just hearing what you just said played backwards.
//
// Crossfade between two read heads, offset by half the loop length, hides
// the join the same way Freeze does.

public final class ReverseLooper implements VocalMonitorNativePlugin {

    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    private int loopLen;
    private float readPos = 0f;
    private float[] hann;
    private int sampleRate = 44100;

    private float record = 1f;     // 0 = paused, 1 = recording
    private float play = 0f;       // 0 = dry only, 1 = reverse playback active
    private float length = 1000f;  // ms
    private float mix = 0.6f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = sr * 4;
        buf = new float[bufLen];
        writeIdx = 0;
        loopLen = sr;
        readPos = 0f;
        int hannLen = 1024;
        hann = new float[hannLen];
        for (int i = 0; i < hannLen; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / hannLen));
        }
    }

    @Override public String[] parameterNames() { return new String[] { "record", "play", "length", "mix" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) {
        return "length".equals(n) ? 4000f : 1f;
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "record": return 1f;
            case "play":   return 0f;
            case "length": return 1000f;
            case "mix":    return 0.6f;
            default:       return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "record": return "Record";
            case "play":   return "Play Rev";
            case "length": return "Length (ms)";
            case "mix":    return "Mix";
            default:       return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "record": record = v; break;
            case "play":   play = v; break;
            case "length": length = v; break;
            case "mix":    mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        loopLen = Math.max(256, Math.min(bufLen - 1, (int) (length * sampleRate / 1000f)));
        final boolean rec = record >= 0.5f;
        final boolean playing = play >= 0.5f;
        final float mixLocal = mix;
        final float halfLen = loopLen * 0.5f;
        final int hLen = hann.length;
        final float[] b = buf;
        final float[] hLut = hann;
        int w = writeIdx;
        float rp = readPos;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            if (rec) {
                b[w] = x;
                w++; if (w >= bufLen) w = 0;
            }
            if (playing) {
                // Reverse: rA decreases at +1 per sample, but we feed it
                // an inverted index relative to the current write head.
                float rA = rp;
                float rB = rp + halfLen;
                if (rB >= loopLen) rB -= loopLen;
                int baseEnd = w - 1;
                if (baseEnd < 0) baseEnd += bufLen;
                int idxA = baseEnd - (int) rA;
                while (idxA < 0) idxA += bufLen;
                int idxB = baseEnd - (int) rB;
                while (idxB < 0) idxB += bufLen;
                int hA = (int) (rA / loopLen * hLen);
                int hB = (int) (rB / loopLen * hLen);
                if (hA >= hLen) hA = hLen - 1;
                if (hB >= hLen) hB = hLen - 1;
                float wet = b[idxA] * hLut[hA] + b[idxB] * hLut[hB];
                rp += 1f;
                if (rp >= loopLen) rp -= loopLen;
                output[i] = x * (1f - mixLocal) + wet * mixLocal;
            } else {
                rp = 0f;
                output[i] = x;
            }
        }
        writeIdx = w;
        readPos = rp;
    }
}
