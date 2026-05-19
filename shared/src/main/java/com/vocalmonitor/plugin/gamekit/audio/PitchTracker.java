package com.vocalmonitor.plugin.gamekit.audio;

import java.util.Map;

/**
 * Fundamental-frequency tracker for vocal range (~60–800 Hz).
 *
 * Algorithm: one-pole low-pass at 800 Hz to suppress upper harmonics,
 * count upward zero-crossings of the filtered signal, divide by
 * window duration.  Output is smoothed across frames with a
 * configurable follow-rate so the readout glides instead of
 * jittering.  Cents-accurate it is NOT — for tuner-grade pitch
 * (sub-1¢) you'd want autocorrelation or YIN.  For "is the voice
 * going up or down" and ±50¢ training games, this is plenty.
 *
 * Backed by the same code AngryChirp / rocket-pitch / pitch-arrow /
 * target-tone / scale-runner all duplicate today — extracted here
 * so they can stop.
 *
 * Usage:
 *   tracker.feed(streams, dt);
 *   if (tracker.voiced()) {
 *       float hz = tracker.hz();
 *       float cents = tracker.centsFrom(440f);
 *   }
 */
public final class PitchTracker {

    private int sampleRate = 44_100;
    private float lpCutoff = 800f;
    private float lpAlpha = 0f;
    private float lpPrev = 0f;

    private float floor = 0.008f;    // RMS gate
    private float smoothedHz = 0f;
    private float smoothedRms = 0f;
    private float followRate = 0.30f;

    private float idleHz = 0f;       // value to drift toward on silence (0 = decay)

    public PitchTracker() { setSampleRate(44_100); }

    public PitchTracker setSampleRate(int sr) {
        this.sampleRate = Math.max(8_000, sr);
        this.lpAlpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * lpCutoff / this.sampleRate));
        return this;
    }

    /** Override the LP cutoff used to suppress upper harmonics.
     *  Defaults to 800 Hz — raise it for higher-register voices, lower
     *  for deep voices. */
    public PitchTracker lpCutoff(float hz) {
        this.lpCutoff = hz;
        this.lpAlpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * lpCutoff / sampleRate));
        return this;
    }

    /** RMS below this is treated as silence — the pitch readout
     *  decays toward {@link #idleHz}. */
    public PitchTracker floor(float rmsFloor)  { this.floor = rmsFloor; return this; }

    /** 0..1 follow rate for the smoothed pitch.  Lower = more
     *  glide, higher = more responsive. */
    public PitchTracker followRate(float r)    { this.followRate = r; return this; }

    /** Where the smoothed pitch drifts to when the voice goes
     *  silent.  Default 0 (decays to zero); set 220 / 440 etc. for
     *  games that want a neutral resting position. */
    public PitchTracker idleHz(float hz)       { this.idleHz = hz; return this; }

    /** Pull the latest mic chunk + advance state.  Use this as the
     *  standard entry point in render(). */
    public void feed(Map<String, float[]> streams, float dt) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        feedSamples(wave, wave == null ? 0 : wave.length);
    }

    /** Feed from a raw float array (for offline use). */
    public void feedSamples(float[] samples, int len) {
        if (samples == null || len < 16) {
            decayIdle();
            return;
        }
        int upwardZc = 0;
        boolean wasPositive = lpPrev >= 0f;
        double sumSq = 0.0;
        for (int i = 0; i < len; i++) {
            lpPrev += lpAlpha * (samples[i] - lpPrev);
            boolean isPositive = lpPrev >= 0f;
            if (isPositive && !wasPositive) upwardZc++;
            wasPositive = isPositive;
            sumSq += samples[i] * samples[i];
        }
        float rms = (float) Math.sqrt(sumSq / len);
        smoothedRms += 0.30f * (rms - smoothedRms);

        if (rms > floor) {
            float duration = len / (float) sampleRate;
            float p = upwardZc / duration;
            if (p < 60f)  p = 60f;
            if (p > 800f) p = 800f;
            smoothedHz += followRate * (p - smoothedHz);
        } else {
            decayIdle();
        }
    }

    private void decayIdle() {
        if (idleHz > 0f) smoothedHz += 0.01f * (idleHz - smoothedHz);
        else             smoothedHz *= 0.85f;
    }

    /** Smoothed fundamental in Hz.  ≤ 50 when not voiced. */
    public float hz() { return smoothedHz; }

    /** Smoothed RMS — useful for "is the user singing right now". */
    public float rms() { return smoothedRms; }

    /** True when the user is actually voicing (RMS > floor + pitch
     *  in vocal range). */
    public boolean voiced() { return smoothedHz > 50f && smoothedRms > floor * 0.6f; }

    /**
     * Cent error of the current pitch from a target Hz.  Positive =
     * sharp, negative = flat.  Returns 0 when not voiced (so HUDs
     * showing "+0 cents" don't look wrong during silence — callers
     * should also check {@link #voiced()}).
     */
    public float centsFrom(float targetHz) {
        if (!voiced() || targetHz <= 0f) return 0f;
        return (float) (1200.0 * Math.log(smoothedHz / targetHz) / Math.log(2.0));
    }

    public void reset() {
        lpPrev = 0f;
        smoothedHz = 0f;
        smoothedRms = 0f;
    }
}
