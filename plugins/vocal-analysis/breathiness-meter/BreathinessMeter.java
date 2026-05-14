package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Breathiness Meter — Cepstral Peak Prominence (Hillenbrand 1994).
 *
 * CPP is the clinical gold standard for breathiness — more robust
 * than HNR, doesn't need a pitch tracker, works on running speech.
 *
 * Algorithm:
 *   1. 1024-pt Hann-windowed FFT of the audio frame.
 *   2. dB magnitude:  S[k] = 20·log10 |X[k]|.
 *   3. Real cepstrum: c[q] = IFFT(S[k])  (symmetric input → IFFT
 *      is just the FFT of the real signal divided by N).
 *   4. In the quefrency range that maps to 60–500 Hz pitch, find
 *      the cepstral peak q_peak and the line of best fit through
 *      c[q] in that range.
 *   5. CPP_dB = c[q_peak] − (regression line evaluated at q_peak).
 *
 * Calibrated thresholds (Heman-Ackah et al. 2014):
 *   CPP ≥ 15 dB → clear / focused
 *   10 .. 15    → normal
 *   5  .. 10    → breathy
 *   CPP < 5     → severely breathy / pressed-noise / whisper
 *
 * HNR (from YIN CMND) is kept as a secondary read-out.
 */
public final class BreathinessMeter
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(hnrHist, 0f);
        java.util.Arrays.fill(cppHist, 0f);
        java.util.Arrays.fill(cepDb, 0f);
        ringW = 0; sampleAcc = 0; histW = 0;
        hnrDb = 0f; cppDb = 0f;
        qPeak = qMin = qMax = 0;
        regSlope = regIntercept = 0;
        voiced = false;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // FFT + YIN config.
    private static final int FFT_N = 1024;
    private static final int FFT_HOP = 512;
    private static final int FFT_HALF = FFT_N / 2;
    private static final int LAG_MIN = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.2f;
    private final float[] audioRing = new float[FFT_N];
    private final float[] yinBuf = new float[FFT_N];
    private final float[] yinDiff = new float[LAG_MAX + 1];
    private final float[] yinCMND = new float[LAG_MAX + 1];
    private final float[] fftRe = new float[FFT_N];
    private final float[] fftIm = new float[FFT_N];
    private final float[] cepIn = new float[FFT_N];
    private final float[] cepInIm = new float[FFT_N];
    private final float[] cepDb = new float[FFT_HALF];
    private final float[] hann = new float[FFT_N];
    {
        for (int i = 0; i < FFT_N; i++) {
            hann[i] = (float)(0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_N - 1))));
        }
    }
    private int ringW = 0, sampleAcc = 0;

    private float cppDb = 0f, hnrDb = 0f;
    private int qPeak = 0, qMin = 0, qMax = 0;
    private double regSlope = 0, regIntercept = 0;
    private boolean voiced = false;

    private static final int HIST_LEN = 256;
    private final float[] hnrHist = new float[HIST_LEN];
    private final float[] cppHist = new float[HIST_LEN];
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
        double energy = 0;
        for (int i = 0; i < FFT_N; i++) {
            int idx = (ringW + i) % FFT_N;
            float v = audioRing[idx];
            yinBuf[i] = v;
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / FFT_N);
        voiced = rms >= 0.003f;
        if (!voiced) {
            cppHist[histW] = 0f;
            hnrHist[histW] = 0f;
            histW = (histW + 1) % HIST_LEN;
            return;
        }
        // ── HNR via YIN (kept as secondary readout) ──
        int half = FFT_N / 2;
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
        float newHnr = 0f;
        if (chosen >= 0) {
            float cmnd = yinCMND[chosen];
            float period = Math.max(1e-4f, 1f - cmnd);
            float noise = Math.max(1e-4f, cmnd);
            newHnr = (float)(10.0 * Math.log10(period / noise));
        }
        hnrDb += 0.4f * (newHnr - hnrDb);
        hnrHist[histW] = hnrDb;

        // ── CPP via real cepstrum ──
        for (int i = 0; i < FFT_N; i++) {
            fftRe[i] = yinBuf[i] * hann[i];
            fftIm[i] = 0f;
        }
        fft(fftRe, fftIm);
        // dB magnitude, mirrored for the IFFT input.
        for (int k = 0; k < FFT_N; k++) {
            int kk = k <= FFT_HALF ? k : FFT_N - k;
            float mag = (float) Math.sqrt(fftRe[kk] * fftRe[kk] + fftIm[kk] * fftIm[kk]);
            cepIn[k] = 20f * (float) Math.log10(Math.max(1e-9f, mag));
            cepInIm[k] = 0f;
        }
        // IFFT of real symmetric data: same forward FFT, take real / N.
        fft(cepIn, cepInIm);
        for (int k = 0; k < FFT_HALF; k++) cepDb[k] = cepIn[k] / FFT_N;
        // Quefrency window: 60..500 Hz pitch → samples = sr/f.
        qMin = Math.max(2, (int) Math.ceil(sampleRate / 500.0));
        qMax = Math.min(FFT_HALF - 1, (int) Math.floor(sampleRate / 60.0));
        if (qMax <= qMin + 4) { cppHist[histW] = 0f; histW = (histW + 1) % HIST_LEN; return; }
        // Linear regression: c[q] = a*q + b.
        double sumQ = 0, sumC = 0, sumQQ = 0, sumQC = 0;
        int nn = qMax - qMin + 1;
        for (int q = qMin; q <= qMax; q++) {
            sumQ += q; sumC += cepDb[q];
            sumQQ += (double)q * q; sumQC += (double)q * cepDb[q];
        }
        double denom = nn * sumQQ - sumQ * sumQ;
        if (Math.abs(denom) < 1e-9) { cppHist[histW] = 0f; histW = (histW + 1) % HIST_LEN; return; }
        regSlope = (nn * sumQC - sumQ * sumC) / denom;
        regIntercept = (sumC - regSlope * sumQ) / nn;
        // Peak in window.
        float peakVal = cepDb[qMin];
        int peakQ = qMin;
        for (int q = qMin; q <= qMax; q++) {
            if (cepDb[q] > peakVal) { peakVal = cepDb[q]; peakQ = q; }
        }
        qPeak = peakQ;
        double predAtPeak = regSlope * peakQ + regIntercept;
        float newCpp = (float)(peakVal - predAtPeak);
        if (newCpp < 0) newCpp = 0;
        cppDb += 0.4f * (newCpp - cppDb);
        cppHist[histW] = cppDb;
        histW = (histW + 1) % HIST_LEN;
    }

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
    private static final int COLOR_SIGNATURE   = 0xFF6DD3E0;
    private static final int COLOR_CLEAR       = 0xFF6FE07A;
    private static final int COLOR_NORMAL      = 0xFF6DD3E0;
    private static final int COLOR_BREATHY     = 0xFFE0A040;
    private static final int COLOR_SEVERE      = 0xFFE0606A;
    private static final int COLOR_GRID        = 0xFF202125;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, cepLine, regLine, peakDot, cppLine;
    private PluginPath cepPath, cppPath;

    private int verdictColor(float cpp) {
        if (cpp >= 15f) return COLOR_CLEAR;
        if (cpp >= 10f) return COLOR_NORMAL;
        if (cpp >= 5f)  return COLOR_BREATHY;
        return COLOR_SEVERE;
    }
    private String verdictLabel(float cpp) {
        if (cpp >= 15f) return "CLEAR";
        if (cpp >= 10f) return "NORMAL";
        if (cpp >= 5f)  return "BREATHY";
        return "SEVERE";
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
        canvas.drawText("BREATHINESS METER", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText("Cepstral Peak Prominence", W - 12f, 16f, textDim);

        float pad = 12f, headerH = 24f;
        // CPP card on the left.
        float cardW = 120f, cardH = 86f;
        float cardX = pad, cardY = pad + headerH;
        int col = verdictColor(cppDb);
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(cardX, cardY, cardX + cardW, cardY + cardH, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(cardX, cardY, cardX + cardW, cardY + cardH, 8f, cardPaint);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("CPP", cardX + 8f, cardY + 14f, textDim);
        textBright.setColor(col).setTextSize(26f).setTextAlign(1);
        canvas.drawText(String.format("%.1f", cppDb), cardX + cardW * 0.5f, cardY + 46f, textBright);
        textDim.setColor(col).setTextSize(11f).setTextAlign(1);
        canvas.drawText("dB", cardX + cardW * 0.5f, cardY + 60f, textDim);
        textBright.setColor(col).setTextSize(10f).setTextAlign(1);
        canvas.drawText(verdictLabel(cppDb), cardX + cardW * 0.5f, cardY + 78f, textBright);

        // HNR secondary readout (small, below CPP card).
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText(String.format("HNR  %.1f dB", hnrDb),
                cardX + 8f, cardY + cardH + 14f, textDim);

        // Cepstrum plot on the right.
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
            // Plot the cepstrum across qMin..qMax with the regression line.
            if (qMax > qMin + 2) {
                float cMin = Float.POSITIVE_INFINITY, cMax = Float.NEGATIVE_INFINITY;
                for (int q = qMin; q <= qMax; q++) {
                    if (cepDb[q] < cMin) cMin = cepDb[q];
                    if (cepDb[q] > cMax) cMax = cepDb[q];
                }
                float rangeC = Math.max(0.001f, cMax - cMin);
                cepPath.reset();
                boolean started = false;
                for (int q = qMin; q <= qMax; q++) {
                    float tx = (q - qMin) / (float)(qMax - qMin);
                    float px = plotX0 + tx * plotW;
                    float py = plotY1 - (cepDb[q] - cMin) / rangeC * plotH;
                    if (!started) { cepPath.moveTo(px, py); started = true; }
                    else cepPath.lineTo(px, py);
                }
                cepLine.setColor(0xFFE6E6EA).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
                canvas.drawPath(cepPath, cepLine);
                // Regression line.
                float r0 = (float)(regSlope * qMin + regIntercept);
                float r1 = (float)(regSlope * qMax + regIntercept);
                float ry0 = plotY1 - (r0 - cMin) / rangeC * plotH;
                float ry1 = plotY1 - (r1 - cMin) / rangeC * plotH;
                regLine.setColor(0xFF8A8B8F).setStyle(PluginStyle.STROKE).setStrokeWidth(1.0f);
                canvas.drawLine(plotX0, ry0, plotX1, ry1, regLine);
                // Peak marker.
                float tx = (qPeak - qMin) / (float)(qMax - qMin);
                float pxMark = plotX0 + tx * plotW;
                float pyMark = plotY1 - (cepDb[qPeak] - cMin) / rangeC * plotH;
                peakDot.setColor(col).setStyle(PluginStyle.FILL);
                canvas.drawCircle(pxMark, pyMark, 4f, peakDot);
                // Pitch readout from peak quefrency.
                float f0Hz = sampleRate / (float) qPeak;
                textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
                canvas.drawText("cepstrum (quefrency window)", plotX0 + 4f, plotY0 + 11f, textDim);
                textDim.setColor(col).setTextAlign(2);
                canvas.drawText(String.format("peak %.0f Hz", f0Hz),
                        plotX1 - 4f, plotY0 + 11f, textDim);
            }
        }

        // CPP history strip below.
        float histY0 = cardY + cardH + 20f;
        float histY1 = H - pad - 4f;
        if (histY1 - histY0 > 26f) {
            cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(pad, histY0, W - pad, histY1, 6f, cardPaint);
            cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(pad, histY0, W - pad, histY1, 6f, cardPaint);
            // Zones: 0-5 severe red, 5-10 breathy orange, 10-15 normal cyan, 15-25 clear green.
            float zoneRange = 25f;
            float plotW = W - pad * 2;
            float plotH = histY1 - histY0;
            float[] zoneBounds = { 0f, 5f, 10f, 15f, 25f };
            int[] zoneCols = { 0x33E0606A, 0x33E0A040, 0x336DD3E0, 0x336FE07A };
            for (int z = 0; z < 4; z++) {
                float y0 = histY1 - (zoneBounds[z + 1] / zoneRange) * plotH;
                float y1 = histY1 - (zoneBounds[z]     / zoneRange) * plotH;
                gridPaint.setColor(zoneCols[z]).setStyle(PluginStyle.FILL);
                canvas.drawRect(pad, y0, W - pad, y1, gridPaint);
            }
            cppPath.reset();
            float step = plotW / (HIST_LEN - 1f);
            boolean started = false;
            for (int i = 0; i < HIST_LEN; i++) {
                int idx = (histW + i) % HIST_LEN;
                float v = cppHist[idx];
                if (v < 0f) v = 0f; if (v > zoneRange) v = zoneRange;
                float px = pad + i * step;
                float py = histY1 - (v / zoneRange) * plotH;
                if (!started) { cppPath.moveTo(px, py); started = true; }
                else cppPath.lineTo(px, py);
            }
            cppLine.setColor(col).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
            canvas.drawPath(cppPath, cppLine);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
            canvas.drawText("CPP history (0..25 dB)", pad + 4f, histY0 + 11f, textDim);
        }
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        gridPaint  = c.newPaint();
        cepLine    = c.newPaint();
        regLine    = c.newPaint();
        peakDot    = c.newPaint();
        cppLine    = c.newPaint();
        cepPath    = c.newPath();
        cppPath    = c.newPath();
    }
}
