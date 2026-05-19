package com.vocalmonitor.plugin.gamekit.audio;

import java.util.Map;

/**
 * Envelope follower with separate attack / release time constants —
 * the per-frame "how loud is the mic right now" reading every game
 * and meter needs.  Feed it `streams["waveform"]` plus the wall-clock
 * dt; query {@link #level()}.
 *
 * Defaults: fast attack (0.25 per frame), slow release (0.05 per
 * frame).  Tweak via the fluent setters.  Use a faster release for
 * snappy peak meters, slower for sustained-tone games where you
 * want the value to "hang".
 *
 * Stateless across the rest of the kit — one instance per plugin.
 */
public final class RmsFollower {

    private float attack = 0.25f;
    private float release = 0.05f;
    private float envelope = 0f;

    public RmsFollower attack(float a)  { this.attack = a; return this; }
    public RmsFollower release(float r) { this.release = r; return this; }

    /**
     * Pull the latest mic ring from the host's streams map, compute
     * RMS, and step the envelope.  Safe to call with null / empty
     * streams — the envelope just decays toward zero.
     */
    public void feed(Map<String, float[]> streams, float dt) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length == 0) {
            envelope *= 0.92f;
            return;
        }
        double sumSq = 0.0;
        for (int i = 0; i < wave.length; i++) sumSq += wave[i] * wave[i];
        float rms = (float) Math.sqrt(sumSq / wave.length);
        if (rms > envelope) envelope += attack  * (rms - envelope);
        else                envelope += release * (rms - envelope);
    }

    /** Feed from a float array directly — useful when sampling from
     *  something other than streams["waveform"]. */
    public void feedSamples(float[] samples, int len) {
        if (samples == null || len <= 0) { envelope *= 0.92f; return; }
        double sumSq = 0.0;
        for (int i = 0; i < len; i++) sumSq += samples[i] * samples[i];
        float rms = (float) Math.sqrt(sumSq / len);
        if (rms > envelope) envelope += attack  * (rms - envelope);
        else                envelope += release * (rms - envelope);
    }

    /** Current envelope value in [0, 1]-ish (real-world voice
     *  rarely exceeds 0.5 — clamp at the call site if needed). */
    public float level() { return envelope; }

    public void reset() { envelope = 0f; }
}
