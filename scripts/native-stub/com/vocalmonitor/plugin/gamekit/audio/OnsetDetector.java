package com.vocalmonitor.plugin.gamekit.audio;

import java.util.Map;

/**
 * Generic onset detector — fires once when the input signal spikes
 * meaningfully above its own slow-moving baseline.  Use for clap /
 * snap / "ka!" detection in rhythm games, or any "sudden loud thing
 * happened" trigger.
 *
 * Same RMS+baseline+refractory approach as {@link
 * com.vocalmonitor.plugin.gamekit.MicTrigger}, but exposes the inner
 * state (instantaneous level, baseline) so callers can build their
 * own visual feedback.  MicTrigger is the "I just want hit()" wrapper;
 * this one is when you want the full readout.
 */
public final class OnsetDetector {

    private float floor = 0.020f;
    private float mult = 3.0f;
    private float refractoryS = 0.12f;

    private float inst = 0f;
    private float baseline = 0f;
    private float refractoryTimer = 0f;
    private boolean pendingHit = false;
    private long hitsTotal = 0;

    public OnsetDetector floor(float v)        { this.floor = v; return this; }
    public OnsetDetector mult(float v)         { this.mult = v; return this; }
    public OnsetDetector refractoryS(float v)  { this.refractoryS = v; return this; }

    /** Feed from `streams["waveform"]`. */
    public void feed(Map<String, float[]> streams, float dt) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        feedSamples(wave, wave == null ? 0 : wave.length, dt);
    }

    public void feedSamples(float[] samples, int len, float dt) {
        if (refractoryTimer > 0f) refractoryTimer -= dt;
        if (samples == null || len == 0) {
            inst *= 0.92f;
            baseline += 0.05f * (inst - baseline);
            return;
        }
        double sumSq = 0.0;
        for (int i = 0; i < len; i++) sumSq += samples[i] * samples[i];
        float rms = (float) Math.sqrt(sumSq / len);
        // Fast attack on instantaneous, slow follow on the baseline.
        inst += 0.50f * (rms - inst);
        baseline += 0.06f * (inst - baseline);
        if (refractoryTimer <= 0f) {
            float thresh = Math.max(baseline * mult, floor);
            if (inst > thresh) {
                pendingHit = true;
                refractoryTimer = refractoryS;
                hitsTotal++;
            }
        }
    }

    /** Consume the pending hit — returns true at most once per onset. */
    public boolean hit() {
        if (pendingHit) { pendingHit = false; return true; }
        return false;
    }

    public float level()    { return inst; }
    public float baseline() { return baseline; }

    /** Current instantaneous level relative to the trigger threshold.
     *  Drive a visual indicator: green at <1, red at ≥1. */
    public float hotness() {
        float thresh = Math.max(baseline * mult, floor);
        return thresh <= 0f ? 0f : inst / thresh;
    }

    /** Running count of detected onsets — useful for "claps so far"
     *  rhythm games. */
    public long count() { return hitsTotal; }

    public void reset() {
        inst = 0f; baseline = 0f; refractoryTimer = 0f;
        pendingHit = false; hitsTotal = 0;
    }
}
