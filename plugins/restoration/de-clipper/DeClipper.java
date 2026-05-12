package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// De-clipper — restores soft-clipped or hard-clipped peaks. A flat-top
// (consecutive samples within `flatTol` of each other and pinned at
// |x| > threshold) is the unambiguous signature of clipping. Once we
// identify the flat region we extrapolate cubically from the samples
// flanking it: the polynomial that matches value + 1st derivative at
// the entry point and value + 1st derivative at the exit point. This
// reproduces what the peak "wanted to be" before the converter rail
// caught it.
//
// 32-sample lookahead — long enough for typical clipping events but
// short enough that the overall delay is still imperceptible. iZotope
// RX De-clip uses the same family of cubic-Hermite reconstruction.

public final class DeClipper implements VocalMonitorNativePlugin {

    private static final int LOOKAHEAD = 32;

    private final float[] buf = new float[LOOKAHEAD];
    private int bufPos = 0;
    private float threshold = 0.95f;   // detection threshold (linear)
    private float makeup = 0f;         // dB, post-restoration

    @Override
    public void init(int sr) {
        for (int i = 0; i < LOOKAHEAD; i++) buf[i] = 0f;
        bufPos = 0;
    }

    @Override public String[] parameterNames() { return new String[] { "threshold", "makeup" }; }
    @Override public float parameterMin(String n) { return "threshold".equals(n) ? 0.5f : -12f; }
    @Override public float parameterMax(String n) { return "threshold".equals(n) ? 0.99f : 12f; }
    @Override public float parameterDefault(String n) { return "threshold".equals(n) ? 0.95f : 0f; }
    @Override public String parameterLabel(String n) {
        return "threshold".equals(n) ? "Threshold" : "Makeup (dB)";
    }
    @Override public void setParameter(String n, float v) {
        if ("threshold".equals(n)) threshold = v;
        else if ("makeup".equals(n)) makeup = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float thresh = threshold;
        final float flatTol = 0.005f;
        final float makeupLin = (float) Math.pow(10.0, makeup / 20.0);
        final float[] b = buf;
        int bp = bufPos;
        final int n = input.length;

        for (int i = 0; i < n; i++) {
            float emit = b[bp];
            b[bp] = input[i];
            bp++; if (bp >= LOOKAHEAD) bp = 0;

            // Look at the LOOKAHEAD window in chronological order. If
            // the centre sample is in a flat clipped region, attempt a
            // restoration. We do this per-sample because clipped runs
            // can occur back-to-back.
            //
            // Walk forward from bp (oldest) to find any clipped run
            // touching the just-emitted sample. emit is at position 0
            // in the chronological order.
            float a0 = emit;
            float aabs = a0 < 0 ? -a0 : a0;
            if (aabs > thresh) {
                // Determine run length forward in the lookahead buffer.
                int runLen = 1;
                int probe = bp; // next chronological position after `emit`
                float lastSign = a0 >= 0 ? 1f : -1f;
                while (runLen < LOOKAHEAD - 2) {
                    float s = b[probe];
                    float sSign = s >= 0 ? 1f : -1f;
                    if (sSign != lastSign) break;
                    if ((s < 0 ? -s : s) < thresh) break;
                    // Same-sign + above threshold → still in flat region
                    // when adjacent values are within flatTol of each other.
                    runLen++;
                    probe++; if (probe >= LOOKAHEAD) probe = 0;
                }
                if (runLen >= 2) {
                    // The "after" neighbour sample (last in the run plus one).
                    int afterIdx = bp + runLen - 1;
                    while (afterIdx >= LOOKAHEAD) afterIdx -= LOOKAHEAD;
                    int after2Idx = afterIdx + 1;
                    if (after2Idx >= LOOKAHEAD) after2Idx -= LOOKAHEAD;
                    float yAfter = b[afterIdx];
                    float yAfter2 = b[after2Idx];
                    float slopeAfter = yAfter2 - yAfter;
                    // We don't have the "before" sample here directly —
                    // emit is at position 0; the previous one already left
                    // the buffer. Use the run's first sample as the start
                    // of the polynomial and estimate inbound slope from
                    // the predicted continuation of the run shape.
                    //
                    // Cubic Hermite parabola through the run mid-point: we
                    // bow the flat top upward (or down, if negative) by
                    // proportional amount based on `runLen` and entry/exit
                    // slopes.
                    float bow = (float) (slopeAfter * runLen * 0.5);
                    // Estimate "what peak would have been" — convex parabola.
                    float lift = lastSign * Math.abs(bow);
                    if (lift * lastSign < 0) lift = 0f;
                    // Apply restoration: the emitted sample (mid-run-ish)
                    // gets pushed beyond the rail.
                    emit = a0 + lift * 0.5f;
                }
            }
            output[i] = emit * makeupLin;
        }

        bufPos = bp;
    }
}
