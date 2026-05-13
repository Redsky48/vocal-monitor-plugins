package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.BlendMode;
import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Reference example of a canvas-mode visual plugin. Audio is
 * pass-through (no DSP). The panel renders a circular spectrum: each
 * of {@link #BANDS} log-spaced frequency bands becomes a radial bar
 * around the circle, length + colour tracking the band's magnitude
 * computed from the live mic input via Goertzel filters. Released
 * energy fades through a slower trail line so the visualisation feels
 * like a glowing aurora rather than a strobe.
 *
 * Reads the mic from {@code streams["waveform"]} which the host fills
 * with the most recent normalised input window. Placing the plugin
 * anywhere in the effect chain therefore yields the same visual — it
 * always shows what the microphone is feeding into the app, not what
 * upstream nodes have already done to it.
 */
public final class GlowMeter
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private static final int BANDS = 48;
    private static final float MIN_HZ = 60f;
    private static final float MAX_HZ = 14_000f;

    private int sampleRate = 48_000;

    /** Centre frequency per band (Hz), log-spaced 60 Hz → 14 kHz. */
    private final float[] bandFreq = new float[BANDS];
    /** Per-band Goertzel coefficient `2*cos(2*pi*f/fs)`, cached. */
    private final float[] bandCoeff = new float[BANDS];

    /** Smoothed magnitude per band (0..1 after normalisation). */
    private final float[] bandLevel = new float[BANDS];
    /** Slower-decaying trail for the aurora afterglow effect. */
    private final float[] bandTrail = new float[BANDS];

    // Self-filled ring buffer of the most recent audio that passed
    // through process(). render() falls back to this when the host
    // doesn't supply streams["waveform"] — so the plugin animates on
    // any signal flowing through the chain, in any host (test app,
    // future PC DAWs, etc.), regardless of whether host-level streams
    // are wired up.
    private static final int LOCAL_WAV_LEN = 2048;
    private final float[] localWave = new float[LOCAL_WAV_LEN];
    private int localWaveWrite = 0;
    private final float[] scratchWindow = new float[LOCAL_WAV_LEN];

    // ─── Audio interface ──────────────────────────────────────────────

    @Override public void init(int sampleRate) {
        this.sampleRate = sampleRate;
        // Log-space the bands so low freqs get the same angular slice
        // count as the high end — equal information per degree.
        double minLn = Math.log(MIN_HZ);
        double maxLn = Math.log(MAX_HZ);
        for (int b = 0; b < BANDS; b++) {
            double t = b / (double) (BANDS - 1);
            float f = (float) Math.exp(minLn + (maxLn - minLn) * t);
            bandFreq[b] = f;
            double omega = 2.0 * Math.PI * f / sampleRate;
            bandCoeff[b] = (float) (2.0 * Math.cos(omega));
            bandLevel[b] = 0f;
            bandTrail[b] = 0f;
        }
        java.util.Arrays.fill(localWave, 0f);
        localWaveWrite = 0;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String name) { return 0f; }
    @Override public float parameterMax(String name) { return 1f; }
    @Override public float parameterDefault(String name) { return 0f; }
    @Override public String parameterLabel(String name) { return name; }
    @Override public void setParameter(String name, float value) { }

    @Override public void process(float[] input, float[] output) {
        // Pass-through audio, but also tap into the chain so the visual
        // can animate without relying on the host's streams API.
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            localWave[localWaveWrite] = s;
            localWaveWrite++;
            if (localWaveWrite >= LOCAL_WAV_LEN) localWaveWrite = 0;
        }
    }

    // ─── Visual interface ─────────────────────────────────────────────

    private PluginPaint bgPaint;
    private PluginPaint barPaint;
    private PluginPaint trailPaint;
    private PluginPaint centerGlow;
    private PluginPath  scratchPath;

    @Override public void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    ) {
        if (bgPaint == null) {
            bgPaint     = canvas.newPaint();
            barPaint    = canvas.newPaint();
            trailPaint  = canvas.newPaint();
            centerGlow  = canvas.newPaint();
            scratchPath = canvas.newPath();
        }

        // 1. Pull the latest audio window. Prefer the host-supplied
        //    waveform stream when available, otherwise fall back to the
        //    ring of samples process() captured from the live chain.
        //    Either way the plugin animates on real audio whenever the
        //    chain is active.
        float[] wave = streams.get("waveform");
        if (wave == null || wave.length < 64) {
            // Linearise the ring into a contiguous window for analysis.
            int w = localWaveWrite;
            for (int i = 0; i < LOCAL_WAV_LEN; i++) {
                scratchWindow[i] = localWave[(w + i) % LOCAL_WAV_LEN];
            }
            wave = scratchWindow;
        }
        // Detect silence in the chosen window; if the audio's flat,
        // just let levels decay instead of analysing.
        float peak = 0f;
        for (int i = 0; i < wave.length; i++) {
            float a = wave[i] < 0 ? -wave[i] : wave[i];
            if (a > peak) peak = a;
        }
        if (peak > 1e-5f) {
            analyseBands(wave);
        } else {
            for (int b = 0; b < BANDS; b++) bandLevel[b] *= 0.9f;
        }
        // Trail decays slower than the active band envelope.
        for (int b = 0; b < BANDS; b++) {
            if (bandLevel[b] > bandTrail[b]) bandTrail[b] = bandLevel[b];
            else bandTrail[b] *= 0.94f;
        }

        // 2. Background — solid near-black so additive blends light up.
        bgPaint.setColor(0xFF050505).setStyle(PluginStyle.FILL);
        canvas.drawRect(0f, 0f, (float) width, (float) height, bgPaint);

        float cx = width  * 0.5f;
        float cy = height * 0.5f;
        float innerR = Math.min(width, height) * 0.18f;
        float maxBarLen = Math.min(width, height) * 0.30f;
        float barWidth = (float) (2.0 * Math.PI * innerR / BANDS) * 0.45f;

        // Subtle rotation so the spectrum doesn't feel static when the
        // input is steady. ~0.6 rpm — barely there.
        float rotateDeg = (timeMs % 100_000L) * 360f / 100_000f;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(rotateDeg);

        // 3. Trail layer — drawn first under the live bars so the
        //    aurora afterglow sits behind the current spectrum.
        trailPaint
            .setStyle(PluginStyle.STROKE)
            .setStrokeWidth(barWidth * 1.4f)
            .setBlendMode(BlendMode.ADD)
            .setAntialias(true);
        for (int b = 0; b < BANDS; b++) {
            float lvl = bandTrail[b];
            if (lvl <= 0.01f) continue;
            float angle = (b * 360f / BANDS) * (float) Math.PI / 180f;
            float r0 = innerR;
            float r1 = innerR + lvl * maxBarLen * 1.15f;
            float x0 = (float) Math.cos(angle) * r0;
            float y0 = (float) Math.sin(angle) * r0;
            float x1 = (float) Math.cos(angle) * r1;
            float y1 = (float) Math.sin(angle) * r1;
            int trailColor = colorForBand(b, lvl * 0.5f);
            trailPaint.setColor(trailColor).setGlow(trailColor, 18f * lvl);
            canvas.drawLine(x0, y0, x1, y1, trailPaint);
        }

        // 4. Live bars layer — current spectrum, brighter + harder glow.
        barPaint
            .setStyle(PluginStyle.STROKE)
            .setStrokeWidth(barWidth)
            .setBlendMode(BlendMode.ADD)
            .setAntialias(true);
        for (int b = 0; b < BANDS; b++) {
            float lvl = bandLevel[b];
            if (lvl <= 0.005f) continue;
            float angle = (b * 360f / BANDS) * (float) Math.PI / 180f;
            float r0 = innerR;
            float r1 = innerR + lvl * maxBarLen;
            float x0 = (float) Math.cos(angle) * r0;
            float y0 = (float) Math.sin(angle) * r0;
            float x1 = (float) Math.cos(angle) * r1;
            float y1 = (float) Math.sin(angle) * r1;
            int barColor = colorForBand(b, Math.min(1f, lvl + 0.25f));
            barPaint.setColor(barColor).setGlow(barColor, 10f + lvl * 14f);
            canvas.drawLine(x0, y0, x1, y1, barPaint);
        }

        canvas.restore();

        // 5. Centre — a soft pulsing glow disc sized by total energy.
        float total = 0f;
        for (int b = 0; b < BANDS; b++) total += bandLevel[b];
        float pulse = Math.min(1f, total / (BANDS * 0.5f));
        float breath = (float) (Math.sin(timeMs / 700.0) * 0.5 + 0.5);
        float discR = innerR * (0.55f + 0.10f * breath + 0.30f * pulse);
        int[] discColors = new int[] {
            colorWithAlpha(0xFFFFD34A, 180 + (int) (60f * pulse)),
            colorWithAlpha(0xFFFFD34A, 0),
        };
        float[] discStops = new float[] { 0f, 1f };
        centerGlow
            .setStyle(PluginStyle.FILL)
            .setRadialGradient(cx, cy, discR, discColors, discStops)
            .setBlendMode(BlendMode.ADD)
            .setAntialias(true);
        canvas.drawCircle(cx, cy, discR, centerGlow);
    }

    // ─── Band analysis ────────────────────────────────────────────────

    /**
     * Compute Goertzel magnitudes for every band over the input window,
     * normalise, and smooth into {@link #bandLevel}.
     */
    private void analyseBands(float[] wave) {
        int n = wave.length;
        // Calibrated so a -20 dBFS speaking voice (≈ 0.1 peak) fills
        // the aurora to ~70%; quieter still produces visible motion,
        // hot signals settle into the tanh's soft knee at the top.
        float norm = 18f / n;
        for (int b = 0; b < BANDS; b++) {
            float coeff = bandCoeff[b];
            float s0 = 0f, s1 = 0f, s2 = 0f;
            for (int i = 0; i < n; i++) {
                s0 = coeff * s1 - s2 + wave[i];
                s2 = s1; s1 = s0;
            }
            float mag = (float) Math.sqrt(
                Math.max(0f, s1 * s1 + s2 * s2 - coeff * s1 * s2)
            );
            // Pink-noise correction: boost lower bands so the aurora
            // doesn't visually favour the high end (where spectral
            // energy is naturally sparser in voice).
            float pinkBoost = (float) Math.sqrt(bandFreq[b] / 800f);
            if (pinkBoost < 0.35f) pinkBoost = 0.35f;
            float lvl = mag * norm * pinkBoost;
            // Soft compress so the visual doesn't pin to max on loud
            // peaks — gives the aurora some headroom.
            lvl = (float) Math.tanh(lvl * 1.8f);
            // Attack fast, release slow → barbs jump on transients
            // then breathe out.
            float prev = bandLevel[b];
            bandLevel[b] = lvl > prev
                ? prev + (lvl - prev) * 0.55f
                : prev + (lvl - prev) * 0.18f;
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /**
     * Map band index + level to an ARGB colour. Hue spans red→yellow→
     * cyan→violet across the spectrum so the visual reads as a literal
     * frequency rainbow, with saturation + value lifted by [level].
     */
    private static int colorForBand(int bandIdx, float level) {
        float h = bandIdx / (float) (BANDS - 1) * 280f;  // 0=red, 280=violet
        float s = 0.95f;
        float v = 0.35f + 0.65f * Math.max(0f, Math.min(1f, level));
        int rgb = hsvToRgb(h, s, v);
        return 0xFF000000 | rgb;
    }

    private static int hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float hp = h / 60f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r, g, b;
        if (hp < 1)      { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else if (hp < 5) { r = x; g = 0; b = c; }
        else             { r = c; g = 0; b = x; }
        float m = v - c;
        int ri = (int) Math.round((r + m) * 255);
        int gi = (int) Math.round((g + m) * 255);
        int bi = (int) Math.round((b + m) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    private static int colorWithAlpha(int rgb, int alpha) {
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
}
