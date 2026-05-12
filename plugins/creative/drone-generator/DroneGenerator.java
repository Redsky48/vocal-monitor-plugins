package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Drone Generator — tracks the dominant pitch of the input via a
// zero-crossing-based detector and synthesises a continuously-sustained
// drone at that same pitch. Mixed under the dry input, this acts like
// a built-in "shruti box" or harmonium: sing a note, get an instant
// drone underneath in the same key. Pitch tracker is smoothed slowly so
// brief vocal slides don't yank the drone around — only sustained notes
// move it.
//
// The drone voice itself is a Karplus-Strong-style resonant comb with
// near-unit feedback and gentle damping — gives a warm, harmonic tone
// that gracefully decays if you stop excitation. We feed pink noise into
// the line so the comb sustains indefinitely.

public final class DroneGenerator implements VocalMonitorNativePlugin {

    private float[] line;
    private int lineLen;
    private int lineIdx = 0;
    private float lp = 0f;

    // Pitch tracking via zero-crossing period averaging.
    private float prevX = 0f;
    private int samplesSinceCross = 0;
    private float pitchEst = 220f;   // smoothed
    private final float[] crossBuf = new float[8];
    private int crossPos = 0;

    private final float[] pink = new float[5];
    private int pinkCounter = 0;
    private long noiseSeed = 1;

    private int sampleRate = 44100;
    private float pitchSmoothness = 0.5f;
    private float level = 0.4f;
    private float tone = 0.4f;
    private float mix = 0.5f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        lineLen = sr;
        line = new float[lineLen];
        lineIdx = 0; lp = 0f;
        prevX = 0f; samplesSinceCross = 0;
        pitchEst = 220f;
        for (int i = 0; i < crossBuf.length; i++) crossBuf[i] = sr / 220f;
        crossPos = 0;
        for (int i = 0; i < 5; i++) pink[i] = 0f;
        pinkCounter = 0; noiseSeed = 1;
    }

    @Override public String[] parameterNames() {
        return new String[] { "smoothness", "level", "tone", "mix" };
    }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "smoothness": return 0.5f;
            case "level":      return 0.4f;
            case "tone":       return 0.4f;
            case "mix":        return 0.5f;
            default:           return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "smoothness": return "Smoothness";
            case "level":      return "Drone Level";
            case "tone":       return "Tone";
            case "mix":        return "Mix";
            default:           return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "smoothness": pitchSmoothness = v; break;
            case "level":      level = v; break;
            case "tone":       tone = v; break;
            case "mix":        mix = v; break;
        }
    }

    private float nextWhite() {
        // Linear congruential noise, deterministic but cheap.
        noiseSeed = noiseSeed * 1664525L + 1013904223L;
        long u = noiseSeed & 0xFFFFFFFFL;
        return ((float) u / 2147483648f) - 1f;
    }
    private float nextPink() {
        pinkCounter++;
        int lsb = 0; int c = pinkCounter;
        while ((c & 1) == 0 && lsb < pink.length - 1) { lsb++; c >>= 1; }
        pink[lsb] = nextWhite();
        return (pink[0] + pink[1] + pink[2] + pink[3] + pink[4]) * 0.2f;
    }

    @Override
    public void process(float[] input, float[] output) {
        // Smoothness 0..1 → trail constant 50..3000 ms on the pitch IIR.
        final float pitchTimeSec = 0.05f + pitchSmoothness * 2.95f;
        final float pitchCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * pitchTimeSec));
        final float lpA = 0.1f + tone * 0.8f;
        final float feedback = 0.99f;
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float levelLocal = level;
        float pE = pitchEst;
        float pX = prevX;
        int ssc = samplesSinceCross;
        int cP = crossPos;
        int idx = lineIdx;
        float lpS = lp;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            // Pitch detection: rising zero-crossing intervals.
            ssc++;
            if (pX <= 0f && x > 0f && ssc > sampleRate / 1500) {  // ignore < ~666 us spurious
                if (ssc < sampleRate / 60) {  // ignore < 60 Hz (likely sub-harmonic)
                    // Keep period — usable.
                    crossBuf[cP] = ssc;
                    cP++; if (cP >= crossBuf.length) cP = 0;
                    // Median of recent crossings → less jittery than instant period.
                    float sum = 0f;
                    for (int k = 0; k < crossBuf.length; k++) sum += crossBuf[k];
                    float meanPeriod = sum / crossBuf.length;
                    float detected = sampleRate / meanPeriod;
                    if (detected > 50f && detected < 1500f) {
                        pE = pE + pitchCoef * (detected - pE);
                    }
                }
                ssc = 0;
            }
            pX = x;

            // Drone synthesis — Karplus-Strong resonator at pitchEst.
            float period = sampleRate / pE;
            if (period < 2f) period = 2f;
            if (period >= lineLen) period = lineLen - 1;
            float read = idx - period;
            while (read < 0) read += lineLen;
            int i0 = (int) read;
            float frac = read - i0;
            int i1 = i0 + 1; if (i1 >= lineLen) i1 = 0;
            float delayed = line[i0] * (1f - frac) + line[i1] * frac;
            lpS = lpS + lpA * (delayed - lpS);
            // Inject a small amount of pink noise to keep the comb singing.
            float excite = nextPink() * 0.01f;
            line[idx] = lpS * feedback + excite;
            idx++; if (idx >= lineLen) idx = 0;
            float droneVoice = lpS * levelLocal;

            output[i] = x * dry + (x + droneVoice) * mixLocal;
        }
        pitchEst = pE; prevX = pX; samplesSinceCross = ssc; crossPos = cP;
        lineIdx = idx; lp = lpS;
    }
}
