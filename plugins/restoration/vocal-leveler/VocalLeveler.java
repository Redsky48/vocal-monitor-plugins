package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Vocal Leveler — automated fader-riding. A long-window RMS detector
// measures the running loudness of the input; we compute the gain that
// would push that loudness to the target and smooth it via a per-sample
// IIR with separate "ride up" and "ride down" time constants. The
// result is the same kind of slow, musical level-balancing a mixing
// engineer does by hand on the fader. Waves Vocal Rider and iZotope
// Vocal Volume share this topology.
//
// A noise gate on the detector prevents the leveler from boosting room
// tone or breaths between phrases — only signal above the floor counts
// for the loudness measurement.

public final class VocalLeveler implements VocalMonitorNativePlugin {

    private float rmsSq = 0f;
    private float gain = 1f;
    private int sampleRate = 44100;

    private float target = -18f;     // dB target loudness
    private float maxGain = 12f;     // dB maximum boost
    private float maxCut = 12f;      // dB maximum cut
    private float speed = 0.5f;      // 0 = very slow, 1 = quick

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        rmsSq = 0f; gain = 1f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "target", "maxGain", "maxCut", "speed" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "target":  return -40f;
            case "maxGain": return 0f;
            case "maxCut":  return 0f;
            case "speed":   return 0f;
            default:        return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "target":  return 0f;
            case "maxGain": return 24f;
            case "maxCut":  return 24f;
            case "speed":   return 1f;
            default:        return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "target":  return -18f;
            case "maxGain": return 12f;
            case "maxCut":  return 12f;
            case "speed":   return 0.5f;
            default:        return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "target":  return "Target (dB)";
            case "maxGain": return "Max Boost (dB)";
            case "maxCut":  return "Max Cut (dB)";
            case "speed":   return "Speed";
            default:        return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "target":  target = v; break;
            case "maxGain": maxGain = v; break;
            case "maxCut":  maxCut = v; break;
            case "speed":   speed = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        // RMS window: 100..500 ms depending on speed.
        final float rmsTimeSec = 0.5f - speed * 0.4f;
        final float rmsCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * rmsTimeSec));
        // Ride time: 200..2000 ms — slower than the RMS window so the
        // fader moves don't chase every short-term peak.
        final float rideTimeSec = 2f - speed * 1.8f;
        final float upCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * rideTimeSec));
        final float downCoef = upCoef * 1.5f;   // come down a bit faster
        final float targetLin = (float) Math.pow(10.0, target / 20.0);
        final float maxBoostLin = (float) Math.pow(10.0, maxGain / 20.0);
        final float minBoostLin = (float) Math.pow(10.0, -maxCut / 20.0);
        // Gate floor: below this, signal is too quiet to count toward
        // the loudness estimate. -50 dB picks up most reasonable mic
        // signals but ignores room tone.
        final float gateFloor = 0.003f;
        float rms = rmsSq, g = gain;
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float sq = x * x;
            // Only let signal above the gate floor count toward RMS.
            if (sq > gateFloor * gateFloor) {
                rms = rms + rmsCoef * (sq - rms);
            }
            float currentLoudness = (float) Math.sqrt(rms);
            if (currentLoudness < 1e-6f) currentLoudness = 1e-6f;
            float targetGain = targetLin / currentLoudness;
            if (targetGain > maxBoostLin) targetGain = maxBoostLin;
            if (targetGain < minBoostLin) targetGain = minBoostLin;
            // Asymmetric smoothing: pull up gently, drop a bit faster.
            float coef = targetGain > g ? upCoef : downCoef;
            g = g + coef * (targetGain - g);
            output[i] = x * g;
        }
        rmsSq = rms; gain = g;
    }
}
