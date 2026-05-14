package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Sibilance Detector — pass-through audio plugin that flags
 * sibilance / harshness events in real time.
 *
 * Signal flow:
 *   - 4 cascaded biquad band-passes split the wet signal into
 *     5-6 kHz, 6-7 kHz, 7-8.5 kHz, 8.5-10 kHz buckets.
 *   - Per-band envelope follower (5 ms attack, 30 ms release).
 *   - Total HF envelope = sum of band envelopes.
 *   - Event detector: fires when total HF envelope rises 6 dB
 *     above its 200 ms slow average → consonant burst.
 *
 * Visualisation:
 *   - Top: scrolling per-band intensity heat (last 2 s).
 *   - Bottom: event timeline with the dominant frequency at each
 *     spike printed alongside it.
 */
public final class SibilanceDetector
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        for (int b = 0; b < NB; b++) {
            for (int j = 0; j < 4; j++) bandState[b][j] = 0f;
            bandEnv[b] = 0f;
        }
        slowEnv = 0f;
        java.util.Arrays.fill(histTotal, 0f);
        for (int b = 0; b < NB; b++) java.util.Arrays.fill(histBand[b], 0f);
        java.util.Arrays.fill(events, 0f);
        histW = 0; eventW = 0;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // ── Bands ──
    private static final int NB = 4;
    private static final float[][] BAND_FCS = {
        {5000, 6000}, {6000, 7000}, {7000, 8500}, {8500, 10000}
    };
    // Band biquad state (x1, x2, y1, y2) per band.
    private final float[][] bandState = new float[NB][4];
    private float[][] bandCoefs;
    private final float[] bandEnv = new float[NB];
    private float slowEnv = 0f;

    // History.
    private static final int HIST_LEN = 256;
    private final float[] histTotal = new float[HIST_LEN];
    private final float[][] histBand = new float[NB][HIST_LEN];
    private int histW = 0;
    // Recent event impulses (1 = fired at that history slot).
    private final float[] events = new float[HIST_LEN];
    private int eventW = 0;

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        if (bandCoefs == null) {
            bandCoefs = new float[NB][];
            for (int b = 0; b < NB; b++) {
                float fc = (BAND_FCS[b][0] + BAND_FCS[b][1]) * 0.5f;
                float bw = BAND_FCS[b][1] - BAND_FCS[b][0];
                bandCoefs[b] = bandpassBiquad(fc, fc / bw, sampleRate);
            }
        }
        float attCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));
        float relCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.030));
        float slowCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.200));
        int histStride = Math.max(1, sampleRate / 100);
        int sampleAcc = 0;
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            // Run all 4 band-passes + envelope follow.
            float total = 0f;
            for (int b = 0; b < NB; b++) {
                float[] c = bandCoefs[b];
                float[] st = bandState[b];
                float y = c[0] * s + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3];
                st[1] = st[0]; st[0] = s;
                st[3] = st[2]; st[2] = y;
                float a = y < 0 ? -y : y;
                float coef = a > bandEnv[b] ? attCoef : relCoef;
                bandEnv[b] += coef * (a - bandEnv[b]);
                total += bandEnv[b];
            }
            slowEnv += slowCoef * (total - slowEnv);
            sampleAcc++;
            if (sampleAcc >= histStride) {
                sampleAcc = 0;
                histTotal[histW] = total;
                for (int b = 0; b < NB; b++) histBand[b][histW] = bandEnv[b];
                // Event detection: 6 dB above slow → fire.
                boolean spike = slowEnv > 1e-6f && total > slowEnv * 2.0f;
                events[histW] = spike ? 1f : 0f;
                histW = (histW + 1) % HIST_LEN;
            }
        }
    }

    private static float[] bandpassBiquad(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) (alpha / a0),
            0f,
            (float) (-alpha / a0),
            (float) (-2.0 * cs / a0),
            (float) ((1.0 - alpha) / a0)
        };
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SIGNATURE   = 0xFFE34855;
    private static final int COLOR_GRID        = 0xFF202125;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, bandPaint, eventPaint;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        if (width < 60 || height < 60) return;
        float W = width, H = height;

        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("SIBILANCE DETECTOR", 12f, 16f, textBright);

        float pad = 12f, headerH = 24f, footerH = 18f;
        float plotX0 = pad + 30f, plotX1 = W - pad;
        float plotY0 = pad + headerH;
        float plotY1 = H - pad - footerH;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;
        float bandH = plotH / NB;

        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        // Per-band heat rows.
        float step = plotW / (HIST_LEN - 1f);
        String[] bandNames = { "5-6k", "6-7k", "7-8.5k", "8.5-10k" };
        for (int b = 0; b < NB; b++) {
            float y0 = plotY0 + b * bandH;
            float y1 = y0 + bandH;
            // Label.
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            canvas.drawText(bandNames[b], plotX0 - 3f, (y0 + y1) * 0.5f + 3f, textDim);
            // Heat cells.
            for (int i = 0; i < HIST_LEN; i++) {
                int idx = (histW + i) % HIST_LEN;
                float v = histBand[b][idx];
                float t = Math.min(1f, v * 20f);
                if (t < 0.05f) continue;
                int alpha = (int)(t * 255);
                int col = (alpha << 24) | (COLOR_SIGNATURE & 0x00FFFFFF);
                bandPaint.setColor(col).setStyle(PluginStyle.FILL);
                canvas.drawRect(plotX0 + i * step, y0 + 1f,
                        plotX0 + i * step + step + 0.5f, y1 - 1f, bandPaint);
            }
            // Event markers — small vertical line on this row when fired.
            for (int i = 0; i < HIST_LEN; i++) {
                int idx = (histW + i) % HIST_LEN;
                if (events[idx] > 0.5f) {
                    eventPaint.setColor(0xFFFFE680).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
                    canvas.drawLine(plotX0 + i * step, y0,
                            plotX0 + i * step, y1, eventPaint);
                }
            }
        }
        // Footer.
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
        canvas.drawText("older", plotX0, plotY1 + 12f, textDim);
        textDim.setTextAlign(2);
        canvas.drawText("now", plotX1, plotY1 + 12f, textDim);
        textDim.setColor(COLOR_SIGNATURE).setTextAlign(1);
        canvas.drawText("yellow tick = sibilance spike",
                (plotX0 + plotX1) * 0.5f, plotY1 + 12f, textDim);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        gridPaint  = c.newPaint();
        bandPaint  = c.newPaint();
        eventPaint = c.newPaint();
    }
}
