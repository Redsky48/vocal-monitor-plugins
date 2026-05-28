package com.vocalmonitor.plugin.gamekit.audio;

import java.util.Map;

/**
 * Fundamental-frequency tracker for vocal range (~55–1000 Hz).
 *
 * Algorithm: YIN-style cumulative-mean-normalized difference function
 * (de Cheveigné & Kawahara) over a rolling ~54 ms window, with
 * parabolic interpolation for sub-sample period accuracy.  Output is
 * smoothed across frames with a configurable follow-rate so the
 * readout glides instead of jittering.
 *
 * <p>This replaced an upward-zero-crossing counter that locked onto
 * harmonics on low / rich notes — it would read an octave high and
 * "stick", refusing to track a glissando downward.  YIN's threshold
 * search picks the shortest plausible period first, which avoids both
 * the octave-up errors of zero-crossing and the octave-down errors of
 * plain autocorrelation.
 *
 * <p>To stay cheap on the audio/render thread the signal is
 * boxcar-decimated (which doubles as an anti-alias filter) before the
 * difference function runs, so the inner loop is a few tens of
 * thousands of multiplies per frame regardless of sample rate.
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
    private float lpCutoff = 800f;   // retained for API compat (anti-alias is the decimator now)
    private float lpAlpha = 0f;
    private float lpPrev = 0f;

    private float floor = 0.008f;    // RMS gate
    private float smoothedHz = 0f;
    private float smoothedRms = 0f;
    private float followRate = 0.30f;
    private float clarity = 0f;      // last detection clarity 0..1 (1 = perfectly periodic)

    private float idleHz = 0f;       // value to drift toward on silence (0 = decay)

    // ── rolling analysis buffer (original-rate, fills across frames) ──
    private static final int RING = 2400;          // ~54 ms @ 44.1 kHz
    private final float[] ring = new float[RING];
    private int ringPos = 0;
    private boolean ringFull = false;

    // scratch (reused each frame; sized for the worst case)
    private final float[] decBuf = new float[RING];
    private final float[] dBuf = new float[RING];
    private final float[] dpBuf = new float[RING];

    private static final float MIN_HZ = 55f;
    private static final float MAX_HZ = 1000f;
    private static final float YIN_THRESHOLD = 0.15f;

    public PitchTracker() { setSampleRate(44_100); }

    public PitchTracker setSampleRate(int sr) {
        this.sampleRate = Math.max(8_000, sr);
        this.lpAlpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * lpCutoff / this.sampleRate));
        return this;
    }

    /** Retained for API compatibility — the decimator now provides the
     *  anti-alias / harmonic suppression, so this no longer affects the
     *  detector.  Kept so existing callers compile and run unchanged. */
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
        if (samples == null || len < 1) {
            decayIdle();
            return;
        }

        // append into the rolling buffer + measure level of this chunk
        double sumSq = 0.0;
        for (int i = 0; i < len; i++) {
            float s = samples[i];
            ring[ringPos] = s;
            if (++ringPos == RING) { ringPos = 0; ringFull = true; }
            sumSq += s * s;
        }
        float rms = (float) Math.sqrt(sumSq / len);
        smoothedRms += 0.30f * (rms - smoothedRms);

        if (rms <= floor || !ringFull) {
            decayIdle();
            return;
        }

        float freq = detect();
        if (freq <= 0f) {                 // not periodic enough → treat as unvoiced
            decayIdle();
            return;
        }

        if (smoothedHz < 50f) smoothedHz = freq;                  // snap on onset
        else                  smoothedHz += followRate * (freq - smoothedHz);
    }

    /** Run YIN over the rolling buffer; returns Hz, or 0 when the
     *  signal isn't periodic enough to trust. */
    private float detect() {
        final int D = sampleRate >= 32_000 ? 4 : (sampleRate >= 16_000 ? 2 : 1);
        final float decRate = sampleRate / (float) D;
        final int M = RING / D;

        // boxcar-decimate the buffer oldest→newest (ringPos is the oldest
        // sample once the ring is full). Averaging D samples anti-aliases.
        final float[] dec = decBuf;
        for (int k = 0; k < M; k++) {
            float acc = 0f;
            int base = ringPos + k * D;
            for (int j = 0; j < D; j++) {
                int idx = base + j;
                if (idx >= RING) idx -= RING;
                acc += ring[idx];
            }
            dec[k] = acc / D;
        }

        final int minLag = Math.max(2, (int) (decRate / MAX_HZ));
        final int maxLag = Math.min(M / 2 - 1, (int) (decRate / MIN_HZ));
        if (maxLag <= minLag + 1) return 0f;
        final int W = M - maxLag;          // integration window

        // difference function d(tau) and cumulative-mean-normalized dp(tau)
        final float[] d = dBuf;
        final float[] dp = dpBuf;
        dp[0] = 1f;
        float running = 0f;
        for (int tau = 1; tau <= maxLag; tau++) {
            float sum = 0f;
            for (int j = 0; j < W; j++) {
                float diff = dec[j] - dec[j + tau];
                sum += diff * diff;
            }
            d[tau] = sum;
            running += sum;
            dp[tau] = running > 0f ? (sum * tau / running) : 1f;
        }

        // first dip below the absolute threshold, descended to its local min
        int tauEst = -1;
        for (int tau = minLag; tau < maxLag; tau++) {
            if (dp[tau] < YIN_THRESHOLD) {
                while (tau + 1 <= maxLag && dp[tau + 1] < dp[tau]) tau++;
                tauEst = tau;
                break;
            }
        }
        if (tauEst < 0) {                  // nothing crossed the threshold → global min
            float best = Float.MAX_VALUE;
            for (int tau = minLag; tau <= maxLag; tau++) {
                if (dp[tau] < best) { best = dp[tau]; tauEst = tau; }
            }
        }

        clarity = 1f - Math.min(1f, dp[tauEst]);
        if (dp[tauEst] > 0.55f) return 0f;  // too aperiodic to trust

        // parabolic interpolation around the chosen minimum
        float betterTau = tauEst;
        if (tauEst > minLag && tauEst < maxLag) {
            float s0 = dp[tauEst - 1], s1 = dp[tauEst], s2 = dp[tauEst + 1];
            float denom = s0 + s2 - 2f * s1;
            if (Math.abs(denom) > 1e-9f) betterTau = tauEst + 0.5f * (s0 - s2) / denom;
        }

        float freq = decRate / betterTau;
        if (freq < MIN_HZ) freq = MIN_HZ;
        if (freq > MAX_HZ) freq = MAX_HZ;
        return freq;
    }

    private void decayIdle() {
        if (idleHz > 0f) smoothedHz += 0.01f * (idleHz - smoothedHz);
        else             smoothedHz *= 0.85f;
    }

    /** Smoothed fundamental in Hz.  ≤ 50 when not voiced. */
    public float hz() { return smoothedHz; }

    /** Smoothed RMS — useful for "is the user singing right now". */
    public float rms() { return smoothedRms; }

    /** Detection clarity 0..1 of the most recent voiced frame
     *  (1 = perfectly periodic). */
    public float clarity() { return clarity; }

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
        clarity = 0f;
        ringPos = 0;
        ringFull = false;
    }
}
