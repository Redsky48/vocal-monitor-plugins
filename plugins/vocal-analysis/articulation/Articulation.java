package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Articulation Meter — pro-grade onset detection + consonant
 * classification.
 *
 * Onset detection function (ODF): **complex-domain** Bello/Dixon
 * 2005:
 *
 *   predicted X̂[n,k] = |X[n−1,k]| · exp(j · (2·φ[n−1,k] − φ[n−2,k]))
 *   ODF[n]            = Σ_k | X[n,k] − X̂[n,k] |
 *
 * Captures BOTH magnitude changes (the spectral-flux idea) AND
 * phase deviations (steady-state harmonics → ODF stays low even at
 * loud sustained notes).
 *
 * Adaptive threshold = **100 ms moving median × 1.7 + floor**.
 * Median is robust to occasional outliers; the 1.7× margin keeps
 * normal vibrato modulation under the line.
 *
 * Consonant classifier at onset: HF/LF energy ratio sampled from
 * the FFT at the onset frame.
 *   ratio > 2.5  → /s/ /t/ /k/  (HF — sibilants / unvoiced)
 *   ratio < 0.7  → /b/ /d/ /g/  (LF — voiced plosives)
 *   else         → /m/ /n/ /v/  (mid — nasals / vowels)
 *
 * Most-recent classification is shown next to the title.
 */
public final class Articulation
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(prevMag, 0f);
        java.util.Arrays.fill(prevPhase, 0f);
        java.util.Arrays.fill(prevPrevPhase, 0f);
        java.util.Arrays.fill(odfHist, 0f);
        java.util.Arrays.fill(odfMedianRing, 0f);
        java.util.Arrays.fill(clarityHist, 0f);
        java.util.Arrays.fill(events, 0f);
        ringW = 0; sampleAcc = 0; histW = 0; medianRingW = 0;
        prevOdf = 0f; prevPrevOdf = 0f; lastClass = -1; phraseAvg = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    private static final int FFT_SIZE = 512;
    private static final int HOP = 256;
    private static final int FFT_HALF = FFT_SIZE / 2;
    private final float[] audioRing = new float[FFT_SIZE];
    private final float[] fftRe = new float[FFT_SIZE];
    private final float[] fftIm = new float[FFT_SIZE];
    private final float[] hann  = new float[FFT_SIZE];
    private boolean hannInit = false;
    // Complex-domain ODF state.
    private final float[] prevMag       = new float[FFT_HALF];
    private final float[] prevPhase     = new float[FFT_HALF];
    private final float[] prevPrevPhase = new float[FFT_HALF];
    private int ringW = 0, sampleAcc = 0;

    // ODF ring + 100 ms median (~9 frames @ 11.6 ms/frame).
    private static final int MEDIAN_LEN = 9;
    private final float[] odfMedianRing = new float[MEDIAN_LEN];
    private final float[] medianSort    = new float[MEDIAN_LEN];
    private int medianRingW = 0;
    private float prevOdf = 0f, prevPrevOdf = 0f;

    private static final int HIST_LEN = 256;
    private final float[] odfHist     = new float[HIST_LEN];
    private final float[] clarityHist = new float[HIST_LEN];
    private final float[] events      = new float[HIST_LEN];
    private int histW = 0;
    private float phraseAvg = 0f;

    // Consonant class: 0 = HF (s/t/k), 1 = mid (m/n/v), 2 = LF (b/d/g).
    private int lastClass = -1;
    private static final String[] CLASS_LABEL = { "s / t / k", "m / n / v", "b / d / g" };
    private static final int[] CLASS_COLOUR = { 0xFFE34855, 0xFF4FCB60, 0xFF5BD9E0 };

    // Pass-through + capture into a local ring; analysis runs in
    // render() from streams["waveform"] (preferred) or this ring.
    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % FFT_SIZE;
        }
    }

    private void prepareWindow(java.util.Map<String, float[]> streams) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 64) return;
        int n = wave.length;
        int start = n - FFT_SIZE;
        if (start < 0) {
            int pad = -start;
            for (int i = 0; i < pad; i++) audioRing[i] = 0f;
            for (int i = 0; i < n; i++) audioRing[pad + i] = wave[i];
        } else {
            for (int i = 0; i < FFT_SIZE; i++) audioRing[i] = wave[start + i];
        }
        ringW = 0;
    }

    private void analyseFrame() {
        if (!hannInit) {
            for (int i = 0; i < FFT_SIZE; i++) {
                hann[i] = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
            }
            hannInit = true;
        }
        for (int i = 0; i < FFT_SIZE; i++) {
            int idx = (ringW + i) % FFT_SIZE;
            fftRe[i] = audioRing[idx] * hann[i];
            fftIm[i] = 0f;
        }
        fftRadix2(fftRe, fftIm);
        // Complex-domain ODF.
        float odf = 0f;
        // Track LF and HF energy for the classifier.
        float lfE = 0f, hfE = 0f;
        float binHz = sampleRate / (float) FFT_SIZE;
        for (int b = 1; b < FFT_HALF; b++) {
            float re = fftRe[b], im = fftIm[b];
            float mag = (float) Math.sqrt(re * re + im * im) / FFT_SIZE;
            float ph = (float) Math.atan2(im, re);
            // Predicted phase: linear extrapolation from last two frames.
            float predPh = 2f * prevPhase[b] - prevPrevPhase[b];
            // Wrap into [-π, π].
            float dphi = ph - predPh;
            while (dphi >  (float) Math.PI) dphi -= (float)(2 * Math.PI);
            while (dphi < -(float) Math.PI) dphi += (float)(2 * Math.PI);
            // Predicted complex value: prevMag * exp(j*predPh).
            // Observed: mag * exp(j*ph). Distance |observed - predicted|:
            //   = sqrt(mag² + prevMag² − 2·mag·prevMag·cos(dphi))
            float pm = prevMag[b];
            float dist = (float) Math.sqrt(
                    Math.max(0f, mag * mag + pm * pm - 2f * mag * pm * (float) Math.cos(dphi)));
            // Emphasise mid+HF bins (where consonants live).
            float f = b * binHz;
            if (f > 800f) odf += dist * (f > 4000f ? 1.5f : 1.0f);
            // Energy splits for classifier.
            if (f >= 60f && f <= 1000f) lfE += mag * mag;
            else if (f >= 3000f && f <= 8000f) hfE += mag * mag;
            // Roll state.
            prevPrevPhase[b] = prevPhase[b];
            prevPhase[b] = ph;
            prevMag[b] = mag;
        }
        // 100 ms moving median of ODF.
        odfMedianRing[medianRingW] = odf;
        medianRingW = (medianRingW + 1) % MEDIAN_LEN;
        System.arraycopy(odfMedianRing, 0, medianSort, 0, MEDIAN_LEN);
        java.util.Arrays.sort(medianSort);
        float med = medianSort[MEDIAN_LEN / 2];
        // Adaptive threshold: median × 1.7 + small floor.
        float threshold = Math.max(1e-3f, med * 1.7f);
        // Onset: ODF > threshold AND ODF is a local max (prev < cur > prevPrev).
        boolean isPeak = odf > prevOdf && prevOdf > prevPrevOdf;
        boolean fire = odf > threshold && isPeak;
        // Store + classify.
        odfHist[histW] = odf;
        clarityHist[histW] = Math.min(1f, odf / Math.max(1e-3f, threshold * 2f));
        if (fire) {
            events[histW] = 1f;
            float ratio = lfE > 1e-9f ? hfE / lfE : 10f;
            if (ratio > 2.5f)      lastClass = 0;
            else if (ratio < 0.7f) lastClass = 2;
            else                   lastClass = 1;
        } else {
            events[histW] = 0f;
        }
        histW = (histW + 1) % HIST_LEN;
        prevPrevOdf = prevOdf;
        prevOdf = odf;
        // Phrase average = mean clarity over history.
        float sum = 0f;
        for (float v : clarityHist) sum += v;
        phraseAvg = sum / HIST_LEN;
    }

    private static void fftRadix2(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2;
            double ang = -2.0 * Math.PI / size;
            float wpr = (float) Math.cos(ang);
            float wpi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += size) {
                float wr = 1f, wi = 0f;
                for (int j = 0; j < half; j++) {
                    int k = i + j, kh = k + half;
                    float tr = re[kh] * wr - im[kh] * wi;
                    float ti = re[kh] * wi + im[kh] * wr;
                    re[kh] = re[k] - tr; im[kh] = im[k] - ti;
                    re[k]  = re[k] + tr; im[k]  = im[k] + ti;
                    float nwr = wr * wpr - wi * wpi;
                    wi = wr * wpi + wi * wpr;
                    wr = nwr;
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
    private static final int COLOR_SIGNATURE   = 0xFF4FCB60;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            fillPaint, linePaint, tickPaint;
    private PluginPath linePath, fillPath;

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
        canvas.drawText("ARTICULATION", 12f, 16f, textBright);
        if (lastClass >= 0 && lastClass < CLASS_LABEL.length) {
            textBright.setColor(CLASS_COLOUR[lastClass]).setTextSize(11f).setTextAlign(2);
            canvas.drawText("last: " + CLASS_LABEL[lastClass], W - 12f, 16f, textBright);
        } else {
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(11f).setTextAlign(2);
            canvas.drawText("listening...", W - 12f, 16f, textDim);
        }

        float pad = 12f, headerH = 24f;
        float plotX0 = pad + 24f, plotX1 = W - pad;
        float plotY0 = pad + headerH;
        float plotY1 = H - pad - 14f;
        float plotW = plotX1 - plotX0, plotH = plotY1 - plotY0;

        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        linePath.reset(); fillPath.reset();
        float step = plotW / (HIST_LEN - 1f);
        boolean started = false;
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histW + i) % HIST_LEN;
            float v = clarityHist[idx];
            float px = plotX0 + i * step;
            float py = plotY1 - v * plotH;
            if (!started) {
                linePath.moveTo(px, py);
                fillPath.moveTo(px, plotY1).lineTo(px, py);
                started = true;
            } else {
                linePath.lineTo(px, py);
                fillPath.lineTo(px, py);
            }
        }
        fillPath.lineTo(plotX0 + (HIST_LEN - 1) * step, plotY1).close();
        fillPaint.setColor(0x444FCB60).setStyle(PluginStyle.FILL);
        canvas.drawPath(fillPath, fillPaint);
        linePaint.setColor(COLOR_SIGNATURE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
        canvas.drawPath(linePath, linePaint);

        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histW + i) % HIST_LEN;
            if (events[idx] > 0.5f) {
                tickPaint.setColor(0xFFFFE680).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
                canvas.drawLine(plotX0 + i * step, plotY1 - 4f,
                        plotX0 + i * step, plotY1, tickPaint);
            }
        }
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
        canvas.drawText(String.format("phrase avg %.2f", phraseAvg),
                plotX0, plotY1 + 11f, textDim);
        textDim.setTextAlign(2);
        canvas.drawText("now (complex-domain ODF)", plotX1, plotY1 + 11f, textDim);
        textDim.setColor(0xFFFFE680).setTextAlign(1);
        canvas.drawText("ticks = consonant onsets (adaptive threshold)",
                (plotX0 + plotX1) * 0.5f, plotY1 + 11f, textDim);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        fillPaint  = c.newPaint();
        linePaint  = c.newPaint();
        tickPaint  = c.newPaint();
        linePath   = c.newPath();
        fillPath   = c.newPath();
    }
}
