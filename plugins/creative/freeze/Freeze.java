package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Freeze — captures the last N ms of audio into a circular buffer and,
// when toggled on, loops that buffer forever using two crossfaded read
// heads offset by half the loop length. The crossfade hides the seam
// where the buffer wraps, so the held sound has no audible click on
// every cycle — it just sustains. Same idea as the EHX Freeze pedal /
// Strymon Mobius freeze block / Ableton "Loop" device.
//
// When freeze=0 the buffer keeps quietly recording so you can always
// freeze whatever just happened. When freeze=1 the read heads activate
// and the input is muted (or crossfaded out) so only the loop is heard.

public final class Freeze implements VocalMonitorNativePlugin {

    private float[] buf;
    private int bufLen;
    private int writeIdx = 0;
    private int captureLen;
    private float readPos = 0f;
    private float[] hann;
    private boolean wasFrozen = false;
    private float fadeIn = 0f;
    private int sampleRate = 44100;

    private float freeze = 0f;
    private float length = 250f;   // ms
    private float mix = 1f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = sr;  // 1 second max capture
        buf = new float[bufLen];
        writeIdx = 0;
        captureLen = sr / 4;
        readPos = 0f;
        fadeIn = 0f;
        wasFrozen = false;
        // Hann LUT over a 64-sample crossfade region.
        int hannLen = 1024;
        hann = new float[hannLen];
        for (int i = 0; i < hannLen; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / hannLen));
        }
    }

    @Override public String[] parameterNames() { return new String[] { "freeze", "length", "mix" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) {
        return "length".equals(n) ? 1000f : 1f;
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "freeze": return 0f;
            case "length": return 250f;
            case "mix":    return 1f;
            default:       return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "freeze": return "Freeze";
            case "length": return "Length (ms)";
            case "mix":    return "Mix";
            default:       return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "freeze": freeze = v; break;
            case "length": length = v; break;
            case "mix":    mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final boolean frozen = freeze >= 0.5f;
        captureLen = Math.max(64, Math.min(bufLen - 1, (int) (length * sampleRate / 1000f)));
        // Snapshot the buffer position when freeze first activates so the
        // loop reads from the most recent captureLen samples.
        if (frozen && !wasFrozen) {
            readPos = 0f;
            fadeIn = 0f;
        }
        wasFrozen = frozen;
        final float fadeStep = 1f / Math.max(1, sampleRate / 200);  // 5 ms fade
        final float mixLocal = mix;
        final float halfLen = captureLen * 0.5f;
        final int hLen = hann.length;
        final int bL = bufLen;
        final float[] b = buf;
        final float[] hLut = hann;
        int w = writeIdx;
        float rp = readPos;
        float fI = fadeIn;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            // Always record so freeze can grab "the most recent thing".
            b[w] = x;
            w++; if (w >= bL) w = 0;

            if (frozen) {
                // Two read heads offset by half the loop length.
                float rA = rp;
                float rB = rp + halfLen;
                if (rB >= captureLen) rB -= captureLen;
                // Anchor reads to the buffer at the moment freeze was toggled —
                // captureWriteBase = the w value at freeze-on. We track that
                // implicitly by always reading at (w - captureLen + offset).
                int baseRead = w - captureLen;
                while (baseRead < 0) baseRead += bL;
                int idxA = baseRead + (int) rA;
                while (idxA >= bL) idxA -= bL;
                int idxB = baseRead + (int) rB;
                while (idxB >= bL) idxB -= bL;
                // Hann envelopes — read head A peaks mid-loop, B peaks at the
                // wrap point. Together they sum near unity.
                int hA = (int) (rA / captureLen * hLen);
                int hB = (int) (rB / captureLen * hLen);
                if (hA >= hLen) hA = hLen - 1;
                if (hB >= hLen) hB = hLen - 1;
                float envA = hLut[hA];
                float envB = hLut[hB];
                float loop = b[idxA] * envA + b[idxB] * envB;
                rp += 1f;
                if (rp >= captureLen) rp -= captureLen;

                fI += fadeStep; if (fI > 1f) fI = 1f;
                float wet = loop * fI;
                output[i] = x * (1f - mixLocal) + wet * mixLocal;
            } else {
                fI -= fadeStep; if (fI < 0f) fI = 0f;
                output[i] = x;
            }
        }
        writeIdx = w;
        readPos = rp;
        fadeIn = fI;
    }
}
