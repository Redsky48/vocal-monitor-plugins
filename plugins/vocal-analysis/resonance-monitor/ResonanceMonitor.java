package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Resonance Monitor — Sundberg's Singer's Power Ratio (SPR),
 * measured from the Long-Term Average Spectrum (LTAS).
 *
 *   LTAS         : per-bin 5-second running average of magnitude
 *                  in dB (frame rate ≈ 86 fps with 1024-pt FFT
 *                  at 50 % overlap → 5 s ≈ 430 frames).
 *   SPR (dB)     : peak_dB(2–4 kHz)  −  peak_dB(0–2 kHz)
 *
 * Calibration (Sundberg 1974, Omori 1996):
 *   SPR ≥ −10 dB → operatic / strong ring
 *   −10 .. −20  → trained modern voice
 *   −20 .. −30  → untrained / amateur
 *   SPR < −30   → no ring (pressed / very dark)
 */
public final class ResonanceMonitor
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(ltasDb, -90f);
        java.util.Arrays.fill(sprHist, -60f);
        ringW = 0; sampleAcc = 0; histW = 0;
        ltasReady = false; ltasFrames = 0;
        spr = -60f; peakLowDb = -90f; peakHighDb = -90f;
        peakLowBin = peakHighBin = 0;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // FFT config.
    private static final int FFT_N = 1024;
    private static final int FFT_HOP = 512;
    private static final int FFT_HALF = FFT_N / 2;
    private final float[] audioRing = new float[FFT_N];
    private final float[] fftRe = new float[FFT_N];
    private final float[] fftIm = new float[FFT_N];
    private final float[] hann  = new float[FFT_N];
    {
        for (int i = 0; i < FFT_N; i++) {
            hann[i] = (float)(0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_N - 1))));
        }
    }
    private int ringW = 0, sampleAcc = 0;

    // LTAS (running dB average per bin, 5 s time constant).
    private final float[] ltasDb = new float[FFT_HALF];
    private boolean ltasReady = false;
    private int ltasFrames = 0;

    // SPR readouts.
    private float spr = -60f;
    private float peakLowDb = -90f, peakHighDb = -90f;
    private int peakLowBin = 0, peakHighBin = 0;

    private static final int HIST_LEN = 256;
    private final float[] sprHist = new float[HIST_LEN];
    private int histW = 0;

    // Pass-through + capture into a local ring; analysis runs in
    // render() from streams["waveform"] (preferred) or this ring.
    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % FFT_N;
        }
    }

    private void prepareWindow(java.util.Map<String, float[]> streams) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 64) return;
        int n = wave.length;
        int start = n - FFT_N;
        if (start < 0) {
            int pad = -start;
            for (int i = 0; i < pad; i++) audioRing[i] = 0f;
            for (int i = 0; i < n; i++) audioRing[pad + i] = wave[i];
        } else {
            for (int i = 0; i < FFT_N; i++) audioRing[i] = wave[start + i];
        }
        ringW = 0;
    }

    private void analyseFrame() {
        // Windowed copy into FFT buffer.
        for (int i = 0; i < FFT_N; i++) {
            int idx = (ringW + i) % FFT_N;
            fftRe[i] = audioRing[idx] * hann[i];
            fftIm[i] = 0f;
        }
        fft(fftRe, fftIm);
        // Update LTAS per bin.
        float alpha = 1f / 430f;   // 5 s @ 86 fps
        // First few frames: fill faster so the LTAS rises out of -90 dB.
        if (!ltasReady) alpha = 0.05f;
        for (int k = 0; k < FFT_HALF; k++) {
            float mag = (float) Math.sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]);
            float magDb = 20f * (float) Math.log10(Math.max(1e-9f, mag));
            ltasDb[k] += alpha * (magDb - ltasDb[k]);
        }
        ltasFrames++;
        if (ltasFrames > 50) ltasReady = true;
        // Find peak dB in 0-2 kHz and in 2-4 kHz from the LTAS.
        float binHz = sampleRate / (float) FFT_N;
        int kLowMin  = Math.max(1, (int) Math.floor(60f   / binHz));
        int kLowMax  = Math.min(FFT_HALF - 1, (int) Math.floor(2000f / binHz));
        int kHighMin = Math.min(FFT_HALF - 1, (int) Math.floor(2000f / binHz));
        int kHighMax = Math.min(FFT_HALF - 1, (int) Math.floor(4000f / binHz));
        float pLow = -120f; int iLow = kLowMin;
        for (int k = kLowMin; k <= kLowMax; k++) {
            if (ltasDb[k] > pLow) { pLow = ltasDb[k]; iLow = k; }
        }
        float pHigh = -120f; int iHigh = kHighMin;
        for (int k = kHighMin; k <= kHighMax; k++) {
            if (ltasDb[k] > pHigh) { pHigh = ltasDb[k]; iHigh = k; }
        }
        peakLowDb = pLow; peakLowBin = iLow;
        peakHighDb = pHigh; peakHighBin = iHigh;
        spr = pHigh - pLow;
        sprHist[histW] = spr;
        histW = (histW + 1) % HIST_LEN;
    }

    // In-place radix-2 Cooley-Tukey FFT.  N must be a power of two.
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wRe = (float) Math.cos(ang);
            float wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float wpr = 1f, wpi = 0f;
                int half = len >> 1;
                for (int k = 0; k < half; k++) {
                    int a = i + k, b = a + half;
                    float tr = wpr * re[b] - wpi * im[b];
                    float ti = wpr * im[b] + wpi * re[b];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    float nwpr = wpr * wRe - wpi * wIm;
                    wpi = wpr * wIm + wpi * wRe;
                    wpr = nwpr;
                }
            }
        }
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_BAND_LOW    = 0xFF6DD3E0;   // 0–2 kHz
    private static final int COLOR_BAND_HIGH   = 0xFFF5C842;   // 2–4 kHz (gold)
    private static final int COLOR_OPERATIC    = 0xFFF5C842;   // ≥ −10 dB
    private static final int COLOR_TRAINED     = 0xFF6FE07A;   // −10..−20
    private static final int COLOR_UNTRAINED   = 0xFFE0A040;   // −20..−30
    private static final int COLOR_NO_RING     = 0xFFE0606A;   // < −30

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            ltasLine, lowMark, highMark, sprLine;
    private PluginPath ltasPath, sprPath;

    private int verdictColor(float sprDb) {
        if (sprDb >= -10f) return COLOR_OPERATIC;
        if (sprDb >= -20f) return COLOR_TRAINED;
        if (sprDb >= -30f) return COLOR_UNTRAINED;
        return COLOR_NO_RING;
    }
    private String verdictLabel(float sprDb) {
        if (sprDb >= -10f) return "RING";
        if (sprDb >= -20f) return "TRAINED";
        if (sprDb >= -30f) return "UNTRAINED";
        return "NO RING";
    }

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
        canvas.drawText("RESONANCE MONITOR", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText("Sundberg SPR  (LTAS 5 s)", W - 12f, 16f, textDim);

        float pad = 12f, headerH = 24f;
        // Big SPR readout on the left.
        float cardW = 130f;
        float cardX = pad;
        float cardY0 = pad + headerH;
        float cardH = 86f;
        int col = verdictColor(spr);
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(cardX, cardY0, cardX + cardW, cardY0 + cardH, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(cardX, cardY0, cardX + cardW, cardY0 + cardH, 8f, cardPaint);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("SPR", cardX + 8f, cardY0 + 14f, textDim);
        textBright.setColor(col).setTextSize(26f).setTextAlign(1);
        canvas.drawText(String.format("%+.1f", spr), cardX + cardW * 0.5f, cardY0 + 46f, textBright);
        textDim.setColor(col).setTextSize(11f).setTextAlign(1);
        canvas.drawText("dB", cardX + cardW * 0.5f, cardY0 + 60f, textDim);
        textBright.setColor(col).setTextSize(10f).setTextAlign(1);
        canvas.drawText(verdictLabel(spr), cardX + cardW * 0.5f, cardY0 + 78f, textBright);

        // LTAS plot on the right.
        float plotX0 = cardX + cardW + 12f;
        float plotX1 = W - pad;
        float plotY0 = pad + headerH;
        float plotY1 = pad + headerH + cardH;
        if (plotX1 - plotX0 > 80f) {
            cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(plotX0, plotY0, plotX1, plotY1, 6f, cardPaint);
            cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(plotX0, plotY0, plotX1, plotY1, 6f, cardPaint);
            float plotW = plotX1 - plotX0;
            float plotH = plotY1 - plotY0;
            // Log-Hz axis from 60 Hz to 5500 Hz.
            float fMin = 60f, fMax = 5500f;
            double lmin = Math.log10(fMin), lmax = Math.log10(fMax);
            // Find dB range.
            float dbMin = -90f, dbMax = 0f;
            for (int k = 1; k < FFT_HALF; k++) {
                float d = ltasDb[k];
                if (d > dbMax) dbMax = d;
            }
            dbMin = dbMax - 60f;
            // 2 kHz vertical divider line.
            float binHz = sampleRate / (float) FFT_N;
            float t2k = (float)((Math.log10(2000.0) - lmin) / (lmax - lmin));
            float x2k = plotX0 + t2k * plotW;
            cardPaint.setColor(0xFF353638).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(x2k, plotY0 + 4f, x2k, plotY1 - 4f, cardPaint);
            float t4k = (float)((Math.log10(4000.0) - lmin) / (lmax - lmin));
            float x4k = plotX0 + t4k * plotW;
            canvas.drawLine(x4k, plotY0 + 4f, x4k, plotY1 - 4f, cardPaint);
            // LTAS curve.
            ltasPath.reset();
            boolean started = false;
            for (int k = 1; k < FFT_HALF; k++) {
                float f = k * binHz;
                if (f < fMin || f > fMax) continue;
                double lf = Math.log10(f);
                float px = plotX0 + (float)((lf - lmin) / (lmax - lmin)) * plotW;
                float d = ltasDb[k];
                if (d < dbMin) d = dbMin;
                if (d > dbMax) d = dbMax;
                float py = plotY1 - (d - dbMin) / (dbMax - dbMin) * plotH;
                if (!started) { ltasPath.moveTo(px, py); started = true; }
                else ltasPath.lineTo(px, py);
            }
            ltasLine.setColor(0xFFE6E6EA).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
            canvas.drawPath(ltasPath, ltasLine);
            // Peak markers.
            if (ltasReady) {
                drawPeakMark(canvas, plotX0, plotY0, plotW, plotH, lmin, lmax, dbMin, dbMax,
                        peakLowBin * binHz, peakLowDb, COLOR_BAND_LOW, "0-2k", lowMark);
                drawPeakMark(canvas, plotX0, plotY0, plotW, plotH, lmin, lmax, dbMin, dbMax,
                        peakHighBin * binHz, peakHighDb, COLOR_BAND_HIGH, "2-4k", highMark);
            }
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
            canvas.drawText("LTAS (log Hz)", plotX0 + 4f, plotY0 + 11f, textDim);
        }

        // SPR history at the bottom.
        float histY0 = pad + headerH + cardH + 10f;
        float histY1 = H - pad - 4f;
        if (histY1 - histY0 > 26f) {
            cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(pad, histY0, W - pad, histY1, 6f, cardPaint);
            cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(pad, histY0, W - pad, histY1, 6f, cardPaint);
            float plotW = W - pad * 2;
            float plotH = histY1 - histY0;
            float step = plotW / (HIST_LEN - 1f);
            // dB range −60..0
            sprPath.reset();
            boolean started = false;
            for (int i = 0; i < HIST_LEN; i++) {
                int idx = (histW + i) % HIST_LEN;
                float v = sprHist[idx];
                if (v < -60f) v = -60f; if (v > 0f) v = 0f;
                float px = pad + i * step;
                float py = histY1 - ((v + 60f) / 60f) * plotH;
                if (!started) { sprPath.moveTo(px, py); started = true; }
                else sprPath.lineTo(px, py);
            }
            sprLine.setColor(col).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
            canvas.drawPath(sprPath, sprLine);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
            canvas.drawText("SPR history (−60..0 dB)", pad + 4f, histY0 + 11f, textDim);
        }
    }

    private void drawPeakMark(PluginCanvas canvas, float x0, float y0,
                               float w, float h, double lmin, double lmax,
                               float dbMin, float dbMax,
                               float freqHz, float dbVal, int colour,
                               String label, PluginPaint paint) {
        if (freqHz <= 0f) return;
        double lf = Math.log10(Math.max(20f, freqHz));
        float px = x0 + (float)((lf - lmin) / (lmax - lmin)) * w;
        float d = Math.max(dbMin, Math.min(dbMax, dbVal));
        float py = (y0 + h) - (d - dbMin) / (dbMax - dbMin) * h;
        paint.setColor(colour).setStyle(PluginStyle.FILL);
        canvas.drawCircle(px, py, 4f, paint);
        paint.setColor(colour).setTextSize(8.5f).setTextAlign(1);
        canvas.drawText(label, px, py - 6f, paint);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        ltasLine   = c.newPaint();
        lowMark    = c.newPaint();
        highMark   = c.newPaint();
        sprLine    = c.newPaint();
        ltasPath   = c.newPath();
        sprPath    = c.newPath();
    }
}
