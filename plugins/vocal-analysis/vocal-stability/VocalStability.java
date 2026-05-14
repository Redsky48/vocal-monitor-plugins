package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vocal Stability — composite measurement combining six sub-scores
 * over a rolling 2-second window:
 *
 *   - Pitch stability  : 100 - clamp(cents-stdev * 5)
 *   - Jitter (local)   : Praat-style |T_i - T_{i-1}| / mean(T) %
 *   - Shimmer (local)  : Praat-style |20·log10(A_i/A_{i-1})| dB
 *   - Tone stability   : real-FFT spectral centroid stdev
 *   - Volume stability : RMS-dB stdev
 *   - Break score      : penalises mid-sustain unvoiced flips.
 *
 * Composite weighting:
 *   0.30 pitch + 0.15 jitter + 0.15 shimmer
 *   + 0.15 tone + 0.15 volume + 0.10 breaks.
 */
public final class VocalStability
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(centsRing, Float.NaN);
        java.util.Arrays.fill(centroidRing, Float.NaN);
        java.util.Arrays.fill(rmsRing, Float.NaN);
        java.util.Arrays.fill(periodRing, Float.NaN);
        java.util.Arrays.fill(peakRing, Float.NaN);
        ringW = 0; sampleAcc = 0; histW = 0;
        wasVoiced = false; breakCount = 0;
        pitchScore = toneScore = volScore = breakScore = 0f;
        jitterScore = shimmerScore = overallScore = 0f;
        jitterPct = 0f; shimmerDb = 0f;
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

    // Real FFT buffers (used for the *real* spectral centroid).
    private static final int FFT_N = ANALYSIS_SIZE;       // 1024
    private static final int FFT_HALF = FFT_N / 2;
    private final float[] fftRe = new float[FFT_N];
    private final float[] fftIm = new float[FFT_N];
    private final float[] hann  = new float[FFT_N];
    {
        for (int i = 0; i < FFT_N; i++) {
            hann[i] = (float)(0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_N - 1))));
        }
    }

    // 2-second ring of measurements (170 frames @ 12ms).
    private static final int HIST = 170;
    private final float[] centsRing    = new float[HIST];   // NaN if unvoiced
    private final float[] centroidRing = new float[HIST];   // NaN if unvoiced
    private final float[] rmsRing      = new float[HIST];
    private final float[] periodRing   = new float[HIST];   // period in samples
    private final float[] peakRing     = new float[HIST];   // peak |x| of last period
    private int histW = 0;
    private boolean wasVoiced = false;
    private int breakCount = 0;

    // Scores (0..100) and raw readouts.
    private float pitchScore = 0f, toneScore = 0f, volScore = 0f, breakScore = 0f;
    private float jitterScore = 0f, shimmerScore = 0f;
    private float jitterPct = 0f, shimmerDb = 0f;
    private float overallScore = 0f;
    private static final int OVERALL_HIST = 256;
    private final float[] overallHist = new float[OVERALL_HIST];
    private int histWO = 0;

    // Pass-through + capture into a local ring; analysis runs in
    // render() from streams["waveform"] (preferred) or this ring.
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
            centsRing[histW]    = Float.NaN;
            centroidRing[histW] = Float.NaN;
            rmsRing[histW]      = Float.NaN;
            periodRing[histW]   = Float.NaN;
            peakRing[histW]     = Float.NaN;
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
            // Parabolic interpolation for sub-sample period estimate.
            float refined = chosen;
            if (chosen > 1 && chosen < maxLag - 1) {
                float s0 = yinCMND[chosen - 1];
                float s1 = yinCMND[chosen];
                float s2 = yinCMND[chosen + 1];
                float denom = (s0 - 2f * s1 + s2);
                if (Math.abs(denom) > 1e-12f) {
                    refined = chosen + 0.5f * (s0 - s2) / denom;
                }
            }
            float freq = sampleRate / refined;
            double semitones = 12.0 * (Math.log(freq / A4) / Math.log(2.0));
            int midiR = (int) Math.round(69.0 + semitones);
            float cents = (float) ((69.0 + semitones - midiR) * 100.0);
            centsRing[histW]  = cents;
            periodRing[histW] = refined;
            // Peak amplitude across the most recent pitch period — used
            // for shimmer.  Period sits at the END of the analysis
            // window (most recent samples).
            int periodLen = Math.max(1, Math.round(refined));
            int start = ANALYSIS_SIZE - periodLen;
            float pk = 0f;
            for (int j = start; j < ANALYSIS_SIZE; j++) {
                float a = Math.abs(yinBuf[j]);
                if (a > pk) pk = a;
            }
            peakRing[histW] = pk;
        } else {
            centsRing[histW]  = Float.NaN;
            periodRing[histW] = Float.NaN;
            peakRing[histW]   = Float.NaN;
        }
        // Real spectral centroid via radix-2 FFT on Hann-windowed frame.
        // (Replaces the previous buggy time-domain "centroid".)
        for (int i = 0; i < FFT_N; i++) {
            fftRe[i] = yinBuf[i] * hann[i];
            fftIm[i] = 0f;
        }
        fft(fftRe, fftIm);
        double cWeighted = 0, cTotal = 0;
        float binHz = sampleRate / (float) FFT_N;
        for (int k = 1; k < FFT_HALF; k++) {
            float mag = (float) Math.sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]);
            cWeighted += k * binHz * mag;
            cTotal    += mag;
        }
        centroidRing[histW] = (float)(cTotal > 1e-9 ? cWeighted / cTotal : 0);
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
        // Jitter (local, Praat-style): mean(|T_i - T_{i-1}|) / mean(T).
        // Walks the periodRing in chronological order (oldest → newest).
        double jSumDiff = 0, jSumT = 0; int jN = 0;
        float prevT = Float.NaN;
        for (int i = 0; i < HIST; i++) {
            int idx = (histW + i) % HIST;
            float t = periodRing[idx];
            if (Float.isNaN(t)) { prevT = Float.NaN; continue; }
            jSumT += t; jN++;
            if (!Float.isNaN(prevT)) {
                jSumDiff += Math.abs(t - prevT);
            }
            prevT = t;
        }
        if (jN > 2 && jSumT > 1e-9) {
            float meanT = (float)(jSumT / jN);
            jitterPct = (float)((jSumDiff / Math.max(1, jN - 1)) / meanT * 100.0);
            // Score: 0% → 100, ≥3% → 0.  Clinical "normal" voice < 1.04%.
            jitterScore = clamp01(1f - jitterPct / 3f) * 100f;
        } else {
            jitterPct = 0f; jitterScore = 0f;
        }
        // Shimmer (local, Praat-style): mean(|20·log10(A_i/A_{i-1})|).
        double sSumDb = 0; int sN = 0;
        float prevA = Float.NaN;
        for (int i = 0; i < HIST; i++) {
            int idx = (histW + i) % HIST;
            float a = peakRing[idx];
            if (Float.isNaN(a) || a < 1e-6f) { prevA = Float.NaN; continue; }
            if (!Float.isNaN(prevA) && prevA > 1e-6f) {
                sSumDb += Math.abs(20.0 * Math.log10(a / prevA));
                sN++;
            }
            prevA = a;
        }
        if (sN > 0) {
            shimmerDb = (float)(sSumDb / sN);
            // 0 dB → 100, ≥2 dB → 0.  Clinical "normal" < 0.35 dB.
            shimmerScore = clamp01(1f - shimmerDb / 2f) * 100f;
        } else {
            shimmerDb = 0f; shimmerScore = 0f;
        }
        // Centroid stdev (real FFT now — calibrated for Hz units).
        double sumX = 0, sumSqX = 0; int nX = 0;
        for (float c : centroidRing) {
            if (Float.isNaN(c) || c <= 0f) continue;
            sumX += c; sumSqX += c * c; nX++;
        }
        if (nX > 1) {
            double mean = sumX / nX;
            double var = sumSqX / nX - mean * mean;
            if (var < 0) var = 0;
            float stdev = (float) Math.sqrt(var);
            // 0 Hz stdev → 100, 800 Hz stdev → 0.
            toneScore = clamp01(1f - stdev / 800f) * 100f;
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
        // Composite: pitch 30, jitter 15, shimmer 15, tone 15, vol 15, breaks 10.
        overallScore = 0.30f * pitchScore
                     + 0.15f * jitterScore
                     + 0.15f * shimmerScore
                     + 0.15f * toneScore
                     + 0.15f * volScore
                     + 0.10f * breakScore;
        // Decay break count slowly so a long stable run recovers.
        if (breakCount > 0 && Math.random() < 0.005) breakCount--;
        overallHist[histWO] = overallScore;
        histWO = (histWO + 1) % OVERALL_HIST;
    }

    // In-place radix-2 Cooley-Tukey FFT.  N must be a power of two.
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        // Bit-reverse.
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
        prepareWindow(streams);
        analyseFrame();
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

        // Right: 6 sub-score bars.
        float subX0 = ringCx + ringR + 24f;
        float subX1 = W - pad;
        float subBarH = 14f;
        float subGap = 4f;
        float subY0 = headerH + pad;
        String[] labels = { "PITCH", "JITTER", "SHIMMER", "TONE", "VOLUME", "BREAKS" };
        float[]  scores = { pitchScore, jitterScore, shimmerScore,
                            toneScore,  volScore,    breakScore };
        for (int i = 0; i < 6; i++) {
            float y0 = subY0 + i * (subBarH + subGap);
            drawSubBar(canvas, subX0, y0, subX1, y0 + subBarH, labels[i], scores[i]);
        }

        // Bottom: overall score history + jitter/shimmer raw readouts.
        float subEndY = subY0 + 6 * (subBarH + subGap) - subGap;
        float ringBottom = ringCy + ringR + 22f;
        float plotY0 = Math.max(subEndY, ringBottom) + 6f;
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
            // Raw jitter / shimmer readouts in the top-left of the
            // history strip — useful for clinical interpretation.
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
            canvas.drawText(
                String.format("J %.2f%%   S %.2f dB", jitterPct, shimmerDb),
                pad + 6f, plotY0 + 12f, textDim);
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
        canvas.drawText(label, x0 + 6f, (y0 + y1) * 0.5f + 3f, textBright);
        textBright.setColor(col).setTextAlign(2);
        canvas.drawText(String.format("%.0f", score), x1 - 6f, (y0 + y1) * 0.5f + 3f, textBright);
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
