package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vocal Stability — composite measurement combining four sub-scores
 * over a rolling 2-second window:
 *
 *   - Pitch stability  : 100 - clamp(cents-stdev * 5)
 *   - Tone stability   : 100 - clamp(spectral-centroid-stdev / 30)
 *   - Volume stability : 100 - clamp(RMS-dB-stdev * 10)
 *   - Break score      : 100 - clamp(numBreaks * 30) — counts how
 *                        many times the YIN tracker went unvoiced
 *                        mid-sustained-note.
 *
 * The overall stability score is the weighted average:
 *   overall = 0.4 * pitch + 0.25 * tone + 0.20 * vol + 0.15 * break.
 */
public final class VocalStability
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(centsRing, Float.NaN);
        java.util.Arrays.fill(centroidRing, 0f);
        java.util.Arrays.fill(rmsRing, 0f);
        ringW = 0; sampleAcc = 0; histW = 0;
        wasVoiced = false; breakCount = 0;
        pitchScore = toneScore = volScore = breakScore = overallScore = 0f;
        java.util.Arrays.fill(overallHist, 0f);
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // YIN config.
    private static final int ANALYSIS_SIZE = 1024;
    private static final int ANALYSIS_HOP  = 512;        // ~12 ms / frame
    private static final int LAG_MIN = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.15f;
    private static final float A4 = 440f;
    private final float[] audioRing = new float[ANALYSIS_SIZE];
    private final float[] yinBuf = new float[ANALYSIS_SIZE];
    private final float[] yinDiff = new float[LAG_MAX + 1];
    private final float[] yinCMND = new float[LAG_MAX + 1];
    private int ringW = 0, sampleAcc = 0;

    // 2-second ring of measurements (170 frames @ 12ms).
    private static final int HIST = 170;
    private final float[] centsRing    = new float[HIST];   // NaN if unvoiced
    private final float[] centroidRing = new float[HIST];
    private final float[] rmsRing      = new float[HIST];
    private int histW = 0;
    private boolean wasVoiced = false;
    private int breakCount = 0;

    // Scores (0..100).
    private float pitchScore = 0f, toneScore = 0f, volScore = 0f, breakScore = 0f;
    private float overallScore = 0f;
    private static final int OVERALL_HIST = 256;
    private final float[] overallHist = new float[OVERALL_HIST];

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
        boolean voiced = rms >= 0.003f;
        if (!voiced) {
            if (wasVoiced) breakCount++;
            centsRing[histW] = Float.NaN;
            centroidRing[histW] = Float.NaN;
            rmsRing[histW] = Float.NaN;
            histW = (histW + 1) % HIST;
            wasVoiced = false;
            computeScores();
            return;
        }
        wasVoiced = true;
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
        if (chosen >= 0) {
            float freq = sampleRate / (float) chosen;
            double semitones = 12.0 * (Math.log(freq / A4) / Math.log(2.0));
            int midiR = (int) Math.round(69.0 + semitones);
            float cents = (float) ((69.0 + semitones - midiR) * 100.0);
            centsRing[histW] = cents;
        } else {
            centsRing[histW] = Float.NaN;
        }
        // Spectral centroid via simple weighted sum on the YIN buffer
        // (cheap proxy: use audioRing energy distribution, not real
        // FFT — adequate for variance comparisons).
        double weighted = 0, total = 0;
        for (int b = 1; b < half; b++) {
            float v = Math.abs(yinBuf[b]) + Math.abs(yinBuf[b + 1]);
            weighted += b * v;
            total += v;
        }
        centroidRing[histW] = (float)(total > 1e-9 ? weighted / total : 0);
        rmsRing[histW] = 20f * (float) Math.log10(Math.max(1e-9f, rms));
        histW = (histW + 1) % HIST;
        computeScores();
    }

    private void computeScores() {
        // Pitch stdev (only voiced frames).
        double sumC = 0, sumSqC = 0; int nC = 0;
        for (float c : centsRing) {
            if (Float.isNaN(c)) continue;
            sumC += c; sumSqC += c * c; nC++;
        }
        if (nC > 1) {
            double mean = sumC / nC;
            double var = sumSqC / nC - mean * mean;
            if (var < 0) var = 0;
            float stdev = (float) Math.sqrt(var);
            pitchScore = clamp01(1f - stdev / 25f) * 100f;
        }
        // Centroid stdev.
        double sumX = 0, sumSqX = 0; int nX = 0;
        for (float c : centroidRing) {
            if (c <= 0f) continue;
            sumX += c; sumSqX += c * c; nX++;
        }
        if (nX > 1) {
            double mean = sumX / nX;
            double var = sumSqX / nX - mean * mean;
            if (var < 0) var = 0;
            float stdev = (float) Math.sqrt(var);
            toneScore = clamp01(1f - stdev / 30f) * 100f;
        }
        // RMS dB stdev.
        double sumR = 0, sumSqR = 0; int nR = 0;
        for (float r : rmsRing) {
            if (Float.isNaN(r) || r < -60f) continue;
            sumR += r; sumSqR += r * r; nR++;
        }
        if (nR > 1) {
            double mean = sumR / nR;
            double var = sumSqR / nR - mean * mean;
            if (var < 0) var = 0;
            float stdev = (float) Math.sqrt(var);
            volScore = clamp01(1f - stdev / 10f) * 100f;
        }
        // Break score: based on running count, decay slowly.
        breakScore = clamp01(1f - breakCount / 6f) * 100f;
        // Composite.
        overallScore = 0.40f * pitchScore + 0.25f * toneScore
                     + 0.20f * volScore   + 0.15f * breakScore;
        // Decay break count slowly so a long stable run recovers.
        if (breakCount > 0 && Math.random() < 0.005) breakCount--;
        // Push to history.
        overallHist[histWO] = overallScore;
        histWO = (histWO + 1) % OVERALL_HIST;
    }
    private int histWO = 0;

    private static float clamp01(float v) {
        if (v < 0f) return 0f; if (v > 1f) return 1f; return v;
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SIGNATURE   = 0xFFA060E0;
    private static final int COLOR_GREEN       = 0xFF6FE07A;
    private static final int COLOR_YELLOW      = 0xFFF5C842;
    private static final int COLOR_RED         = 0xFFE0606A;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            ringBg, ringFg, barBg, barFg;

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
        canvas.drawText("VOCAL STABILITY", 12f, 16f, textBright);

        float pad = 12f, headerH = 24f;
        // Big overall score ring on the left.
        float ringR = Math.min(H * 0.30f, 60f);
        float ringCx = pad + ringR + 20f;
        float ringCy = headerH + pad + ringR + 8f;
        ringBg.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(8f);
        canvas.drawCircle(ringCx, ringCy, ringR, ringBg);
        int col = scoreColour(overallScore);
        ringFg.setColor(col).setStyle(PluginStyle.STROKE).setStrokeWidth(8f);
        float a0 = (float) Math.PI * 0.75f;
        float a1 = a0 + (float)(2.0 * Math.PI * 0.7f) * (overallScore / 100f);
        int segs = Math.max(2, (int)((a1 - a0) * 20));
        float pxP = ringCx + ringR * (float) Math.cos(a0);
        float pyP = ringCy + ringR * (float) Math.sin(a0);
        for (int i = 1; i <= segs; i++) {
            float t = i / (float) segs;
            float a = a0 + (a1 - a0) * t;
            float px = ringCx + ringR * (float) Math.cos(a);
            float py = ringCy + ringR * (float) Math.sin(a);
            canvas.drawLine(pxP, pyP, px, py, ringFg);
            pxP = px; pyP = py;
        }
        textBright.setColor(col).setTextSize(22f).setTextAlign(1);
        canvas.drawText(String.format("%.0f", overallScore), ringCx, ringCy + 5f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(1);
        canvas.drawText("STABILITY", ringCx, ringCy + ringR + 16f, textDim);

        // Right: 4 sub-score bars.
        float subX0 = ringCx + ringR + 24f;
        float subX1 = W - pad;
        float subBarH = 18f;
        float subGap = 6f;
        float subY0 = headerH + pad;
        drawSubBar(canvas, subX0, subY0,                      subX1, subY0 + subBarH,
                "PITCH",  pitchScore);
        drawSubBar(canvas, subX0, subY0 + (subBarH + subGap), subX1, subY0 + 2 * subBarH + subGap,
                "TONE",   toneScore);
        drawSubBar(canvas, subX0, subY0 + 2 * (subBarH + subGap), subX1, subY0 + 3 * subBarH + 2 * subGap,
                "VOLUME", volScore);
        drawSubBar(canvas, subX0, subY0 + 3 * (subBarH + subGap), subX1, subY0 + 4 * subBarH + 3 * subGap,
                "BREAKS", breakScore);

        // Bottom: overall score history.
        float plotY0 = ringCy + ringR + 28f;
        float plotY1 = H - pad - 4f;
        if (plotY1 - plotY0 > 30f) {
            cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(pad, plotY0, W - pad, plotY1, 6f, cardPaint);
            cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(pad, plotY0, W - pad, plotY1, 6f, cardPaint);
            float plotW = W - pad * 2;
            float plotH = plotY1 - plotY0;
            float step = plotW / (OVERALL_HIST - 1f);
            for (int i = 0; i < OVERALL_HIST; i++) {
                int idx = (histWO + i) % OVERALL_HIST;
                float v = overallHist[idx];
                if (v <= 0f) continue;
                float px = pad + i * step;
                float py = plotY1 - (v / 100f) * plotH;
                cardPaint.setColor(scoreColour(v)).setStyle(PluginStyle.FILL);
                canvas.drawRect(px, py, px + step + 0.5f, plotY1, cardPaint);
            }
        }
    }

    private void drawSubBar(PluginCanvas canvas, float x0, float y0, float x1, float y1,
                             String label, float score) {
        barBg.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0, x1, y1, 4f, barBg);
        int col = scoreColour(score);
        float fx = x0 + (x1 - x0) * (score / 100f);
        barFg.setColor(col).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0, fx, y1, 4f, barFg);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(9f).setTextAlign(0);
        canvas.drawText(label, x0 + 6f, (y0 + y1) * 0.5f + 4f, textBright);
        textBright.setColor(col).setTextAlign(2);
        canvas.drawText(String.format("%.0f", score), x1 - 6f, (y0 + y1) * 0.5f + 4f, textBright);
    }

    private static int scoreColour(float s) {
        if (s >= 75f) return COLOR_GREEN;
        if (s >= 50f) return COLOR_YELLOW;
        return COLOR_RED;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        ringBg     = c.newPaint();
        ringFg     = c.newPaint();
        barBg      = c.newPaint();
        barFg      = c.newPaint();
    }
}
