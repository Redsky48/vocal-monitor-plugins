package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vocal Load — long-window trend monitor.  NOT a medical
 * diagnosis.  The display answers one question: "is the singer's
 * voice getting tireder vs the start of this session?"
 *
 * Tracked metrics (averaged over 30-second windows, kept for the
 * last ~30 minutes / 60 windows):
 *
 *   - Pitch stability         (cents variance)
 *   - Brightness              (HF energy ratio)
 *   - Breath-to-tone ratio    (HNR via YIN)
 *   - Dynamic control         (RMS variance)
 *
 * The composite "load" score rises when stability / brightness /
 * tone-quality drop relative to the first window of the session.
 * Range 0–100, where 0 = "fresh" and 100 = "you've been singing
 * for two hours straight, take a break".
 */
public final class VocalFatigue
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(loadHist, 0f);
        java.util.Arrays.fill(bandLoStateA, 0f);
        java.util.Arrays.fill(bandHiStateA, 0f);
        ringW = 0; sampleAcc = 0; histW = 0;
        windowAccCount = 0;
        windowSumCentsAbs = 0; windowSumHi = 0; windowSumLo = 0;
        windowSumHnr = 0; windowSumRms = 0; windowSumRmsSq = 0;
        baselineSet = false;
        baselinePitchStd = baselineBrightness = baselineHnr = baselineDr = 0f;
        currentLoad = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // YIN.
    private static final int ANALYSIS_SIZE = 1024;
    private static final int ANALYSIS_HOP  = 512;
    private static final int LAG_MIN = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.15f;
    private static final float A4 = 440f;
    private final float[] audioRing = new float[ANALYSIS_SIZE];
    private final float[] yinBuf = new float[ANALYSIS_SIZE];
    private final float[] yinDiff = new float[LAG_MAX + 1];
    private final float[] yinCMND = new float[LAG_MAX + 1];
    private int ringW = 0, sampleAcc = 0;

    // Brightness via lo (300 Hz) and hi (4 kHz) bandpasses.
    private float[] bandLoCoefs, bandHiCoefs;
    private final float[] bandLoStateA = new float[4];
    private final float[] bandHiStateA = new float[4];
    private float loEnv = 0f, hiEnv = 0f;

    // 30-second window accumulators.
    private static final float WINDOW_SEC = 30f;
    private double windowSumCentsAbs = 0;
    private double windowSumLo = 0, windowSumHi = 0;
    private double windowSumHnr = 0;
    private double windowSumRms = 0, windowSumRmsSq = 0;
    private int windowAccCount = 0;

    // Per-window metrics history.
    private static final int HIST = 60;
    private final float[] loadHist = new float[HIST];
    private int histW = 0;
    private float currentLoad = 0f;
    // Session baseline (first window).
    private boolean baselineSet = false;
    private float baselinePitchStd = 0f, baselineBrightness = 0f;
    private float baselineHnr = 0f, baselineDr = 0f;

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        if (bandLoCoefs == null) {
            bandLoCoefs = bandpass(300f, 1.0f, sampleRate);
            bandHiCoefs = bandpass(4000f, 1.0f, sampleRate);
        }
        float att = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.030));
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % ANALYSIS_SIZE;
            float yl = biquad(s, bandLoCoefs, bandLoStateA);
            float yh = biquad(s, bandHiCoefs, bandHiStateA);
            loEnv += att * ((yl < 0 ? -yl : yl) - loEnv);
            hiEnv += att * ((yh < 0 ? -yh : yh) - hiEnv);
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
        if (rms < 0.003f) return;
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
        if (chosen < 0) return;
        float freq = sampleRate / (float) chosen;
        if (freq < 60f || freq > 1500f) return;
        // Cents-off-nearest-semitone (absolute) for pitch stability.
        double semitones = 12.0 * (Math.log(freq / A4) / Math.log(2.0));
        int midiR = (int) Math.round(69.0 + semitones);
        float centsAbs = (float) Math.abs((69.0 + semitones - midiR) * 100.0);
        // HNR proxy from YIN CMND value.
        float hnr = (float)(10.0 * Math.log10(Math.max(1e-3f, (1f - yinCMND[chosen]) / yinCMND[chosen])));

        windowSumCentsAbs += centsAbs;
        windowSumLo += loEnv;
        windowSumHi += hiEnv;
        windowSumHnr += hnr;
        windowSumRms += rms;
        windowSumRmsSq += rms * rms;
        windowAccCount++;
        // Window closes after WINDOW_SEC of voiced frames.
        // ANALYSIS_HOP / SR seconds per frame.
        float windowFrames = WINDOW_SEC * sampleRate / ANALYSIS_HOP;
        if (windowAccCount >= windowFrames) {
            closeWindow();
        }
    }

    private void closeWindow() {
        if (windowAccCount == 0) return;
        float avgCents = (float)(windowSumCentsAbs / windowAccCount);
        float avgBright = (float)(windowSumHi / Math.max(1, windowSumLo));
        float avgHnr = (float)(windowSumHnr / windowAccCount);
        float avgRms = (float)(windowSumRms / windowAccCount);
        float varRms = (float)(windowSumRmsSq / windowAccCount - avgRms * avgRms);
        if (varRms < 0f) varRms = 0f;
        float dr = (float) Math.sqrt(varRms);
        if (!baselineSet) {
            baselinePitchStd = avgCents;
            baselineBrightness = avgBright;
            baselineHnr = avgHnr;
            baselineDr = dr;
            baselineSet = true;
        }
        // Load: 0 at baseline, climbs as each metric degrades.
        float dPitch = Math.max(0f, (avgCents - baselinePitchStd) / Math.max(1f, baselinePitchStd));
        float dBright = Math.max(0f, (baselineBrightness - avgBright) / Math.max(0.05f, baselineBrightness));
        float dHnr = Math.max(0f, (baselineHnr - avgHnr) / Math.max(1f, baselineHnr));
        float dDr = Math.max(0f, (baselineDr - dr) / Math.max(0.01f, baselineDr));
        currentLoad = Math.min(100f,
                25f * dPitch + 25f * dBright + 25f * dHnr + 25f * dDr);
        loadHist[histW] = currentLoad;
        histW = (histW + 1) % HIST;
        // Reset accumulators for next window.
        windowSumCentsAbs = windowSumLo = windowSumHi = windowSumHnr = 0;
        windowSumRms = windowSumRmsSq = 0;
        windowAccCount = 0;
    }

    private static float biquad(float x, float[] c, float[] st) {
        float y = c[0] * x + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3];
        st[1] = st[0]; st[0] = x;
        st[3] = st[2]; st[2] = y;
        return y;
    }
    private static float[] bandpass(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float)(alpha / a0), 0f, (float)(-alpha / a0),
            (float)(-2.0 * cs / a0), (float)((1.0 - alpha) / a0)
        };
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SIGNATURE   = 0xFFFFA040;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, fillPaint, linePaint;
    private PluginPath linePath, fillPath;

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
        canvas.drawText("VOCAL LOAD", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText("trend vs session start - NOT a medical diagnosis",
                W - 12f, 16f, textDim);

        // Big current load number.
        int col = loadColour(currentLoad);
        textBright.setColor(col).setTextSize(34f).setTextAlign(0);
        canvas.drawText(String.format("%.0f", currentLoad), 12f, 56f, textBright);
        textDim.setColor(col).setTextSize(11f).setTextAlign(0);
        String verdict = currentLoad < 25 ? "FRESH"
                      : currentLoad < 50 ? "WARM"
                      : currentLoad < 75 ? "WORKING" : "TIRED";
        canvas.drawText(verdict, 70f, 56f, textDim);

        // Per-window trend graph.
        float pad = 12f, topY = 70f;
        float plotX0 = pad + 24f, plotX1 = W - pad;
        float plotY0 = topY;
        float plotY1 = H - pad - 14f;
        float plotW = plotX1 - plotX0, plotH = plotY1 - plotY0;
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        for (int s = 0; s <= 100; s += 25) {
            float y = plotY1 - (s / 100f) * plotH;
            gridPaint.setColor(0xFF353638).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            canvas.drawText(s + "", plotX0 - 3f, y + 3f, textDim);
        }
        // Trend bars.
        float step = plotW / (HIST - 1f);
        linePath.reset(); fillPath.reset();
        boolean started = false;
        for (int i = 0; i < HIST; i++) {
            int idx = (histW + i) % HIST;
            float v = loadHist[idx];
            float px = plotX0 + i * step;
            float py = plotY1 - (v / 100f) * plotH;
            if (!started) {
                linePath.moveTo(px, py);
                fillPath.moveTo(px, plotY1).lineTo(px, py);
                started = true;
            } else {
                linePath.lineTo(px, py);
                fillPath.lineTo(px, py);
            }
        }
        fillPath.lineTo(plotX0 + (HIST - 1) * step, plotY1).close();
        fillPaint.setColor(0x44FFA040).setStyle(PluginStyle.FILL);
        canvas.drawPath(fillPath, fillPaint);
        linePaint.setColor(COLOR_SIGNATURE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f);
        canvas.drawPath(linePath, linePaint);

        textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
        canvas.drawText("session start", plotX0, plotY1 + 11f, textDim);
        textDim.setTextAlign(2);
        canvas.drawText("now (30 s windows)", plotX1, plotY1 + 11f, textDim);
    }

    private static int loadColour(float v) {
        if (v < 25f) return 0xFF6FE07A;
        if (v < 50f) return 0xFFF5C842;
        if (v < 75f) return 0xFFFFA040;
        return 0xFFE0606A;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        gridPaint  = c.newPaint();
        fillPaint  = c.newPaint();
        linePaint  = c.newPaint();
        linePath   = c.newPath();
        fillPath   = c.newPath();
    }
}
