package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import java.util.Random;

// Vinyl (native port) — pink noise hiss (Voss-McCartney) plus
// Poisson-triggered crackle pops with one-pole decay. Aged-record
// texture under any take.
public final class Vinyl implements VocalMonitorNativePlugin {
    private final float[] pink = new float[5];
    private int counter = 0;
    private float crackleState = 0f;
    private final Random rng = new Random();
    private float hiss = 0.12f, crackle = 0.30f, mix = 1f;

    @Override
    public void init(int sr) {
        for (int i = 0; i < 5; i++) pink[i] = 0f;
        counter = 0;
        crackleState = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "hiss", "crackle", "mix" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "hiss":    return 0.12f;
            case "crackle": return 0.30f;
            case "mix":     return 1f;
            default:        return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "hiss":    return "Hiss";
            case "crackle": return "Crackle";
            case "mix":     return "Mix";
            default:        return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "hiss":    hiss = v; break;
            case "crackle": crackle = v; break;
            case "mix":     mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float hissScaled = hiss * 0.4f;
        final float crackleLocal = crackle;
        final float crackleProb = crackleLocal * 0.0008f;
        final float mixLocal = mix;
        final Random r = rng;
        final float[] pk = pink;
        int c = counter;
        float cs = crackleState;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            c++;
            int lsb = 0;
            int cc = c;
            while ((cc & 1) == 0 && lsb < pk.length - 1) {
                lsb++;
                cc >>= 1;
            }
            pk[lsb] = r.nextFloat() * 2f - 1f;
            float pinkSum = (pk[0] + pk[1] + pk[2] + pk[3] + pk[4]) * 0.18f;

            if (r.nextFloat() < crackleProb) {
                cs = (r.nextFloat() * 2f - 1f) * 0.8f;
            }
            cs *= 0.85f;

            float dirt = pinkSum * hissScaled + cs * crackleLocal;
            output[i] = input[i] + dirt * mixLocal;
        }
        counter = c;
        crackleState = cs;
    }
}
