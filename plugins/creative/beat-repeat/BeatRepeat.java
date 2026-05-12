package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import java.util.Random;

// Beat Repeat — stutters the input on a fixed period, but with random
// variations applied to each cycle: the slice length can shrink, the
// playback pitch can shift up/down an octave, or the slice can play
// reversed. Builds the kind of glitchy chops Squarepusher or Aphex Twin
// scatter through their drum programming, on whatever source you feed
// it.

public final class BeatRepeat implements VocalMonitorNativePlugin {

    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    private int periodSamples;
    private int cursor = 0;
    private float readPos = 0f;
    private float currentRatio = 1f;
    private boolean currentReverse = false;
    private int currentSliceLen = 0;
    private final Random rng = new Random();
    private int sampleRate = 44100;

    private float period = 250f;       // ms
    private float randomness = 0.5f;   // 0..1
    private float pitchSpread = 0.5f;  // 0..1 octaves max
    private float mix = 0.7f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = sr;
        buf = new float[bufLen];
        writeIdx = 0;
        cursor = 0;
        readPos = 0f;
        currentRatio = 1f;
        currentReverse = false;
        periodSamples = sr / 4;
        currentSliceLen = periodSamples;
    }

    @Override public String[] parameterNames() {
        return new String[] { "period", "randomness", "pitchSpread", "mix" };
    }
    @Override public float parameterMin(String n) {
        return "period".equals(n) ? 50f : 0f;
    }
    @Override public float parameterMax(String n) {
        return "period".equals(n) ? 1000f : 1f;
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "period":      return 250f;
            case "randomness":  return 0.5f;
            case "pitchSpread": return 0.5f;
            case "mix":         return 0.7f;
            default:            return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "period":      return "Period (ms)";
            case "randomness":  return "Randomness";
            case "pitchSpread": return "Pitch Spread";
            case "mix":         return "Mix";
            default:            return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "period":      period = v; break;
            case "randomness":  randomness = v; break;
            case "pitchSpread": pitchSpread = v; break;
            case "mix":         mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        periodSamples = Math.max(64, Math.min(bufLen / 2, (int) (period * sampleRate / 1000f)));
        final float randLocal = randomness;
        final float spreadLocal = pitchSpread;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float[] b = buf;
        final int bL = bufLen;
        int w = writeIdx;
        int c = cursor;
        float rp = readPos;
        float ratio = currentRatio;
        boolean rev = currentReverse;
        int sliceLen = currentSliceLen;
        final Random r = rng;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            b[w] = x;
            w++; if (w >= bL) w = 0;

            if (c == 0) {
                // Start of a new cycle — record this slice and pick how
                // the rest of the cycle will play back.
                sliceLen = periodSamples;
                if (r.nextFloat() < randLocal) {
                    // Slice is half-length, pitched, or reversed.
                    float choice = r.nextFloat();
                    if (choice < 0.33f) {
                        sliceLen = periodSamples / 2;
                    } else if (choice < 0.66f) {
                        ratio = (float) Math.pow(2.0, (r.nextFloat() * 2f - 1f) * spreadLocal);
                        rev = false;
                    } else {
                        rev = true;
                        ratio = 1f;
                    }
                } else {
                    ratio = 1f;
                    rev = false;
                }
                rp = 0f;
                output[i] = x;
            } else if (c < sliceLen) {
                // Still within recording window — pass dry.
                output[i] = x;
            } else {
                // Play back from buffer.
                float src;
                if (rev) {
                    int idx = w - 1 - (c % sliceLen);
                    while (idx < 0) idx += bL;
                    src = b[idx];
                } else {
                    int playOffset = (int) (rp * ratio) % sliceLen;
                    int base = w - periodSamples;
                    while (base < 0) base += bL;
                    int idx = base + playOffset;
                    while (idx >= bL) idx -= bL;
                    src = b[idx];
                    rp += 1f;
                }
                output[i] = x * dry + src * mixLocal;
            }
            c++;
            if (c >= periodSamples) c = 0;
        }
        writeIdx = w;
        cursor = c;
        readPos = rp;
        currentRatio = ratio;
        currentReverse = rev;
        currentSliceLen = sliceLen;
    }
}
