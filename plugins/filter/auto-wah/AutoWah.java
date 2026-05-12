package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Auto-Wah (native port) — envelope-following bandpass biquad. Loud
// notes push cutoff up, quiet notes let it fall — MuTron III "quack"
// on guitar / vocal. Biquad form: Direct Form II Transposed.
public final class AutoWah implements VocalMonitorNativePlugin {
    private float env = 0f;
    private float z1 = 0f, z2 = 0f;
    private int sampleRate = 44100;
    private float sensitivity = 0.6f, minFreq = 250f, maxFreq = 2500f,
                  q = 4f, attack = 5f, release = 80f;

    @Override
    public void init(int sr) { this.sampleRate = sr; env = 0f; z1 = 0f; z2 = 0f; }

    @Override
    public String[] parameterNames() {
        return new String[] { "sensitivity", "minFreq", "maxFreq", "q", "attack", "release" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "sensitivity": return 0f;
            case "minFreq":     return 60f;
            case "maxFreq":     return 400f;
            case "q":           return 0.5f;
            case "attack":      return 0.5f;
            case "release":     return 5f;
            default:            return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "sensitivity": return 1f;
            case "minFreq":     return 2000f;
            case "maxFreq":     return 8000f;
            case "q":           return 12f;
            case "attack":      return 200f;
            case "release":     return 500f;
            default:            return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "sensitivity": return 0.6f;
            case "minFreq":     return 250f;
            case "maxFreq":     return 2500f;
            case "q":           return 4f;
            case "attack":      return 5f;
            case "release":     return 80f;
            default:            return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "sensitivity": return "Sense";
            case "minFreq":     return "Min Hz";
            case "maxFreq":     return "Max Hz";
            case "q":           return "Q";
            case "attack":      return "Attack ms";
            case "release":     return "Rel ms";
            default:            return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "sensitivity": sensitivity = v; break;
            case "minFreq":     minFreq = v; break;
            case "maxFreq":     maxFreq = v; break;
            case "q":           q = v; break;
            case "attack":      attack = v; break;
            case "release":     release = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float maxF = Math.max(minFreq + 50f, maxFreq);
        final float attackCoef = (float) Math.exp(-1.0 / (sampleRate * attack * 0.001));
        final float releaseCoef = (float) Math.exp(-1.0 / (sampleRate * release * 0.001));
        final float senseMul = 1f + sensitivity * 8f;
        final float logMin = (float) Math.log(minFreq);
        final float logMax = (float) Math.log(maxF);
        final float qLocal = q;
        final float twoPiOverSr = (float) (2.0 * Math.PI / sampleRate);
        float e = env, zA = z1, zB = z2;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float rect = x < 0 ? -x : x;
            float coef = rect > e ? attackCoef : releaseCoef;
            e = rect + (e - rect) * coef;

            float drive = e * senseMul;
            if (drive > 1f) drive = 1f;
            float cutoff = (float) Math.exp(logMin + (logMax - logMin) * drive);

            float w0 = twoPiOverSr * cutoff;
            float sinW = (float) Math.sin(w0);
            float cosW = (float) Math.cos(w0);
            float alpha = sinW / (2f * qLocal);
            float a0 = 1f + alpha;
            float nb0 = alpha / a0;
            float nb2 = -alpha / a0;
            float na1 = -2f * cosW / a0;
            float na2 = (1f - alpha) / a0;

            float y = nb0 * x + zA;
            zA = -na1 * y + zB;
            zB = nb2 * x - na2 * y;
            output[i] = y;
        }
        env = e; z1 = zA; z2 = zB;
    }
}
