package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Articulation Meter — measures diction clarity via two signals:
 *
 *   1. Spectral flux: per-FFT-frame sum of positive bin energy
 *      changes vs the previous frame. Spikes when content suddenly
 *      shifts (consonant onset).
 *   2. HF transient energy: fast-vs-slow RMS envelope ratio of the
 *      band-passed signal above 2 kHz. Spikes on plosives + fricatives.
 *
 * Onset events are marked when EITHER signal exceeds its slow
 * running baseline by 6 dB. The render shows a scrolling timeline
 * of the combined "clarity intensity" with onset ticks, plus a
 * 4-second average for phrase-level reading.
 */
public final class Articulation
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(prevMag, 0f);
        java.util.Arrays.fill(clarityHist, 0f);
        java.util.Arrays.fill(events, 0f);
        for (int j = 0; j < 4; j++) hfState[j] = 0f;
        fastEnv = slowEnv = slowFlux = 0f;
        ringW = 0; sampleAcc = 0; histW = 0;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    private static final int FFT_SIZE = 512;
    private static final int HOP = 256;
    private final float[] audioRing = new float[FFT_SIZE];
    private final float[] fftRe = new float[FFT_SIZE];
    private final float[] fftIm = new float[FFT_SIZE];
    private final float[] hann = new float[FFT_SIZE];
    private boolean hannInit = false;
    private final float[] prevMag = new float[FFT_SIZE / 2];
    private int ringW = 0, sampleAcc = 0;

    // HF transient detector.
    private float[] hfCoefs;
    private final float[] hfState = new float[4];
    private float fastEnv = 0f, slowEnv = 0f;

    private float slowFlux = 0f;
    private static final int HIST_LEN = 256;
    private final float[] clarityHist = new float[HIST_LEN];
    private final float[] events = new float[HIST_LEN];
    private int histW = 0;
    private float phraseAvg = 0f;

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        if (hfCoefs == null) hfCoefs = highPass(2000f, 0.7f, sampleRate);
        float fastC = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.0015));
        float slowC = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.060));
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % FFT_SIZE;
            // HF transient follow.
            float hf = biquad(s, hfCoefs, hfState);
            float a = hf < 0 ? -hf : hf;
            fastEnv += fastC * (a - fastEnv);
            slowEnv += slowC * (a - slowEnv);
            sampleAcc++;
            if (sampleAcc >= HOP) {
                sampleAcc = 0;
                analyseFrame();
            }
        }
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
        // Spectral flux: sum of positive magnitude differences.
        float flux = 0f;
        for (int b = 1; b < FFT_SIZE / 2; b++) {
            float mag = (float) Math.sqrt(fftRe[b] * fftRe[b] + fftIm[b] * fftIm[b]) / FFT_SIZE;
            float diff = mag - prevMag[b];
            if (diff > 0f) flux += diff;
            prevMag[b] = mag;
        }
        // Slow baseline (300 ms).
        slowFlux += 0.05f * (flux - slowFlux);
        // Combined clarity intensity: weighted sum of normalised flux
        // + HF transient ratio.
        float hfRatio = slowEnv > 1e-6f ? fastEnv / slowEnv : 0f;
        float clarity = Math.min(1f, flux * 30f) * 0.5f
                       + Math.min(1f, Math.max(0f, hfRatio - 1f) * 0.5f) * 0.5f;
        clarityHist[histW] = clarity;
        // Event detection.
        boolean fluxSpike = slowFlux > 1e-6f && flux > slowFlux * 2.5f;
        boolean hfSpike   = hfRatio > 1.5f;
        events[histW] = (fluxSpike || hfSpike) ? 1f : 0f;
        histW = (histW + 1) % HIST_LEN;
        // Phrase average (last 256 frames).
        float sum = 0f;
        for (float v : clarityHist) sum += v;
        phraseAvg = sum / HIST_LEN;
    }

    private static float biquad(float x, float[] c, float[] st) {
        float y = c[0] * x + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3];
        st[1] = st[0]; st[0] = x;
        st[3] = st[2]; st[2] = y;
        return y;
    }
    private static float[] highPass(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double a0 = 1 + alpha;
        return new float[] {
            (float)((1 + cs) * 0.5 / a0),
            (float)(-(1 + cs) / a0),
            (float)((1 + cs) * 0.5 / a0),
            (float)(-2 * cs / a0),
            (float)((1 - alpha) / a0)
        };
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
    private static final int COLOR_SIGNATURE   = 0xFF4FCB60; // mint
    private static final int COLOR_GRID        = 0xFF202125;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, fillPaint, linePaint, tickPaint;
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
        canvas.drawText("ARTICULATION", 12f, 16f, textBright);
        textDim.setColor(COLOR_SIGNATURE).setTextSize(11f).setTextAlign(2);
        canvas.drawText(String.format("phrase avg %.2f", phraseAvg),
                W - 12f, 16f, textDim);

        float pad = 12f, headerH = 24f;
        float plotX0 = pad + 24f, plotX1 = W - pad;
        float plotY0 = pad + headerH;
        float plotY1 = H - pad - 14f;
        float plotW = plotX1 - plotX0, plotH = plotY1 - plotY0;

        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        // Clarity contour (filled).
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

        // Onset ticks along the bottom edge.
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histW + i) % HIST_LEN;
            if (events[idx] > 0.5f) {
                tickPaint.setColor(0xFFFFE680).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
                canvas.drawLine(plotX0 + i * step, plotY1 - 4f,
                        plotX0 + i * step, plotY1, tickPaint);
            }
        }

        textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
        canvas.drawText("older", plotX0, plotY1 + 11f, textDim);
        textDim.setTextAlign(2);
        canvas.drawText("now", plotX1, plotY1 + 11f, textDim);
        textDim.setColor(0xFFFFE680).setTextAlign(1);
        canvas.drawText("ticks = consonant onsets",
                (plotX0 + plotX1) * 0.5f, plotY1 + 11f, textDim);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        gridPaint  = c.newPaint();
        fillPaint  = c.newPaint();
        linePaint  = c.newPaint();
        tickPaint  = c.newPaint();
        linePath   = c.newPath();
        fillPath   = c.newPath();
    }
}
