package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import java.util.Random;

// Granular Cloud — N parallel grain readers fire from random positions
// in a continuously-recorded buffer, each at a random pitch within a
// user-set spread. Each grain is Hann-enveloped so it has no click on
// entry/exit. Stack 4–8 grains and you get a continuous textural cloud
// that vaguely resembles the source but with no rhythm, no melody, just
// shimmer — Mutable Instruments Clouds, Tasty Chips GR-1, Ableton Grain
// Delay.

public final class GranularCloud implements VocalMonitorNativePlugin {

    private static final int MAX_GRAINS = 8;

    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    private float[] hann;
    private int hannLen;

    // Per-grain state.
    private final float[] grainPos = new float[MAX_GRAINS];      // current read position
    private final int[] grainOrigin = new int[MAX_GRAINS];       // buffer base when grain spawned
    private final float[] grainRatio = new float[MAX_GRAINS];    // pitch ratio
    private final int[] grainLen = new int[MAX_GRAINS];          // grain size in samples
    private final boolean[] grainActive = new boolean[MAX_GRAINS];

    private final Random rng = new Random();
    private int sampleRate = 44100;
    private int sinceLastSpawn = 0;

    private float density = 4f;        // active grain count
    private float size = 80f;          // ms
    private float pitchSpread = 0.3f;  // 0..1
    private float mix = 0.7f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = sr * 2;
        buf = new float[bufLen];
        writeIdx = 0;
        hannLen = 2048;
        hann = new float[hannLen];
        for (int i = 0; i < hannLen; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / hannLen));
        }
        for (int g = 0; g < MAX_GRAINS; g++) {
            grainActive[g] = false;
            grainPos[g] = 0f;
            grainOrigin[g] = 0;
            grainRatio[g] = 1f;
            grainLen[g] = sr / 10;
        }
        sinceLastSpawn = 0;
    }

    @Override public String[] parameterNames() {
        return new String[] { "density", "size", "pitchSpread", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "density": return 1f;
            case "size":    return 20f;
            default:        return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "density":     return 8f;
            case "size":        return 300f;
            case "pitchSpread": return 1f;
            default:            return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "density":     return 4f;
            case "size":        return 80f;
            case "pitchSpread": return 0.3f;
            case "mix":         return 0.7f;
            default:            return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "density":     return "Density";
            case "size":        return "Size (ms)";
            case "pitchSpread": return "Pitch Spread";
            case "mix":         return "Mix";
            default:            return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "density":     density = v; break;
            case "size":        size = v; break;
            case "pitchSpread": pitchSpread = v; break;
            case "mix":         mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final int wantGrains = Math.max(1, Math.min(MAX_GRAINS, (int) density));
        final int grainSizeSamples = Math.max(64, (int) (size * sampleRate / 1000f));
        // Spawn one new grain every (grainSize / wantGrains) samples so
        // they overlap evenly.
        final int spawnPeriod = Math.max(1, grainSizeSamples / wantGrains);
        final float spreadLocal = pitchSpread;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final int bL = bufLen;
        final float[] b = buf;
        final float[] hLut = hann;
        final Random r = rng;
        int w = writeIdx;
        int sinceSpawn = sinceLastSpawn;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            b[w] = x;
            w++; if (w >= bL) w = 0;
            sinceSpawn++;

            // Spawn new grains as needed.
            if (sinceSpawn >= spawnPeriod) {
                sinceSpawn = 0;
                // Find a free slot.
                for (int g = 0; g < wantGrains; g++) {
                    if (!grainActive[g]) {
                        grainActive[g] = true;
                        grainPos[g] = 0f;
                        grainLen[g] = grainSizeSamples;
                        // Random pitch shift: 2^(±spread octave * random)
                        float octave = (r.nextFloat() * 2f - 1f) * spreadLocal * 1.5f;
                        grainRatio[g] = (float) Math.pow(2.0, octave);
                        // Random offset back into the buffer: 0 .. ~1s
                        int backOffset = (int) (r.nextFloat() * sampleRate * 0.5f) + grainSizeSamples;
                        int orig = w - backOffset;
                        while (orig < 0) orig += bL;
                        grainOrigin[g] = orig;
                        break;
                    }
                }
            }

            float wet = 0f;
            for (int g = 0; g < wantGrains; g++) {
                if (!grainActive[g]) continue;
                float rp = grainPos[g];
                int gLen = grainLen[g];
                if (rp >= gLen) {
                    grainActive[g] = false;
                    continue;
                }
                int readIdx = grainOrigin[g] + (int) (rp * grainRatio[g]);
                while (readIdx >= bL) readIdx -= bL;
                while (readIdx < 0) readIdx += bL;
                int hIdx = (int) (rp / gLen * hannLen);
                if (hIdx >= hannLen) hIdx = hannLen - 1;
                wet += b[readIdx] * hLut[hIdx];
                grainPos[g] = rp + 1f;
            }
            // Normalise: roughly /wantGrains for unit gain.
            wet *= (1f / wantGrains);
            output[i] = x * dry + wet * mixLocal;
        }
        writeIdx = w;
        sinceLastSpawn = sinceSpawn;
    }
}
