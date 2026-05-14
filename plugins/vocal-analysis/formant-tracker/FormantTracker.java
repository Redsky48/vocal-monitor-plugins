package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Formant Tracker — estimates F1, F2, F3 via 12th-order LPC
 * (autocorrelation + Levinson-Durbin) and plots them on the
 * classic F1–F2 vowel map.  Provides:
 *
 *   - Live cross-hair on the F1/F2 plane.
 *   - Reference vowel zones (A, E, I, O, U) drawn as labelled bubbles.
 *   - Trail of recent positions so glides and instability are visible.
 *   - F3 (singer's formant clue) shown as a numeric readout — F3
 *     drifting up toward 2.8–3.4 kHz suggests engaged ring/twang.
 *
 * LPC roots are not computed (root-finding is heavy for DEX); we
 * instead pick the dominant LP-spectrum peaks via cheap FFT of the
 * inverse-filter impulse response, then sort by frequency and
 * label the lowest three.
 */
public final class FormantTracker
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        ringW = 0; sampleAcc = 0;
        java.util.Arrays.fill(trailF1, 0f);
        java.util.Arrays.fill(trailF2, 0f);
        trailW = 0;
        f1 = f2 = f3 = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // ── LPC analysis ──
    private static final int LPC_ORDER  = 12;
    private static final int FRAME_SIZE = 1024;
    private static final int HOP        = 512;
    private final float[] audioRing = new float[FRAME_SIZE];
    private final float[] frame = new float[FRAME_SIZE];
    private final float[] lpcA = new float[LPC_ORDER + 1];
    private final float[] R = new float[LPC_ORDER + 1];
    private int ringW = 0, sampleAcc = 0;

    // ── Result + trail ──
    private float f1 = 0f, f2 = 0f, f3 = 0f;
    private static final int TRAIL_LEN = 80;
    private final float[] trailF1 = new float[TRAIL_LEN];
    private final float[] trailF2 = new float[TRAIL_LEN];
    private int trailW = 0;

    // Sample the LPC magnitude spectrum on N points for peak picking.
    private static final int SPEC_BINS = 256;
    private final float[] lpcSpec = new float[SPEC_BINS];

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % FRAME_SIZE;
            sampleAcc++;
            if (sampleAcc >= HOP) {
                sampleAcc = 0;
                analyseFrame();
            }
        }
    }

    private void analyseFrame() {
        // Pre-emphasis + Hann window — helps the LPC fit the vocal
        // formants instead of the spectral tilt.
        float prev = 0f;
        double energy = 0;
        for (int i = 0; i < FRAME_SIZE; i++) {
            int idx = (ringW + i) % FRAME_SIZE;
            float v = audioRing[idx] - 0.97f * prev;
            prev = audioRing[idx];
            float w = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME_SIZE - 1)));
            frame[i] = v * w;
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / FRAME_SIZE);
        if (rms < 0.003f) { f1 = f2 = f3 = 0f; return; }

        // Autocorrelation R[0..LPC_ORDER].
        for (int k = 0; k <= LPC_ORDER; k++) {
            float sum = 0f;
            for (int i = k; i < FRAME_SIZE; i++) sum += frame[i] * frame[i - k];
            R[k] = sum;
        }
        if (R[0] < 1e-9f) { f1 = f2 = f3 = 0f; return; }
        // Levinson-Durbin recursion → lpcA[].
        float[] a = new float[LPC_ORDER + 1];
        float[] aPrev = new float[LPC_ORDER + 1];
        float Eerr = R[0];
        a[0] = 1f;
        for (int p = 1; p <= LPC_ORDER; p++) {
            float k = -R[p];
            for (int j = 1; j < p; j++) k -= a[j] * R[p - j];
            k /= Eerr;
            // Reflection coefficient clamp for stability.
            if (k > 0.99f) k = 0.99f; if (k < -0.99f) k = -0.99f;
            System.arraycopy(a, 0, aPrev, 0, p);
            a[p] = k;
            for (int j = 1; j < p; j++) a[j] = aPrev[j] + k * aPrev[p - j];
            Eerr *= 1f - k * k;
            if (Eerr < 1e-9f) Eerr = 1e-9f;
        }
        System.arraycopy(a, 0, lpcA, 0, LPC_ORDER + 1);

        // LPC magnitude spectrum: |1 / A(e^jω)| at SPEC_BINS points
        // from 0 to fs/2 (Nyquist).  We only need 0..5 kHz for vocal
        // formants so we sample more densely in that range.
        float maxHz = 5500f;
        for (int b = 0; b < SPEC_BINS; b++) {
            float freq = (b + 1) * maxHz / SPEC_BINS;
            double w = 2.0 * Math.PI * freq / sampleRate;
            double re = 0, im = 0;
            for (int k = 0; k <= LPC_ORDER; k++) {
                re += lpcA[k] * Math.cos(-w * k);
                im += lpcA[k] * Math.sin(-w * k);
            }
            double mag2 = re * re + im * im;
            lpcSpec[b] = mag2 > 1e-12f ? (float)(1.0 / Math.sqrt(mag2)) : 0f;
        }

        // Pick local-max peaks in the LPC spectrum, store their freqs.
        float[] peaks = new float[8];
        int nPeaks = 0;
        for (int b = 2; b < SPEC_BINS - 2 && nPeaks < peaks.length; b++) {
            float v = lpcSpec[b];
            if (v > lpcSpec[b - 1] && v > lpcSpec[b + 1]
                    && v > lpcSpec[b - 2] && v > lpcSpec[b + 2]) {
                peaks[nPeaks++] = (b + 1) * maxHz / SPEC_BINS;
            }
        }
        // Sort ascending (cheap insertion sort, n is tiny).
        for (int i = 1; i < nPeaks; i++) {
            float key = peaks[i];
            int j = i - 1;
            while (j >= 0 && peaks[j] > key) { peaks[j + 1] = peaks[j]; j--; }
            peaks[j + 1] = key;
        }
        // F1 should sit roughly 200–1000 Hz, F2 800–3000 Hz, F3
        // 2200–4500 Hz.  Walk the peak list and pick the first one
        // in each band.
        float newF1 = 0f, newF2 = 0f, newF3 = 0f;
        for (int i = 0; i < nPeaks; i++) {
            float p = peaks[i];
            if (newF1 == 0f && p >= 200f && p <= 1100f) newF1 = p;
            else if (newF2 == 0f && p >= 700f  && p <= 3000f && p > newF1 + 200f) newF2 = p;
            else if (newF3 == 0f && p >= 1800f && p <= 4500f && p > newF2 + 300f) newF3 = p;
        }
        if (newF1 > 0f && newF2 > 0f) {
            // Smooth: 30 ms IIR per formant for visual stability.
            float coef = 0.3f;
            f1 = f1 == 0f ? newF1 : f1 + coef * (newF1 - f1);
            f2 = f2 == 0f ? newF2 : f2 + coef * (newF2 - f2);
            f3 = f3 == 0f ? newF3 : f3 + (newF3 > 0f ? coef * (newF3 - f3) : 0f);
            trailF1[trailW] = f1;
            trailF2[trailW] = f2;
            trailW = (trailW + 1) % TRAIL_LEN;
        }
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SIGNATURE   = 0xFFEE8A2C; // orange
    private static final int COLOR_GRID        = 0xFF202125;

    // Reference vowel positions (English IPA, male speaker average,
    // Peterson-Barney style) in Hz.  Plotted on the F1/F2 plane.
    private static final String[] VOWELS = { "I","E","A","O","U" };
    private static final float[] VOWEL_F1 = { 300f, 480f, 700f, 500f, 320f };
    private static final float[] VOWEL_F2 = { 2200f, 1800f, 1100f, 900f, 800f };

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, trailPaint, dotPaint, vowelPaint, vowelLabel;

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
        canvas.drawText("FORMANT TRACKER", 12f, 16f, textBright);

        // Layout: F1/F2 vowel map fills most of the canvas; bottom
        // strip shows F1/F2/F3 numeric readouts.
        float pad = 12f;
        float headerH = 24f;
        float footerH = 26f;
        float plotX0 = pad + 40f;
        float plotY0 = pad + headerH;
        float plotX1 = W - pad;
        float plotY1 = H - pad - footerH;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;

        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        // Axes: F1 (Y, 200–1000 Hz, top→bottom growing), F2 (X,
        // 700–2400 Hz, right→left growing — IPA convention).
        // Grid lines at F1 200/400/600/800/1000 and F2 800/1200/1600/2000/2400.
        for (int hz = 200; hz <= 1000; hz += 200) {
            float y = mapF1(hz, plotY0, plotY1);
            gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8f).setTextAlign(2);
            canvas.drawText(hz + "", plotX0 - 3f, y + 3f, textDim);
        }
        for (int hz = 800; hz <= 2400; hz += 400) {
            float x = mapF2(hz, plotX0, plotX1);
            gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(x, plotY0, x, plotY1, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8f).setTextAlign(1);
            canvas.drawText(hz + "", x, plotY1 + 11f, textDim);
        }
        // Axis labels.
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("F1 (Hz)", pad, plotY0 - 4f, textDim);
        textDim.setTextAlign(2);
        canvas.drawText("F2 (Hz)", plotX1, plotY1 + 22f, textDim);

        // Reference vowel positions.
        for (int i = 0; i < VOWELS.length; i++) {
            float vx = mapF2(VOWEL_F2[i], plotX0, plotX1);
            float vy = mapF1(VOWEL_F1[i], plotY0, plotY1);
            vowelPaint.setColor(0x44EE8A2C).setStyle(PluginStyle.FILL);
            canvas.drawCircle(vx, vy, 20f, vowelPaint);
            vowelPaint.setColor(COLOR_SIGNATURE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
            canvas.drawCircle(vx, vy, 20f, vowelPaint);
            vowelLabel.setColor(COLOR_TEXT_BRIGHT).setTextSize(13f).setTextAlign(1);
            canvas.drawText(VOWELS[i], vx, vy + 5f, vowelLabel);
        }

        // Trail of recent F1/F2 positions — older = more transparent.
        for (int i = 0; i < TRAIL_LEN; i++) {
            int idx = (trailW - 1 - i + TRAIL_LEN * 2) % TRAIL_LEN;
            float ff1 = trailF1[idx];
            float ff2 = trailF2[idx];
            if (ff1 <= 0f) continue;
            float tx = mapF2(ff2, plotX0, plotX1);
            float ty = mapF1(ff1, plotY0, plotY1);
            int alpha = (int)(220 * (1f - i / (float) TRAIL_LEN));
            if (alpha < 30) alpha = 30;
            int col = (alpha << 24) | (COLOR_SIGNATURE & 0x00FFFFFF);
            trailPaint.setColor(col).setStyle(PluginStyle.FILL);
            canvas.drawCircle(tx, ty, i == 0 ? 5f : 2.5f, trailPaint);
        }

        // Current dot — big yellow crosshair.
        if (f1 > 0f && f2 > 0f) {
            float tx = mapF2(f2, plotX0, plotX1);
            float ty = mapF1(f1, plotY0, plotY1);
            dotPaint.setColor(0xFFF5C842).setStyle(PluginStyle.STROKE).setStrokeWidth(1.5f);
            canvas.drawLine(tx - 14, ty, tx + 14, ty, dotPaint);
            canvas.drawLine(tx, ty - 14, tx, ty + 14, dotPaint);
            dotPaint.setColor(0xFFF5C842).setStyle(PluginStyle.FILL);
            canvas.drawCircle(tx, ty, 4f, dotPaint);
        }

        // Numeric readouts at the bottom.
        float ny = H - pad - 4f;
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText(f1 > 0f ? String.format("F1 %.0f Hz", f1) : "F1 --",
                pad, ny, textDim);
        canvas.drawText(f2 > 0f ? String.format("F2 %.0f Hz", f2) : "F2 --",
                pad + 100f, ny, textDim);
        canvas.drawText(f3 > 0f ? String.format("F3 %.0f Hz", f3) : "F3 --",
                pad + 200f, ny, textDim);
        textDim.setColor(COLOR_SIGNATURE).setTextAlign(2);
        canvas.drawText("vowel map (Peterson-Barney style)", plotX1, ny, textDim);
    }

    private float mapF1(float hz, float y0, float y1) {
        float t = (hz - 200f) / 800f;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return y0 + t * (y1 - y0);
    }
    private float mapF2(float hz, float x0, float x1) {
        // IPA convention: high F2 on the LEFT (front vowels), low
        // on the RIGHT (back vowels).  So map (800..2400) → (1..0).
        float t = (hz - 800f) / 1600f;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return x1 - t * (x1 - x0);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        gridPaint  = c.newPaint();
        trailPaint = c.newPaint();
        dotPaint   = c.newPaint();
        vowelPaint = c.newPaint();
        vowelLabel = c.newPaint();
    }
}
