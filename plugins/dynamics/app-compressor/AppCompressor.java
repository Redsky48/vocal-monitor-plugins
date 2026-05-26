package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

/**
 * Bit-exact port of the app's built-in {@code Compressor.kt} — a
 * single-channel feed-forward peak compressor with soft knee, asym-
 * metric attack/release envelope, and makeup gain.  Every formula
 * and constant maps 1:1 to the Kotlin source so this plugin
 * produces identical output to the host's save-time compressor.
 *
 * Parameter shape mirrors the Kotlin setters' clamp ranges:
 *   threshold   -60 .. 0 dBFS   (default -18)
 *   ratio         1 .. 40       (default 4)
 *   attack      0.1 .. 500 ms   (default 5)
 *   release       5 .. 3000 ms  (default 120)
 *   knee          0 .. 24 dB    (default 6)
 *   makeup        0 .. 24 dB    (default 4)
 */
public final class AppCompressor implements VocalMonitorNativePlugin {

    private static final float LN10 = 2.302585092994046f;

    private int sampleRate = 44_100;

    // Tunable parameters (with the same defaults as the Kotlin source).
    private float thresholdDb = -18f;
    private float ratio = 4f;
    private float attackMs = 5f;
    private float releaseMs = 120f;
    private float kneeWidthDb = 6f;
    private float makeupGainDb = 4f;

    // Cached envelope coefficients (recomputed when attack/release/sr change).
    private float attackCoef;
    private float releaseCoef;

    // Single-pole envelope state in dB of gain reduction (positive = more reduction).
    private float envelopeDb = 0f;

    @Override
    public void init(int sr) {
        this.sampleRate = Math.max(8_000, sr);
        envelopeDb = 0f;
        recomputeCoefs();
    }

    private float computeCoef(float timeMs) {
        // Standard one-pole time-constant: y[n] = a*y[n-1] + (1-a)*x[n]
        // with a = exp(-1 / (sr * tau_seconds)).  Matches Kotlin source.
        float seconds = timeMs / 1000f;
        if (seconds < 1e-6f) seconds = 1e-6f;
        return (float) Math.exp(-1.0 / (sampleRate * seconds));
    }

    private void recomputeCoefs() {
        attackCoef  = computeCoef(attackMs);
        releaseCoef = computeCoef(releaseMs);
    }

    @Override public String[] parameterNames() {
        return new String[] {
            "threshold", "ratio", "attack", "release", "knee", "makeup",
        };
    }

    @Override public float parameterMin(String n) {
        switch (n) {
            case "threshold": return -60f;
            case "ratio":     return   1f;
            case "attack":    return 0.1f;
            case "release":   return   5f;
            case "knee":      return   0f;
            case "makeup":    return   0f;
            default:          return   0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "threshold": return    0f;
            case "ratio":     return   40f;
            case "attack":    return  500f;
            case "release":   return 3000f;
            case "knee":      return   24f;
            case "makeup":    return   24f;
            default:          return    1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "threshold": return -18f;
            case "ratio":     return   4f;
            case "attack":    return   5f;
            case "release":   return 120f;
            case "knee":      return   6f;
            case "makeup":    return   4f;
            default:          return   0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "threshold": return "Threshold dB";
            case "ratio":     return "Ratio";
            case "attack":    return "Attack ms";
            case "release":   return "Release ms";
            case "knee":      return "Knee dB";
            case "makeup":    return "Makeup dB";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "threshold": thresholdDb  = clamp(v, -60f,    0f); break;
            case "ratio":     ratio        = clamp(v,   1f,   40f); break;
            case "attack":    attackMs     = clamp(v, 0.1f,  500f); attackCoef  = computeCoef(attackMs); break;
            case "release":   releaseMs    = clamp(v,   5f, 3000f); releaseCoef = computeCoef(releaseMs); break;
            case "knee":      kneeWidthDb  = clamp(v,   0f,   24f); break;
            case "makeup":    makeupGainDb = clamp(v,   0f,   24f); break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        final float halfKnee = kneeWidthDb * 0.5f;
        // Stash to a local so the JIT can keep it in a register.
        float env = envelopeDb;
        for (int i = 0; i < n; i++) {
            float x = input[i];

            // Input level in dB (positive sign; -inf → -120ish via the floor).
            float absSample = Math.abs(x);
            if (absSample < 1e-6f) absSample = 1e-6f;
            float inputDb = 20f * (float) Math.log(absSample) / LN10;

            // Static gain-reduction curve with soft knee.
            float excess = inputDb - thresholdDb;
            float reductionFactor = 1f - 1f / ratio;
            if (reductionFactor < 0f) reductionFactor = 0f;
            float instantGr;
            if (excess <= -halfKnee) {
                instantGr = 0f;
            } else if (excess >= halfKnee) {
                instantGr = excess * reductionFactor;
            } else if (kneeWidthDb > 0f) {
                float kx = excess + halfKnee;
                instantGr = kx * kx / (2f * kneeWidthDb) * reductionFactor;
            } else {
                instantGr = 0f;
            }

            // Asymmetric one-pole follower in dB-of-reduction space.
            if (instantGr > env) {
                env = instantGr + (env - instantGr) * attackCoef;
            } else {
                env = instantGr + (env - instantGr) * releaseCoef;
            }

            float finalGainDb = -env + makeupGainDb;
            float gain = (float) Math.pow(10.0, finalGainDb / 20.0);
            float y = x * gain;
            // Mirror the Kotlin processor: soft-clip the final output.
            if (y >  1f) y =  1f;
            if (y < -1f) y = -1f;
            output[i] = y;
        }
        envelopeDb = env;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
