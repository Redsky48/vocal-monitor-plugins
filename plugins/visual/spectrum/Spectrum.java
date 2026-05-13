package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Spectrum — 32-band log-spaced spectrum analyser, pass-through audio.
 * Each band is one Goertzel filter (tight, single-bin FFT) so the
 * resolution is exactly where the bar is, not interpolated. Peak-hold
 * markers drop slowly back to the live level so transients leave a
 * visible trail. Yellow-on-black to match the house DAW theme.
 *
 * Captures audio from process() into a ring so the visual works in any
 * host regardless of whether the host wires up streams["waveform"].
 */
public final class Spectrum
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private static final int BANDS = 32;
    private static final float MIN_HZ = 40f;
    private static final float MAX_HZ = 16_000f;
    private static final int WINDOW = 2048;

    private int sampleRate = 44100;

    private final float[] bandFreq = new float[BANDS];
    private final float[] bandCoeff = new float[BANDS];
    private final float[] bandLevel = new float[BANDS];   // 0..1, smoothed
    private final float[] bandPeak = new float[BANDS];    // peak-hold marker

    private final float[] ring = new float[WINDOW];
    private int ringW = 0;
    private final float[] scratch = new float[WINDOW];
    private final float[] hann = new float[WINDOW];

    @Override public void init(int sr) {
        this.sampleRate = sr;
        double minLn = Math.log(MIN_HZ);
        double maxLn = Math.log(MAX_HZ);
        for (int b = 0; b < BANDS; b++) {
            double t = b / (double) (BANDS - 1);
            float f = (float) Math.exp(minLn + (maxLn - minLn) * t);
            bandFreq[b] = f;
            double omega = 2.0 * Math.PI * f / sr;
            bandCoeff[b] = (float) (2.0 * Math.cos(omega));
            bandLevel[b] = 0f;
            bandPeak[b] = 0f;
        }
        for (int i = 0; i < WINDOW; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1)));
            ring[i] = 0f;
        }
        ringW = 0;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            ring[ringW] = s;
            ringW++; if (ringW >= WINDOW) ringW = 0;
        }
    }

    // ---- Visual ----
    private static final int COLOR_BG          = 0xFF050505;
    private static final int COLOR_GRID        = 0xFF18181C;
    private static final int COLOR_TEXT_DIM    = 0xFF6E6E74;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_YELLOW      = 0xFFF5C842;
    private static final int COLOR_YELLOW_LO   = 0xFF9C7E1F;
    private static final int COLOR_PEAK        = 0xFFFFE680;

    private PluginPaint bgPaint, gridPaint, textDim, textBright,
            barPaint, peakPaint;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        final float W = width, H = height;

        // 1. Pick audio window: host stream if present, otherwise local ring.
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 64) {
            int w = ringW;
            for (int i = 0; i < WINDOW; i++) scratch[i] = ring[(w + i) % WINDOW];
            wave = scratch;
        }
        // 2. Detect silence to allow decay rather than analysing zeros.
        float peak = 0f;
        for (int i = 0; i < wave.length; i++) {
            float a = wave[i] < 0 ? -wave[i] : wave[i];
            if (a > peak) peak = a;
        }
        if (peak > 1e-5f) analyseBands(wave);
        // Peak-hold decay: ~0.6 dB per render frame at 60 Hz → ~36 dB/sec
        for (int b = 0; b < BANDS; b++) {
            if (bandLevel[b] > bandPeak[b]) bandPeak[b] = bandLevel[b];
            else bandPeak[b] = Math.max(bandLevel[b], bandPeak[b] - 0.012f);
        }

        // 3. Background
        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);

        // 4. Layout
        float pad = 12f;
        float headerH = 22f;
        float labelH = 16f;
        float plotX0 = pad + 26f;
        float plotY0 = pad + headerH;
        float plotX1 = W - pad;
        float plotY1 = H - pad - labelH;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;
        if (plotW < 50f || plotH < 30f) return;

        // 5. Header label
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("SPECTRUM", pad, pad + 13, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(10f).setTextAlign(2);
        canvas.drawText(String.format("peak %.2f", peak), W - pad, pad + 13, textDim);

        // 6. dB grid + Y-axis labels (0, -20, -40, -60 dBFS-ish on the
        //    band-energy scale; not absolute calibration, but a useful
        //    rough reference).
        gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
        for (int db = 0; db >= -80; db -= 20) {
            float y = plotY0 + (-db) / 80f * plotH;
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
            canvas.drawText(String.valueOf(db), plotX0 - 4, y + 3, textDim);
        }

        // 7. Bars
        float bandSlot = plotW / BANDS;
        float barW = bandSlot * 0.7f;
        for (int b = 0; b < BANDS; b++) {
            float lvl = bandLevel[b];
            float pk = bandPeak[b];
            float x0 = plotX0 + b * bandSlot + (bandSlot - barW) * 0.5f;
            float x1 = x0 + barW;
            float y1 = plotY1;
            float y0 = plotY1 - lvl * plotH;
            // Bar with gradient bottom → top, darker yellow at base.
            barPaint.setStyle(PluginStyle.FILL)
                    .setLinearGradient(x0, y1, x0, plotY0,
                            new int[] { COLOR_YELLOW_LO, COLOR_YELLOW },
                            new float[] { 0f, 1f });
            if (lvl > 0.005f) canvas.drawRect(x0, y0, x1, y1, barPaint);
            // Peak-hold pip
            if (pk > 0.01f) {
                float py = plotY1 - pk * plotH;
                peakPaint.setColor(COLOR_PEAK).setStyle(PluginStyle.FILL);
                canvas.drawRect(x0, py - 1.5f, x1, py + 0.5f, peakPaint);
            }
        }

        // 8. X-axis frequency labels (a sparse set so they don't crowd)
        int[] showHz = { 80, 250, 800, 2500, 8000 };
        for (int hz : showHz) {
            int closest = 0;
            float bestDiff = 1e9f;
            for (int b = 0; b < BANDS; b++) {
                float diff = Math.abs(bandFreq[b] - hz);
                if (diff < bestDiff) { bestDiff = diff; closest = b; }
            }
            float x = plotX0 + closest * bandSlot + bandSlot * 0.5f;
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(1);
            String lbl = hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz);
            canvas.drawText(lbl, x, plotY1 + 12, textDim);
        }
    }

    private void analyseBands(float[] wave) {
        int n = Math.min(wave.length, WINDOW);
        for (int i = 0; i < n; i++) scratch[i] = wave[i] * hann[i];
        // Calibrated so a -20 dBFS vocal (≈ 0.1 peak) hits roughly half
        // the meter; a hot voice clips into the soft-knee at the top.
        // Hann windowing already attenuates by ~6 dB, the bigger boost
        // compensates so the meter stays useful at sane recording levels.
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
            // Pink-noise correction: boost lower bands so a flat-magnitude
            // pink source reads roughly equal across the spectrum.
            float pinkBoost = (float) Math.sqrt(bandFreq[b] / 1000f);
            if (pinkBoost < 0.3f) pinkBoost = 0.3f;
            float lvl = mag * norm * pinkBoost;
            // Soft compress so the visual doesn't pin to max on hot
            // bands — leaves headroom for transients to stand out.
            lvl = (float) Math.tanh(lvl * 2.0f);
            float prev = bandLevel[b];
            bandLevel[b] = lvl > prev
                ? prev + (lvl - prev) * 0.55f
                : prev + (lvl - prev) * 0.22f;
        }
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        gridPaint  = c.newPaint();
        textDim    = c.newPaint();
        textBright = c.newPaint();
        barPaint   = c.newPaint();
        peakPaint  = c.newPaint();
    }
}
