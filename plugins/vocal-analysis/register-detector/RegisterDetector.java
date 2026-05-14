package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Register Detector — pro-grade vocal register classifier.
 *
 * Distinguishes 5 vocal registers (Roubeau's M1/M2 + Sundberg's belt
 * + the head-falsetto continuum) from a 6-feature acoustic vector
 * pulled straight from the voice-science literature:
 *
 *   - **f0**       : fundamental from YIN (de Cheveigné 2002).
 *   - **H1-H2**    : amplitude of the first harmonic minus the
 *                    second.  Henrich (2005) showed H1-H2 is the
 *                    single best discriminator of laryngeal
 *                    mechanism: M1 (chest) → ≈ 0 dB, M2 (falsetto)
 *                    → +10..+20 dB.
 *   - **H1-A3**    : H1 minus the peak amplitude in the F3 region
 *                    (2.5–3.5 kHz).  Stevens' spectral-tilt proxy —
 *                    head/falsetto has a much steeper roll-off than
 *                    chest.
 *   - **HRF**      : Harmonic Richness Factor (Childers).  H1 vs
 *                    the power-summed amplitude of H2…H10.  Chest
 *                    has many strong upper harmonics (HRF ≈ −3 dB),
 *                    falsetto is almost only the fundamental
 *                    (HRF ≈ +15 dB).
 *   - **SPR**      : Singer's Power Ratio (Sundberg 1974).  Peak
 *                    dB(2–4 kHz) minus peak dB(0–2 kHz) over the
 *                    instantaneous spectrum.  Belt → near 0 dB
 *                    (engaged ring); falsetto → < −20 dB.
 *   - **OQ**       : Open Quotient estimate from Henrich's
 *                    approximation OQ ≈ 0.5 + 0.025·(H1-H2).
 *                    Closed pressed phonation ≈ 0.3; modal ≈ 0.5;
 *                    breathy falsetto ≈ 0.8.
 *
 * Classification is the multinomial product of per-feature gaussians
 * (one (μ, σ) per register per feature, tabulated from the
 * literature), normalised and smoothed for visual stability.
 *
 * Honest scope: a true clinical classifier would need EGG / video-
 * laryngoscopy or a CNN trained on labelled chest/mix/head data.
 * This is the best you can do from audio alone, and matches what
 * Praat / VoceVista / Madde compute when running their voice-
 * quality scripts.
 */
public final class RegisterDetector
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        ringW = 0; sampleAcc = 0;
        java.util.Arrays.fill(scores, 0f);
        java.util.Arrays.fill(scoreSmooth, 0f);
        bestIdx = -1;
        currentFreq = 0f; h1h2 = 0f; h1a3 = 0f; hrf = 0f; spr = 0f; oq = 0.5f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // ── FFT / YIN config ──
    private static final int FFT_N      = 2048;       // 21.5 Hz bin @ 44.1 k
    private static final int FFT_HALF   = FFT_N / 2;
    private static final int LAG_MIN    = 32;
    private static final int LAG_MAX    = 1024;
    private static final float YIN_THRESHOLD = 0.15f;
    private final float[] audioRing = new float[FFT_N];
    private final float[] yinBuf    = new float[FFT_N];
    private final float[] yinDiff   = new float[LAG_MAX + 1];
    private final float[] yinCMND   = new float[LAG_MAX + 1];
    private final float[] fftRe     = new float[FFT_N];
    private final float[] fftIm     = new float[FFT_N];
    private final float[] magDb     = new float[FFT_HALF];
    private final float[] hann      = new float[FFT_N];
    {
        for (int i = 0; i < FFT_N; i++) {
            hann[i] = (float)(0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_N - 1))));
        }
    }
    private int ringW = 0, sampleAcc = 0;

    // ── Registers ──
    private static final int N_REG = 5;
    private static final String[] REG = { "CHEST", "MIX", "HEAD", "FALSETTO", "BELT" };
    private static final int[] REG_COLOURS = {
        0xFFE34855, 0xFFEE8A2C, 0xFF5BD9E0, 0xFFA060E0, 0xFFF5C842
    };

    // Per-register (μ, σ) tables for each feature.  Values tuned from
    // Henrich 2005 (H1-H2 / OQ), Sundberg 1990 / 2001 (SPR / chest /
    // belt), Childers 1991 (HRF), Stevens 1998 (H1-A3).  Pitch ranges
    // are typical adult mixed-sex values.
    //                              CHEST     MIX       HEAD      FALSETTO  BELT
    private static final float[] MU_F0    = { 200f,     380f,     550f,     750f,     520f };
    private static final float[] SG_F0    = { 100f,     130f,     150f,     200f,     150f };
    private static final float[] MU_H1H2  = {   1f,       5f,      11f,      16f,       0f };
    private static final float[] SG_H1H2  = {   4f,       4f,       4f,       5f,       4f };
    private static final float[] MU_H1A3  = {  15f,      22f,      30f,      35f,      12f };
    private static final float[] SG_H1A3  = {   8f,       8f,       8f,      10f,       8f };
    private static final float[] MU_HRF   = {  -3f,       3f,       8f,      15f,      -2f };
    private static final float[] SG_HRF   = {   4f,       4f,       5f,       6f,       4f };
    private static final float[] MU_SPR   = { -22f,     -15f,     -20f,     -25f,      -7f };
    private static final float[] SG_SPR   = {   6f,       6f,       6f,       7f,       5f };
    private static final float[] MU_OQ    = { 0.40f,   0.55f,   0.65f,   0.78f,   0.42f };
    private static final float[] SG_OQ    = { 0.10f,   0.10f,   0.10f,   0.10f,   0.10f };

    private final float[] scores      = new float[N_REG];
    private final float[] scoreSmooth = new float[N_REG];
    private int bestIdx = -1;

    // Latest acoustic measurements (exposed in the readout panel).
    private float currentFreq = 0f;
    private float h1h2 = 0f, h1a3 = 0f, hrf = 0f, spr = 0f, oq = 0.5f;

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
        // ── 1. Energy gate ──
        double energy = 0;
        for (int i = 0; i < FFT_N; i++) {
            int idx = (ringW + i) % FFT_N;
            float v = audioRing[idx];
            yinBuf[i] = v;
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / FFT_N);
        if (rms < 0.003f) {
            for (int i = 0; i < N_REG; i++) scoreSmooth[i] *= 0.85f;
            bestIdx = -1;
            return;
        }
        // ── 2. YIN for f0 ──
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
        if (chosen < 0) return;
        // Parabolic interpolation for sub-sample lag.
        float refined = chosen;
        if (chosen > 0 && chosen < maxLag) {
            float y1 = yinCMND[chosen - 1], y2 = yinCMND[chosen], y3 = yinCMND[chosen + 1];
            float denom = 2f * (2f * y2 - y1 - y3);
            if (Math.abs(denom) > 1e-9f) {
                float adj = (y3 - y1) / denom;
                if (adj > -1f && adj < 1f) refined += adj;
            }
        }
        currentFreq = sampleRate / refined;
        if (currentFreq < 70f || currentFreq > 1200f) return;

        // ── 3. FFT for spectral features ──
        for (int i = 0; i < FFT_N; i++) {
            fftRe[i] = yinBuf[i] * hann[i];
            fftIm[i] = 0f;
        }
        fft(fftRe, fftIm);
        for (int k = 0; k < FFT_HALF; k++) {
            float mag = (float) Math.sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]);
            magDb[k] = 20f * (float) Math.log10(Math.max(1e-9f, mag));
        }
        float binHz = sampleRate / (float) FFT_N;

        // Harmonic amplitudes H1..H10 (parabolic-interpolated dB) for
        // the H1-H2 / HRF measurements.
        float[] hDb = new float[11];
        int nH = 0;
        for (int n = 1; n <= 10; n++) {
            float hHz = n * currentFreq;
            if (hHz > sampleRate * 0.45f) break;
            hDb[n] = peakMagDb(hHz, binHz);
            nH = n;
        }
        if (nH < 2) return;

        // H1-H2
        h1h2 = hDb[1] - hDb[2];

        // HRF (Childers): H1_dB − 10·log10(Σ |Hₙ|² for n=2..nH)
        double higherPower = 0;
        for (int n = 2; n <= nH; n++) {
            double m = Math.pow(10.0, hDb[n] / 20.0);
            higherPower += m * m;
        }
        hrf = hDb[1] - 10f * (float) Math.log10(Math.max(1e-9, higherPower));

        // H1-A3: H1 − max(magDb in 2.5..3.5 kHz)
        int kA3Lo = Math.max(1, (int) Math.floor(2500f / binHz));
        int kA3Hi = Math.min(FFT_HALF - 1, (int) Math.ceil(3500f / binHz));
        float a3 = -120f;
        for (int k = kA3Lo; k <= kA3Hi; k++) if (magDb[k] > a3) a3 = magDb[k];
        h1a3 = hDb[1] - a3;

        // SPR (Sundberg): peak dB(2–4 kHz) − peak dB(80–2000 Hz)
        int kLo1 = Math.max(1, (int) Math.floor(80f / binHz));
        int kLo2 = Math.min(FFT_HALF - 1, (int) Math.floor(2000f / binHz));
        int kHi1 = kLo2;
        int kHi2 = Math.min(FFT_HALF - 1, (int) Math.floor(4000f / binHz));
        float pLo = -120f, pHi = -120f;
        for (int k = kLo1; k <= kLo2; k++) if (magDb[k] > pLo) pLo = magDb[k];
        for (int k = kHi1; k <= kHi2; k++) if (magDb[k] > pHi) pHi = magDb[k];
        spr = pHi - pLo;

        // OQ from Henrich's H1-H2 approximation; clamp to physiological
        // range [0.2 .. 0.95].
        oq = 0.5f + 0.025f * h1h2;
        if (oq < 0.2f) oq = 0.2f;
        if (oq > 0.95f) oq = 0.95f;

        // ── 4. Per-register product of gaussians ──
        for (int i = 0; i < N_REG; i++) {
            float e1 = gauss(currentFreq, MU_F0[i],    SG_F0[i]);
            float e2 = gauss(h1h2,         MU_H1H2[i],  SG_H1H2[i]);
            float e3 = gauss(h1a3,         MU_H1A3[i],  SG_H1A3[i]);
            float e4 = gauss(hrf,          MU_HRF[i],   SG_HRF[i]);
            float e5 = gauss(spr,          MU_SPR[i],   SG_SPR[i]);
            float e6 = gauss(oq,           MU_OQ[i],    SG_OQ[i]);
            scores[i] = e1 * e2 * e3 * e4 * e5 * e6;
        }
        float maxR = 0f;
        for (float r : scores) if (r > maxR) maxR = r;
        if (maxR > 1e-12f) for (int i = 0; i < N_REG; i++) scores[i] /= maxR;
        for (int i = 0; i < N_REG; i++) {
            scoreSmooth[i] += 0.25f * (scores[i] - scoreSmooth[i]);
        }
        bestIdx = 0;
        for (int i = 1; i < N_REG; i++) {
            if (scoreSmooth[i] > scoreSmooth[bestIdx]) bestIdx = i;
        }
    }

    // Magnitude in dB at frequency f, parabolic-interpolated around
    // the nearest FFT bin for sub-bin accuracy.
    private float peakMagDb(float f, float binHz) {
        if (f <= 0f || f >= sampleRate * 0.5f) return -90f;
        float kF = f / binHz;
        int k = Math.round(kF);
        if (k <= 0 || k >= FFT_HALF - 1) {
            if (k < 0 || k >= FFT_HALF) return -90f;
            return magDb[k];
        }
        float y1 = magDb[k - 1], y2 = magDb[k], y3 = magDb[k + 1];
        float denom = (y1 - 2f * y2 + y3);
        if (Math.abs(denom) < 1e-6f) return y2;
        float p = 0.5f * (y1 - y3) / denom;
        return y2 - 0.25f * (y1 - y3) * p;
    }

    private static float gauss(float x, float c, float s) {
        float d = (x - c) / s;
        return (float) Math.exp(-0.5 * d * d);
    }

    // In-place radix-2 Cooley-Tukey FFT.
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

    private PluginPaint bgPaint, cardPaint, textBright, textDim, regBg, regFg;

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
        canvas.drawText("REGISTER DETECTOR", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText("pro: H1-H2 + H1-A3 + HRF + SPR + OQ", W - 12f, 16f, textDim);

        // Big register label on the left.
        String big = bestIdx >= 0 ? REG[bestIdx] : "-";
        int bigCol = bestIdx >= 0 ? REG_COLOURS[bestIdx] : COLOR_TEXT_DIM;
        textBright.setColor(bigCol).setTextSize(30f).setTextAlign(0);
        canvas.drawText(big, 12f, 56f, textBright);

        // Measurements panel on the right (6 lines).
        float panelX = W * 0.55f;
        float panelY0 = 28f;
        float lineH = 13f;
        drawStat(canvas, panelX, panelY0 + 0 * lineH,
                "f0",     String.format("%.0f Hz", currentFreq));
        drawStat(canvas, panelX, panelY0 + 1 * lineH,
                "H1-H2",  String.format("%+.1f dB", h1h2));
        drawStat(canvas, panelX, panelY0 + 2 * lineH,
                "H1-A3",  String.format("%+.1f dB", h1a3));
        drawStat(canvas, panelX, panelY0 + 3 * lineH,
                "HRF",    String.format("%+.1f dB", hrf));
        drawStat(canvas, panelX, panelY0 + 4 * lineH,
                "SPR",    String.format("%+.1f dB", spr));
        drawStat(canvas, panelX, panelY0 + 5 * lineH,
                "OQ",     String.format("%.0f%%", oq * 100f));

        // 5 confidence bars across the bottom.
        float barAreaY0 = 100f;
        float barAreaY1 = H - 14f;
        float barW = (W - 24f) / N_REG - 8f;
        for (int i = 0; i < N_REG; i++) {
            float x0 = 12f + i * ((W - 24f) / N_REG) + 4f;
            float x1 = x0 + barW;
            float v = scoreSmooth[i];
            regBg.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, barAreaY0, x1, barAreaY1 - 14f, 4f, regBg);
            regBg.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(x0, barAreaY0, x1, barAreaY1 - 14f, 4f, regBg);
            float fY = barAreaY1 - 14f - v * ((barAreaY1 - 14f) - barAreaY0);
            int col = REG_COLOURS[i];
            if (i != bestIdx) col = (col & 0x00FFFFFF) | 0x88000000;
            regFg.setColor(col).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, fY, x1, barAreaY1 - 14f, 4f, regFg);
            textDim.setColor(i == bestIdx ? REG_COLOURS[i] : COLOR_TEXT_DIM)
                    .setTextSize(9f).setTextAlign(1);
            canvas.drawText(REG[i], (x0 + x1) * 0.5f, barAreaY1 - 1f, textDim);
        }
    }

    private void drawStat(PluginCanvas canvas, float x, float y, String label, String value) {
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText(label, x, y, textDim);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(9.5f).setTextAlign(2);
        canvas.drawText(value, x + 130f, y, textBright);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        regBg      = c.newPaint();
        regFg      = c.newPaint();
    }
}
