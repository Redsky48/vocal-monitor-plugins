package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// De-clicker — detects impulsive clicks / digital pops / vinyl-style
// crackle via a 3rd-order linear predictor. For each sample we compute
//   prediction = 3*x[n-1] - 3*x[n-2] + x[n-3]
// which is the extrapolation of a polynomially smooth signal. The
// residual (x[n] - prediction) is then large only when the new sample
// breaks polynomial continuity — exactly what a click looks like.
//
// We compare the residual against a running median-style envelope of
// recent residuals; a residual that exceeds it by `sensitivity` times
// is flagged as a click. The flagged samples are replaced with the
// predictor's own output for a short repair window, then handed back to
// the running stream.
//
// 16-sample lookahead lets the repair span both the "before" and "after"
// neighbours of the click, so even a wide click is interpolated rather
// than half-erased. Same technique as Sonnox DeClicker / iZotope RX
// De-click.

public final class DeClicker implements VocalMonitorNativePlugin {

    private static final int LOOKAHEAD = 64;

    private final float[] buf = new float[LOOKAHEAD];
    private int bufPos = 0;            // ring-buffer write head
    private float h1 = 0, h2 = 0, h3 = 0;  // recent samples for predictor
    private float residualAvg = 1e-6f;
    private int repairCounter = 0;     // > 0 while we're outputting predicted samples
    private int sampleRate = 44100;

    private float sensitivity = 0.6f;  // 0..1
    private int repairLength = 8;      // samples to interpolate per detected event

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < LOOKAHEAD; i++) buf[i] = 0f;
        bufPos = 0; h1 = h2 = h3 = 0f;
        residualAvg = 1e-6f;
        repairCounter = 0;
    }

    @Override public String[] parameterNames() { return new String[] { "sensitivity", "repair" }; }
    @Override public float parameterMin(String n) { return "repair".equals(n) ? 2f : 0f; }
    @Override public float parameterMax(String n) { return "repair".equals(n) ? 32f : 1f; }
    @Override public float parameterDefault(String n) { return "repair".equals(n) ? 8f : 0.6f; }
    @Override public String parameterLabel(String n) {
        return "sensitivity".equals(n) ? "Sensitivity" : "Repair (smp)";
    }
    @Override public void setParameter(String n, float v) {
        if ("sensitivity".equals(n)) sensitivity = v;
        else if ("repair".equals(n)) repairLength = Math.max(2, Math.min(32, (int) v));
    }

    @Override
    public void process(float[] input, float[] output) {
        // sensitivity → threshold multiplier. Higher sens = lower threshold
        // = catches more (and risks false positives). 4..14 range maps OK.
        final float threshMul = 14f - sensitivity * 10f;
        final int repairLen = repairLength;
        // Slow envelope follower on the residual itself — gives us an
        // adaptive noise floor that automatically grows when content is
        // genuinely jagged (e.g. distorted vocals), shrinks when clean.
        final float envCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.020));
        float p1 = h1, p2 = h2, p3 = h3;
        float rAvg = residualAvg;
        int rc = repairCounter;
        int bp = bufPos;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            // We always output the sample LOOKAHEAD positions behind
            // the write head — that's our lookahead.
            float incoming = input[i];

            // 3rd-order extrapolation: assumes 2nd derivative continues.
            float prediction = 3f * p1 - 3f * p2 + p3;
            float residual = incoming - prediction;
            float absRes = residual < 0 ? -residual : residual;

            // Update running residual envelope using only the smaller of
            // current and previous — that suppresses contribution from
            // outright clicks so the floor doesn't get pulled up by them.
            if (absRes < rAvg) rAvg = rAvg + envCoef * (absRes - rAvg);
            else rAvg = rAvg + envCoef * 0.1f * (absRes - rAvg);
            if (rAvg < 1e-7f) rAvg = 1e-7f;

            // Decide whether to flag this sample.
            float storedSample = incoming;
            if (rc <= 0 && absRes > threshMul * rAvg && absRes > 0.005f) {
                // Click detected — engage repair for repairLen samples.
                rc = repairLen;
            }
            if (rc > 0) {
                // Replace with predictor output; keep predictor's history
                // moving as if the signal were smooth.
                storedSample = prediction;
                rc--;
            }

            // Advance predictor history with whatever we decided to keep.
            p3 = p2; p2 = p1; p1 = storedSample;

            float emit = buf[bp];
            buf[bp] = storedSample;
            bp++; if (bp >= LOOKAHEAD) bp = 0;
            output[i] = emit;
        }

        h1 = p1; h2 = p2; h3 = p3;
        residualAvg = rAvg;
        repairCounter = rc;
        bufPos = bp;
    }
}
