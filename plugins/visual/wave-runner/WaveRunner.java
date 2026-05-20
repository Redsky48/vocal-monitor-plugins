package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Wave Runner — native (Skia) port of the JS Waveform Builder engine.
 *
 * This plugin exists as a performance test: same visual concept as the
 * WebView-based TopVis variant 20, but rendered straight onto the
 * host's Compose canvas via the {@link PluginCanvas} adapter. The
 * canvas is Skia-backed on Android, so the per-frame cost (one
 * rectangle per bar, no JS/V8 round-trip, no glDrawElements pipeline
 * from WebView) should be a fraction of the JS path.
 *
 * Scope of this revision:
 *   • One bars layer, frequency colour mode, center alignment.
 *   • Goertzel spectrum (32 bands) computed from the host's waveform
 *     stream — same approach as the bundled Spectrum plugin.
 *   • Pre-baked HSL→ARGB bar colours so the render loop is pure
 *     drawRect calls with no per-frame allocation.
 *
 * Multi-layer support + line / curve / blend modes can be added
 * once the JS-vs-native gap is confirmed.
 */
public final class WaveRunner
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    // ─── Audio analysis ──────────────────────────────────────────────
    private static final int BANDS = 48;
    private static final int WINDOW = 2048;
    private static final float MIN_HZ = 40f;
    private static final float MAX_HZ = 16_000f;

    private int sampleRate = 44100;

    private final float[] bandFreq  = new float[BANDS];
    private final float[] bandCoeff = new float[BANDS];
    private final float[] bandLevel = new float[BANDS]; // smoothed 0..1
    // Pre-baked frequency-mode colours so the render loop is pure
    // drawRect calls (no HSL math, no string concat).
    private final int[]   barColor  = new int[BANDS];

    private final float[] ring    = new float[WINDOW];
    private int           ringW   = 0;
    private final float[] scratch = new float[WINDOW];
    private final float[] hann    = new float[WINDOW];

    private PluginPaint barPaint;

    // ─── Lifecycle ───────────────────────────────────────────────────

    @Override public void init(int sr) {
        this.sampleRate = sr;
        double minLn = Math.log(MIN_HZ);
        double maxLn = Math.log(MAX_HZ);
        for (int b = 0; b < BANDS; b++) {
            double t = b / (double) (BANDS - 1);
            float f = (float) Math.exp(minLn + (maxLn - minLn) * t);
            bandFreq[b]  = f;
            double omega = 2.0 * Math.PI * f / sr;
            bandCoeff[b] = (float) (2.0 * Math.cos(omega));
            bandLevel[b] = 0f;
            // Frequency colour mode matches wave-engine.js exactly:
            // hue = posFrac * 300°, full saturation, mid lightness.
            float posFrac = b / (float) (BANDS - 1);
            barColor[b] = hslToArgb(posFrac * 300f, 0.95f, 0.6f);
        }
        for (int i = 0; i < WINDOW; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1)));
            ring[i] = 0f;
        }
        ringW = 0;
    }

    @Override public String[] parameterNames()          { return new String[0]; }
    @Override public float    parameterMin(String n)    { return 0f; }
    @Override public float    parameterMax(String n)    { return 1f; }
    @Override public float    parameterDefault(String n){ return 0f; }
    @Override public String   parameterLabel(String n)  { return n; }
    @Override public void     setParameter(String n, float v) { }

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            ring[ringW] = s;
            ringW++;
            if (ringW >= WINDOW) ringW = 0;
        }
    }

    // ─── Visual ──────────────────────────────────────────────────────

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (barPaint == null) {
            barPaint = canvas.newPaint()
                .setStyle(PluginStyle.FILL)
                .setAntialias(false); // antialias adds cost; bars are axis-aligned anyway
        }

        // 1. Pull audio. Prefer host stream so the visual stays in sync
        //    with what the rest of the app sees; otherwise read our own
        //    captured ring.
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 64) {
            int w = ringW;
            for (int i = 0; i < WINDOW; i++) scratch[i] = ring[(w + i) % WINDOW];
            wave = scratch;
        }

        // 2. Cheap silence guard — running the Goertzel inner loop on
        //    zeros wastes CPU and produces NaN-ish artefacts.
        float peak = 0f;
        for (int i = 0; i < wave.length; i++) {
            float a = wave[i] < 0 ? -wave[i] : wave[i];
            if (a > peak) peak = a;
        }
        if (peak > 1e-5f) analyseBands(wave);

        // 3. Bars layer. This is the hot loop — keep it allocation-free.
        final float W = width, H = height;
        final int   N = BANDS;
        final float slot = W / N;
        final float widW = slot * 0.70f;             // width = 70%
        final float gap  = 1f;                       // gap = 1 px
        final float barW = Math.max(1f, widW - gap);
        final float scale = 1.0f;
        final float minH  = 2f;
        for (int b = 0; b < N; b++) {
            float m = bandLevel[b];
            float bh = m * H * scale;
            if (bh < minH) bh = minH;
            if (bh > H)   bh = H;
            float x = b * slot + (slot - barW) * 0.5f;
            float y = (H - bh) * 0.5f; // center align
            barPaint.setColor(barColor[b]);
            canvas.drawRect(x, y, x + barW, y + bh, barPaint);
        }
    }

    // ─── Spectrum analysis ───────────────────────────────────────────
    // Same Goertzel formulation as Spectrum.java — single-bin DFT per
    // band, so band centre frequencies are exact (no log-bin
    // interpolation artefacts). Pink-noise correction + soft tanh
    // compression match the bundled spectrum's calibration so the bar
    // heights feel familiar.

    private void analyseBands(float[] wave) {
        int n = Math.min(wave.length, WINDOW);
        for (int i = 0; i < n; i++) scratch[i] = wave[i] * hann[i];
        float norm = 40f / n;
        for (int b = 0; b < BANDS; b++) {
            float coeff = bandCoeff[b];
            float s1 = 0f, s2 = 0f;
            for (int i = 0; i < n; i++) {
                float s0 = coeff * s1 - s2 + scratch[i];
                s2 = s1; s1 = s0;
            }
            float mag = (float) Math.sqrt(
                Math.max(0f, s1 * s1 + s2 * s2 - coeff * s1 * s2)
            );
            float pinkBoost = (float) Math.sqrt(bandFreq[b] / 1000f);
            if (pinkBoost < 0.3f) pinkBoost = 0.3f;
            float lvl = mag * norm * pinkBoost;
            lvl = (float) Math.tanh(lvl * 2.0f);
            float prev = bandLevel[b];
            // Asymmetric attack/release — matches the JS engine's
            // default of fast attack / slow release for that musical
            // "punch then settle" feel without per-frame allocation.
            bandLevel[b] = lvl > prev
                ? prev + (lvl - prev) * 0.55f
                : prev + (lvl - prev) * 0.22f;
        }
    }

    // ─── HSL → ARGB helper ───────────────────────────────────────────
    // Standard HSL conversion; called once per band at init only, so
    // performance doesn't matter here.
    private static int hslToArgb(float h, float s, float l) {
        h = ((h % 360f) + 360f) % 360f;
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs(((h / 60f) % 2f) - 1f));
        float m = l - c / 2f;
        float r1, g1, b1;
        if      (h < 60f)  { r1 = c; g1 = x; b1 = 0f; }
        else if (h < 120f) { r1 = x; g1 = c; b1 = 0f; }
        else if (h < 180f) { r1 = 0f; g1 = c; b1 = x; }
        else if (h < 240f) { r1 = 0f; g1 = x; b1 = c; }
        else if (h < 300f) { r1 = x; g1 = 0f; b1 = c; }
        else               { r1 = c; g1 = 0f; b1 = x; }
        int r = Math.round((r1 + m) * 255f);
        int g = Math.round((g1 + m) * 255f);
        int bI = Math.round((b1 + m) * 255f);
        if (r < 0) r = 0; else if (r > 255) r = 255;
        if (g < 0) g = 0; else if (g > 255) g = 255;
        if (bI < 0) bI = 0; else if (bI > 255) bI = 255;
        return (0xFF << 24) | (r << 16) | (g << 8) | bI;
    }
}
