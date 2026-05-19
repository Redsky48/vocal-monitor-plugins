package com.vocalmonitor.plugin.gamekit;

import java.util.Map;

/**
 * Live-mic onset detector — fires {@link #hit()} once when the input
 * spikes above its own slow-moving baseline.  Use for chirp / clap /
 * "pa!" / shouted-word style voice triggers in mini-games.
 *
 * Pull samples by calling {@link #feed(Map, float)} once per frame
 * with the host's `streams` map (see voice-analysis plugins —
 * `streams["waveform"]` is the latest mic ring) and the wall-clock
 * dt.  Then poll {@link #hit()} — true once per chirp, then
 * auto-resets after the configured refractory period.
 *
 * Defaults match what AngryChirp ships with:
 *   floor       = 0.015  (absolute RMS minimum so background noise
 *                         doesn't fire)
 *   mult        = 2.5    (RMS must exceed `baseline × mult`)
 *   refractoryS = 0.18s  (cooldown after a hit, so one sustained
 *                         shout fires once, not 60 times)
 *
 * All values are tweakable via setters.  Stateful — one instance
 * per plugin.
 */
public final class MicTrigger {

    private float floor = 0.015f;
    private float mult = 2.5f;
    private float refractoryS = 0.18f;

    private float rms = 0f;
    private float baseline = 0f;
    private float refractoryTimer = 0f;
    private boolean pendingHit = false;

    /** Set the absolute RMS floor — below this nothing fires no matter
     *  what the baseline says.  Lower it (e.g. 0.005) if the kid is
     *  quiet; raise it (0.04) for a loud room. */
    public MicTrigger floor(float v)        { this.floor = v; return this; }

    /** Set the spike-vs-baseline multiplier.  Lower (1.5) = more
     *  sensitive but more false fires; higher (4.0) = needs a sharp
     *  transient. */
    public MicTrigger mult(float v)         { this.mult = v; return this; }

    /** Cooldown after a hit.  Below ~0.1s sustained voice can
     *  re-fire every frame; above ~0.3s repeated chirps feel
     *  unresponsive. */
    public MicTrigger refractoryS(float v)  { this.refractoryS = v; return this; }

    /**
     * Pull the latest mic chunk + advance internal state.  Call once
     * per render frame with the host's streams map and a positive dt
     * in seconds.
     *
     * Safe to call with `streams == null` or an empty/missing
     * waveform key — the detector just decays toward silence.
     */
    public void feed(Map<String, float[]> streams, float dt) {
        if (refractoryTimer > 0f) refractoryTimer -= dt;

        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length == 0) {
            // Smoothly decay so a momentary stream miss doesn't
            // false-fire on the next sample.
            rms *= 0.92f;
            baseline += 0.05f * (rms - baseline);
            return;
        }

        double sumSq = 0.0;
        for (int i = 0; i < wave.length; i++) sumSq += wave[i] * wave[i];
        float inst = (float) Math.sqrt(sumSq / wave.length);

        // Fast attack on the instant RMS, slower follow on the
        // baseline — gives the detector "memory" of the recent ambient
        // level so a sustained tone doesn't keep firing.
        rms += 0.45f * (inst - rms);
        baseline += 0.06f * (rms - baseline);

        if (refractoryTimer <= 0f) {
            boolean above = rms > floor &&
                rms > Math.max(baseline * mult, floor);
            if (above) {
                pendingHit = true;
                refractoryTimer = refractoryS;
            }
        }
    }

    /**
     * Consume the "hit" flag — returns true at most once per detected
     * chirp.  Typical use:
     *
     *     mic.feed(streams, dt);
     *     if (mic.hit()) bird.flap();
     */
    public boolean hit() {
        if (pendingHit) { pendingHit = false; return true; }
        return false;
    }

    /** Smoothed RMS (~0..1).  Drive a meter / level bar with this. */
    public float level() { return rms; }

    /** Slow-moving baseline RMS.  Useful for ambient-noise readouts. */
    public float baseline() { return baseline; }

    /** "Hotness" — current RMS relative to the trigger threshold,
     *  0..1+.  Drive a colour-shift on a mic icon: green at <1,
     *  red at ≥1. */
    public float hotness() {
        float thresh = Math.max(baseline * mult, floor);
        if (thresh <= 0f) return 0f;
        return rms / thresh;
    }

    /** Reset everything — typically called on game restart. */
    public void reset() {
        rms = 0f;
        baseline = 0f;
        refractoryTimer = 0f;
        pendingHit = false;
    }
}
