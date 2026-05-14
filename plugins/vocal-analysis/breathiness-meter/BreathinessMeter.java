package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Breathiness Meter — estimates the Harmonic-to-Noise Ratio (HNR)
 * of the voice using the YIN autocorrelation peak: at the chosen
 * lag, the periodic component is `1 - CMND(tau)`; the noise floor
 * is `CMND(tau)`.  HNR (dB) ≈ 10·log10(period / noise).
 *
 * Maps HNR to a 5-step classification:
 *   > 22 dB → Pressed  (tight, locked tone)
 *   14-22  → Clean    (typical good voice)
 *    8-14  → Airy     (slight breath colour)
 *    3- 8  → Breathy  (lots of breath energy)
 *   <  3  → Noisy    (whisper / very breathy)
 *
 * The categories are STYLE labels, not quality grades. The plugin
 * deliberately doesn't say "good" or "bad".
 */
public final class BreathinessMeter
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(hnrHist, 0f);
        ringW = 0; sampleAcc = 0; histW = 0;
        hnrDb = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // YIN scaffolding.
    private static final int ANALYSIS_SIZE = 1024;
    private static final int ANALYSIS_HOP  = 512;
    private static final int LAG_MIN = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.2f;
    private final float[] audioRing = new float[ANALYSIS_SIZE];
    private final float[] yinBuf = new float[ANALYSIS_SIZE];
    private final float[] yinDiff = new float[LAG_MAX + 1];
    private final float[] yinCMND = new float[LAG_MAX + 1];
    private int ringW = 0, sampleAcc = 0;

    private float hnrDb = 0f;
    private static final int HIST_LEN = 256;
    private final float[] hnrHist = new float[HIST_LEN];
    private int histW = 0;

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % ANALYSIS_SIZE;
            sampleAcc++;
            if (sampleAcc >= ANALYSIS_HOP) {
                sampleAcc = 0;
                analyseFrame();
            }
        }
    }

    private void analyseFrame() {
        double energy = 0;
        for (int i = 0; i < ANALYSIS_SIZE; i++) {
            int idx = (ringW + i) % ANALYSIS_SIZE;
            float v = audioRing[idx];
            yinBuf[i] = v;
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / ANALYSIS_SIZE);
        if (rms < 0.003f) {
            hnrHist[histW] = 0f;
            histW = (histW + 1) % HIST_LEN;
            return;
        }
        int half = ANALYSIS_SIZE / 2;
        int maxLag = Math.min(half, LAG_MAX);
        for (int tau = 1; tau <= maxLag; tau++) {
            float sum = 0f;
            for (int j = 0; j < half; j++) {
                float d = yinBuf[j] - yinBuf[j + tau];
                sum += d * d;
            }
            yinDiff[tau] = sum;
        }
        yinCMND[0] = 1f;
        float running = 0f;
        for (int tau = 1; tau <= maxLag; tau++) {
            running += yinDiff[tau];
            yinCMND[tau] = running > 1e-12f ? yinDiff[tau] * tau / running : 1f;
        }
        int chosen = -1;
        for (int tau = LAG_MIN; tau < maxLag - 1; tau++) {
            if (yinCMND[tau] < YIN_THRESHOLD) {
                while (tau + 1 < maxLag && yinCMND[tau + 1] < yinCMND[tau]) tau++;
                chosen = tau;
                break;
            }
        }
        if (chosen < 0) {
            // No clear pitch → entire signal counts as noise → very low HNR.
            hnrDb = 0f;
            hnrHist[histW] = 0f;
            histW = (histW + 1) % HIST_LEN;
            return;
        }
        // HNR = period / noise = (1 - cmnd) / cmnd in linear; dB → 10·log10.
        float cmnd = yinCMND[chosen];
        float period = Math.max(1e-4f, 1f - cmnd);
        float noise = Math.max(1e-4f, cmnd);
        float newHnr = (float)(10.0 * Math.log10(period / noise));
        // Smooth lightly.
        hnrDb += 0.4f * (newHnr - hnrDb);
        hnrHist[histW] = hnrDb;
        histW = (histW + 1) % HIST_LEN;
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SIGNATURE   = 0xFF6DD3E0;
    private static final int COLOR_GRID        = 0xFF202125;

    private static final String[] LABELS = { "NOISY", "BREATHY", "AIRY", "CLEAN", "PRESSED" };
    private static final float[] THRESH  = { 3f, 8f, 14f, 22f };

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, hnrLine, hnrFill, classPaint;
    private PluginPath hnrPath, hnrFillPath;

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
        canvas.drawText("BREATHINESS METER", 12f, 16f, textBright);

        // Current classification.
        int cls = classifyHnr(hnrDb);
        String label = LABELS[cls];
        textBright.setColor(COLOR_SIGNATURE).setTextSize(14f).setTextAlign(2);
        canvas.drawText(String.format("%s   %.1f dB HNR", label, hnrDb),
                W - 12f, 17f, textBright);

        float pad = 12f, headerH = 24f;
        float labelStripH = 24f;
        float plotX0 = pad + 24f, plotX1 = W - pad;
        float plotY0 = pad + headerH;
        float plotY1 = H - pad - labelStripH;
        float plotW = plotX1 - plotX0, plotH = plotY1 - plotY0;

        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        // HNR axis: 0..30 dB
        for (int db = 0; db <= 30; db += 5) {
            float y = plotY1 - (db / 30f) * plotH;
            gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            if (db % 10 == 0) {
                textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
                canvas.drawText(db + " dB", plotX0 - 3f, y + 3f, textDim);
            }
        }

        // HNR contour.
        hnrPath.reset();
        hnrFillPath.reset();
        float step = plotW / (HIST_LEN - 1f);
        boolean started = false;
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histW + i) % HIST_LEN;
            float v = hnrHist[idx];
            if (v < 0f) v = 0f; if (v > 30f) v = 30f;
            float px = plotX0 + i * step;
            float py = plotY1 - (v / 30f) * plotH;
            if (!started) {
                hnrPath.moveTo(px, py);
                hnrFillPath.moveTo(px, plotY1).lineTo(px, py);
                started = true;
            } else {
                hnrPath.lineTo(px, py);
                hnrFillPath.lineTo(px, py);
            }
        }
        hnrFillPath.lineTo(plotX0 + (HIST_LEN - 1) * step, plotY1).close();
        hnrFill.setColor(0x336DD3E0).setStyle(PluginStyle.FILL);
        canvas.drawPath(hnrFillPath, hnrFill);
        hnrLine.setColor(COLOR_SIGNATURE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
        canvas.drawPath(hnrPath, hnrLine);

        // 5 classification labels — horizontal strip across the bottom.
        float stripY0 = plotY1 + 4f;
        float stripY1 = H - pad;
        float w = (plotX1 - plotX0) / 5f;
        int[] cols = { 0xFFE0606A, 0xFFE0C040, 0xFF6DD3E0, 0xFF6FE07A, 0xFFA060E0 };
        for (int i = 0; i < 5; i++) {
            float x0 = plotX0 + i * w;
            float x1 = x0 + w - 2f;
            classPaint.setColor((cols[i] & 0x00FFFFFF) | (i == cls ? 0xCC000000 : 0x33000000))
                    .setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, stripY0, x1, stripY1, 4f, classPaint);
            textDim.setColor(i == cls ? 0xFF101010 : COLOR_TEXT_DIM)
                    .setTextSize(8f).setTextAlign(1);
            canvas.drawText(LABELS[i], (x0 + x1) * 0.5f, (stripY0 + stripY1) * 0.5f + 3f, textDim);
        }
    }

    private static int classifyHnr(float dB) {
        if (dB < THRESH[0]) return 0;
        if (dB < THRESH[1]) return 1;
        if (dB < THRESH[2]) return 2;
        if (dB < THRESH[3]) return 3;
        return 4;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint     = c.newPaint();
        cardPaint   = c.newPaint();
        textBright  = c.newPaint();
        textDim     = c.newPaint();
        gridPaint   = c.newPaint();
        hnrLine     = c.newPaint();
        hnrFill     = c.newPaint();
        classPaint  = c.newPaint();
        hnrPath     = c.newPath();
        hnrFillPath = c.newPath();
    }
}
