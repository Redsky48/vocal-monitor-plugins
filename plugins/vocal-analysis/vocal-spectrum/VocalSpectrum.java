package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vocal Spectrum — pass-through audio plugin that renders a
 * scrolling FFT spectrogram tuned for the vocal range (60 Hz –
 * 16 kHz, log-y).  Four sub-band overlays mark the zones a
 * vocalist actually cares about:
 *
 *   - 80–300 Hz   "mud"        (warmth that becomes boxy)
 *   - 1–2 kHz     "body"       (vocal weight)
 *   - 2–5 kHz     "harshness"  (the bite/edge)
 *   - 5–10 kHz    "sibilance"  (S / SH / CH)
 *   - 10 kHz+     "air/breath" (silk / sparkle)
 *
 * The spectrogram is the source of truth; the band overlays just
 * label the zones so the singer can recognise WHAT they're looking
 * at without reading a frequency-axis tick.
 */
public final class VocalSpectrum
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    // ── Audio interface ─────────────────────────────────────────
    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(specRing, 0f);
        java.util.Arrays.fill(audioRing, 0f);
        ringW = 0;
        specWrite = 0;
        sampleAcc = 0;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // ── Capture audio into a ring; trigger FFT every HOP samples
    //    so the spectrogram refresh rate is ~86 Hz at 44.1 kSPS.
    //    Audio passes through untouched. ──
    private static final int FFT_SIZE = 1024;
    private static final int HOP      = 512;
    private static final int SPEC_COLS = 256;          // history columns rendered
    private static final int SPEC_BINS = FFT_SIZE / 2;
    private final float[] audioRing = new float[FFT_SIZE];
    private int ringW = 0;
    private int sampleAcc = 0;
    // Spectrogram store: SPEC_COLS columns × SPEC_BINS rows of
    // normalised magnitude (0..1).  Newest column at `specWrite`.
    private final float[] specRing = new float[SPEC_COLS * SPEC_BINS];
    private int specWrite = 0;
    private final float[] hann = new float[FFT_SIZE];
    private boolean hannInit = false;
    private final float[] fftRe = new float[FFT_SIZE];
    private final float[] fftIm = new float[FFT_SIZE];

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % FFT_SIZE;
            sampleAcc++;
            if (sampleAcc >= HOP) {
                sampleAcc = 0;
                captureColumn();
            }
        }
    }

    private void captureColumn() {
        if (!hannInit) {
            for (int i = 0; i < FFT_SIZE; i++) {
                hann[i] = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
            }
            hannInit = true;
        }
        // Unwrap ring into linear buffer with Hann window.
        for (int i = 0; i < FFT_SIZE; i++) {
            int idx = (ringW + i) % FFT_SIZE;
            fftRe[i] = audioRing[idx] * hann[i];
            fftIm[i] = 0f;
        }
        fftRadix2(fftRe, fftIm);
        int colOff = specWrite * SPEC_BINS;
        for (int b = 0; b < SPEC_BINS; b++) {
            float mag = (float) Math.sqrt(fftRe[b] * fftRe[b] + fftIm[b] * fftIm[b]) / FFT_SIZE;
            float dB = (float) (20 * Math.log10(Math.max(1e-9f, mag)));
            float t = (dB + 90f) / 90f;
            if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
            specRing[colOff + b] = t;
        }
        specWrite = (specWrite + 1) % SPEC_COLS;
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

    // ── Visual ──────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_ACCENT      = 0xFFF5C842;
    private static final int COLOR_SIGNATURE   = 0xFF5BD9E0; // cyan = "spectrum"
    private static final int COLOR_OVERLAY_DIM = 0x44FFFFFF;
    // Band overlay colours — match the bands in the description.
    private static final int COLOR_MUD       = 0xFFB57A4F; // brown
    private static final int COLOR_BODY      = 0xFF6FE07A; // green
    private static final int COLOR_HARSH     = 0xFFE34855; // red
    private static final int COLOR_SIBILANCE = 0xFFF5C842; // yellow
    private static final int COLOR_AIR       = 0xFF5BD9E0; // cyan

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, overlayPaint, bandPaint, cellPaint;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        if (width < 60 || height < 60) return;
        float W = width, H = height;

        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);

        // Header.
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("VOCAL SPECTRUM", 12f, 16f, textBright);
        textDim.setColor(COLOR_SIGNATURE).setTextSize(10f).setTextAlign(2);
        canvas.drawText("60 Hz - 16 kHz   log scale", W - 12f, 16f, textDim);

        // Spectrogram panel (most of the canvas).
        float pad = 12f;
        float headerH = 22f;
        float labelH = 16f;
        float plotX0 = pad + 36f;
        float plotY0 = pad + headerH;
        float plotX1 = W - pad - 84f;   // leave space for the band legend
        float plotY1 = H - pad - labelH;

        // Card.
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        // Spectrogram fill — newest column on the right.  Each "column"
        // in the ring becomes a vertical strip of `SPEC_BINS` cells.
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;
        float colW = plotW / SPEC_COLS;
        // Log-Y mapping: bin index → frequency → log-frequency screen y.
        // Pre-compute the y-position of each bin once.
        float logFmin = (float) Math.log(60.0);
        float logFmax = (float) Math.log(16000.0);
        for (int c = 0; c < SPEC_COLS; c++) {
            int colIdx = (specWrite + c) % SPEC_COLS;
            int colOff = colIdx * SPEC_BINS;
            float colX = plotX0 + c * colW;
            for (int b = 2; b < SPEC_BINS; b++) {
                float t = specRing[colOff + b];
                if (t < 0.08f) continue;       // skip noise floor for speed
                // Bin frequency.
                float freq = b * (float) sampleRate / FFT_SIZE;
                if (freq < 60f || freq > 16000f) continue;
                float fT = ((float) Math.log(freq) - logFmin) / (logFmax - logFmin);
                float y = plotY1 - fT * plotH;
                float nextY = plotY1 -
                        ((float)(Math.log((b + 1) * (float) sampleRate / FFT_SIZE) - logFmin)
                          / (logFmax - logFmin)) * plotH;
                cellPaint.setColor(viridis(t)).setStyle(PluginStyle.FILL);
                canvas.drawRect(colX, nextY, colX + colW + 0.5f, y, cellPaint);
            }
        }

        // Y-axis frequency tick labels (log).
        int[] tickHz = { 100, 250, 500, 1000, 2500, 5000, 10000 };
        for (int hz : tickHz) {
            float fT = ((float) Math.log(hz) - logFmin) / (logFmax - logFmin);
            float y = plotY1 - fT * plotH;
            gridPaint.setColor(0x33FFFFFF).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            String lbl = hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz);
            canvas.drawText(lbl, plotX0 - 3f, y + 3f, textDim);
        }
        // X-axis: time label.
        textDim.setTextSize(8.5f).setTextAlign(0);
        canvas.drawText("older", plotX0, plotY1 + 11f, textDim);
        textDim.setTextAlign(2);
        canvas.drawText("now", plotX1, plotY1 + 11f, textDim);

        // Band legend — vertical strip on the right showing the 5
        // vocal sub-bands with their meaning labels.
        float legX0 = plotX1 + 14f;
        float legX1 = W - pad;
        drawBand(canvas, legX0, plotY0, legX1, plotY1,
                logFmin, logFmax, plotH);
    }

    // Draw the 5 vocal sub-band legend strips on the right.
    // Each strip's vertical extent matches the corresponding
    // frequency range on the log-y spectrogram axis.
    private void drawBand(PluginCanvas canvas, float x0, float y0, float x1, float y1,
                           float logFmin, float logFmax, float plotH) {
        int[][] bands = {
            {   80,  300, COLOR_MUD,       0 },
            { 1000, 2000, COLOR_BODY,      1 },
            { 2000, 5000, COLOR_HARSH,     2 },
            { 5000,10000, COLOR_SIBILANCE, 3 },
            {10000,16000, COLOR_AIR,       4 },
        };
        String[] names = { "MUD", "BODY", "HARSH", "SIB", "AIR" };
        for (int i = 0; i < bands.length; i++) {
            int loHz = bands[i][0];
            int hiHz = bands[i][1];
            int col  = bands[i][2];
            float fLo = ((float) Math.log(loHz) - logFmin) / (logFmax - logFmin);
            float fHi = ((float) Math.log(hiHz) - logFmin) / (logFmax - logFmin);
            float yLo = y1 - fLo * plotH;
            float yHi = y1 - fHi * plotH;
            // Translucent band fill.
            bandPaint.setColor((col & 0x00FFFFFF) | 0x44000000).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, yHi, x1, yLo, 3f, bandPaint);
            // Sharp left edge accent.
            bandPaint.setColor(col).setStyle(PluginStyle.FILL);
            canvas.drawRect(x0, yHi, x0 + 3f, yLo, bandPaint);
            // Label.
            textBright.setColor(col).setTextSize(9f).setTextAlign(0);
            canvas.drawText(names[i], x0 + 8f, (yHi + yLo) * 0.5f + 3f, textBright);
            // Range below name.
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(7.5f).setTextAlign(0);
            String range = loHz < 1000 ? loHz + "-" + hiHz : (loHz / 1000) + "-" + (hiHz / 1000) + "k";
            canvas.drawText(range, x0 + 8f, (yHi + yLo) * 0.5f + 14f, textDim);
        }
    }

    // Viridis-ish colormap for the spectrogram (dark → bright cyan
    // → yellow as magnitude rises).  Matches the rest of the
    // analysis suite's "scientific instrument" aesthetic.
    private static int viridis(double t) {
        if (t < 0) t = 0; if (t > 1) t = 1;
        double r, g, b;
        if (t < 0.5) {
            double u = t / 0.5;
            r = lerp(0x14, 0x21, u); g = lerp(0x0A, 0x91, u); b = lerp(0x2C, 0x8c, u);
        } else {
            double u = (t - 0.5) / 0.5;
            r = lerp(0x21, 0xfd, u); g = lerp(0x91, 0xe7, u); b = lerp(0x8c, 0x25, u);
        }
        return 0xFF000000 | (((int) r) << 16) | (((int) g) << 8) | ((int) b);
    }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private void initPaints(PluginCanvas c) {
        bgPaint      = c.newPaint();
        cardPaint    = c.newPaint();
        textBright   = c.newPaint();
        textDim      = c.newPaint();
        gridPaint    = c.newPaint();
        overlayPaint = c.newPaint();
        bandPaint    = c.newPaint();
        cellPaint    = c.newPaint();
    }
}
