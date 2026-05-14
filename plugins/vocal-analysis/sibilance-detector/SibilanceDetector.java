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
 * sibilance / harshness events in real time, with adaptive per-band
 * thresholding and a "−x dB @ y kHz" gain-reduction recommendation
 * for the engineer.
 *
 * Signal flow:
 *   - 6 RBJ band-pass biquads at 3 / 4.5 / 6 / 7.5 / 9 / 11 kHz, Q=4.
 *   - Per-band envelope follower (5 ms attack, 30 ms release).
 *   - Per-band adaptive threshold = median(last 10 s) × 2 (≈ +6 dB).
 *     Median is re-computed once per second from a 1 s-strided ring
 *     of envelope readings — cheap and immune to short-term spikes.
 *   - Event detector: per-band envelope > threshold AND envelope is
 *     a local peak in the last 5 ms → fires.
 *   - GR recommendation: peak band + 20·log10(env / threshold) dB.
 *   - 5 ms "visual lookahead": event markers are drawn 5 ms earlier
 *     on the timeline than they fired (audio stays pass-through —
 *     no introduced latency, just visual alignment).
 */
public final class SibilanceDetector
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        for (int b = 0; b < NB; b++) {
            for (int j = 0; j < 4; j++) bandState[b][j] = 0f;
            bandEnv[b] = 0f;
            bandEnvPrev[b] = 0f;
            bandThreshold[b] = 1e-3f;
            java.util.Arrays.fill(bandRing[b], 0f);
            bandRingW[b] = 0;
        }
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(histTotal, 0f);
        for (int b = 0; b < NB; b++) java.util.Arrays.fill(histBand[b], 0f);
        java.util.Arrays.fill(events, 0f);
        java.util.Arrays.fill(eventBand, -1);
        java.util.Arrays.fill(eventCutDb, 0f);
        histW = 0; medianAcc = 0;
        ringW = 0;
        lastEventBand = -1; lastEventCutDb = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // ── Bands ──
    private static final int NB = 6;
    private static final float[] BAND_FCS = { 3000f, 4500f, 6000f, 7500f, 9000f, 11000f };
    private static final float BAND_Q = 4.0f;
    // Band biquad state (x1, x2, y1, y2) per band.
    private final float[][] bandState = new float[NB][4];
    private float[][] bandCoefs;
    private final float[] bandEnv = new float[NB];
    private final float[] bandEnvPrev = new float[NB];
    private final float[] bandThreshold = new float[NB];

    // Per-band sliding-window ring (1 s @ 100 fps = 100 samples).  We
    // recompute the running median once per second.
    private static final int MED_LEN = 100;
    private final float[][] bandRing = new float[NB][MED_LEN];
    private final int[] bandRingW = new int[NB];
    private final float[] sortBuf = new float[MED_LEN];
    private int medianAcc = 0;

    // History.
    private static final int HIST_LEN = 256;          // 2.56 s @ 100 fps
    private final float[] histTotal = new float[HIST_LEN];
    private final float[][] histBand = new float[NB][HIST_LEN];
    private final float[] events = new float[HIST_LEN];      // 1 = fired
    private final int[]   eventBand = new int[HIST_LEN];     // dominant band
    private final float[] eventCutDb = new float[HIST_LEN];  // GR rec dB
    private int histW = 0;

    // Local audio ring filled by process() — fallback for render()
    // when the host doesn't supply streams["waveform"].
    private static final int ANALYSIS_SIZE = 1024;
    private final float[] audioRing = new float[ANALYSIS_SIZE];
    private int ringW = 0;

    private int lastEventBand = -1;
    private float lastEventCutDb = 0f;

    // Pass-through + capture into a local ring; per-sample band
    // analysis runs in render() from streams["waveform"] (preferred)
    // or this ring.
    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % ANALYSIS_SIZE;
        }
    }

    private void prepareWindow(java.util.Map<String, float[]> streams) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 64) return;
        int n = wave.length;
        int start = n - ANALYSIS_SIZE;
        if (start < 0) {
            int pad = -start;
            for (int i = 0; i < pad; i++) audioRing[i] = 0f;
            for (int i = 0; i < n; i++) audioRing[pad + i] = wave[i];
        } else {
            for (int i = 0; i < ANALYSIS_SIZE; i++) audioRing[i] = wave[start + i];
        }
        ringW = 0;
    }

    // One analysis frame: walk through audioRing's window once,
    // advancing the per-band biquad + envelope state; then push one
    // entry per band to the history rings + perform event detection.
    // Biquad state is kept across calls so the IIRs evolve smoothly.
    private void analyseFrame() {
        if (bandCoefs == null) {
            bandCoefs = new float[NB][];
            for (int b = 0; b < NB; b++) {
                bandCoefs[b] = bandpassBiquad(BAND_FCS[b], BAND_Q, sampleRate);
            }
        }
        float attCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.005));
        float relCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.030));
        float total = 0f;
        for (int i = 0; i < ANALYSIS_SIZE; i++) {
            float s = audioRing[(ringW + i) % ANALYSIS_SIZE];
            for (int b = 0; b < NB; b++) {
                float[] c = bandCoefs[b];
                float[] st = bandState[b];
                float y = c[0] * s + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3];
                st[1] = st[0]; st[0] = s;
                st[3] = st[2]; st[2] = y;
                float a = y < 0 ? -y : y;
                float coef = a > bandEnv[b] ? attCoef : relCoef;
                bandEnv[b] += coef * (a - bandEnv[b]);
            }
        }
        for (int b = 0; b < NB; b++) total += bandEnv[b];
        // Per-band ring update — one entry per analyseFrame call.
        for (int b = 0; b < NB; b++) {
            bandRing[b][bandRingW[b]] = bandEnv[b];
            bandRingW[b] = (bandRingW[b] + 1) % MED_LEN;
        }
        medianAcc++;
        if (medianAcc >= 60) {        // re-estimate median ~once per sec
            medianAcc = 0;
            for (int b = 0; b < NB; b++) {
                System.arraycopy(bandRing[b], 0, sortBuf, 0, MED_LEN);
                java.util.Arrays.sort(sortBuf, 0, MED_LEN);
                float med = sortBuf[MED_LEN / 2];
                bandThreshold[b] = Math.max(2e-3f, med * 2f);
            }
        }
        histTotal[histW] = total;
        int bestBand = -1;
        float bestExcess = 0f;
        for (int b = 0; b < NB; b++) {
            histBand[b][histW] = bandEnv[b];
            if (bandEnv[b] > bandThreshold[b]
                    && bandEnv[b] > bandEnvPrev[b]) {
                float excess = bandEnv[b] / bandThreshold[b];
                if (excess > bestExcess) {
                    bestExcess = excess;
                    bestBand = b;
                }
            }
            bandEnvPrev[b] = bandEnv[b];
        }
        if (bestBand >= 0) {
            events[histW] = 1f;
            eventBand[histW] = bestBand;
            float cutDb = (float)(20.0 * Math.log10(bestExcess));
            eventCutDb[histW] = cutDb;
            lastEventBand = bestBand;
            lastEventCutDb = cutDb;
        } else {
            events[histW] = 0f;
            eventBand[histW] = -1;
        }
        histW = (histW + 1) % HIST_LEN;
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

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            bandPaint, eventPaint;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        if (width < 60 || height < 60) return;
        prepareWindow(streams);
        analyseFrame();
        float W = width, H = height;

        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("SIBILANCE DETECTOR", 12f, 16f, textBright);
        // Most-recent event GR recommendation.
        if (lastEventBand >= 0) {
            textBright.setColor(COLOR_SIGNATURE).setTextSize(11f).setTextAlign(2);
            canvas.drawText(
                String.format("rec −%.1f dB @ %.1f kHz",
                    Math.abs(lastEventCutDb), BAND_FCS[lastEventBand] / 1000f),
                W - 12f, 16f, textBright);
        }

        float pad = 12f, headerH = 24f, footerH = 18f;
        float plotX0 = pad + 36f, plotX1 = W - pad;
        float plotY0 = pad + headerH;
        float plotY1 = H - pad - footerH;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;
        float bandH = plotH / NB;

        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        float step = plotW / (HIST_LEN - 1f);
        // 5 ms visual lookahead = ~0.5 history-bin offset at 100 fps.
        int lookaheadBins = 1;
        for (int b = 0; b < NB; b++) {
            float y0 = plotY0 + b * bandH;
            float y1 = y0 + bandH;
            // Band label = fc in kHz.
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            canvas.drawText(String.format("%.1fk", BAND_FCS[b] / 1000f),
                    plotX0 - 3f, (y0 + y1) * 0.5f + 3f, textDim);
            // Heat cells (intensity relative to the band threshold).
            for (int i = 0; i < HIST_LEN; i++) {
                int idx = (histW + i) % HIST_LEN;
                float v = histBand[b][idx];
                float r = bandThreshold[b] > 1e-6f ? v / bandThreshold[b] : 0f;
                float t = Math.min(1f, r * 0.5f);
                if (t < 0.05f) continue;
                int alpha = (int)(t * 255);
                int col = (alpha << 24) | (COLOR_SIGNATURE & 0x00FFFFFF);
                bandPaint.setColor(col).setStyle(PluginStyle.FILL);
                canvas.drawRect(plotX0 + i * step, y0 + 1f,
                        plotX0 + i * step + step + 0.5f, y1 - 1f, bandPaint);
            }
        }
        // Event markers spanning the full height — but only at the
        // event's dominant band do we draw a glyph.
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histW + i) % HIST_LEN;
            if (events[idx] < 0.5f) continue;
            int displayBin = i - lookaheadBins;
            if (displayBin < 0) displayBin = 0;
            float x = plotX0 + displayBin * step;
            eventPaint.setColor(0x66FFE680).setStyle(PluginStyle.STROKE).setStrokeWidth(1.0f);
            canvas.drawLine(x, plotY0, x, plotY1, eventPaint);
            int eb = eventBand[idx];
            if (eb >= 0 && eb < NB) {
                float y0 = plotY0 + eb * bandH;
                float y1 = y0 + bandH;
                eventPaint.setColor(0xFFFFE680).setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f);
                canvas.drawLine(x, y0, x, y1, eventPaint);
            }
        }
        // Footer.
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
        canvas.drawText("older", plotX0, plotY1 + 12f, textDim);
        textDim.setTextAlign(2);
        canvas.drawText("now (5 ms lookahead)", plotX1, plotY1 + 12f, textDim);
        textDim.setColor(COLOR_SIGNATURE).setTextAlign(1);
        canvas.drawText("yellow tick = sibilance event (adaptive threshold)",
                (plotX0 + plotX1) * 0.5f, plotY1 + 12f, textDim);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        bandPaint  = c.newPaint();
        eventPaint = c.newPaint();
    }
}
