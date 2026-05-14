package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Resonance Monitor — pro-grade vocal resonance analyser.
 *
 * Five complementary spectral metrics computed from the same 5-second
 * Long-Term Average Spectrum (LTAS).  Each one captures a different
 * aspect of resonance / projection, and together they describe the
 * voice's spectral balance the way a clinician or voice coach would:
 *
 *   - **SPR**         : Singer's Power Ratio (Sundberg 1974, Omori
 *                       1996).  peak_dB(2–4 kHz) − peak_dB(80–2 kHz).
 *                       The classic operatic-ring indicator: ≥ −10 dB
 *                       = engaged ring, < −30 dB = no ring.
 *   - **Alpha Ratio** : (Frøkjær-Jensen & Prytz 1976) — integrated
 *                       energy 50–1000 Hz minus 1–5 kHz, dB.  Tracks
 *                       global brightness; negative = bright /
 *                       projected, positive = dark / muffled.
 *   - **Hammarberg**  : (Hammarberg 1986) max_dB(0–2 kHz) −
 *                       max_dB(2–5 kHz).  Inverse spectral balance,
 *                       lower = brighter, used clinically as a
 *                       hyper-/hypo-functional voice indicator.
 *   - **SFA**         : Singer's-formant amplitude (Sundberg) — peak
 *                       dB in the 2.5–3.5 kHz singer's-formant
 *                       cluster.  Tracks the ring's absolute level
 *                       relative to the LTAS peak.
 *   - **Twang**       : Peak dB in the 2–3 kHz "twang" band minus the
 *                       0–2 kHz peak.  Pop/CCM analogue of SPR with
 *                       the band shifted down where modern voices
 *                       cluster their ring (Estill, Sadolin).
 *
 * LTAS is the per-bin 5-second running average of the magnitude
 * spectrum in dB (≈ 430 frames @ 86 fps with 1024-pt FFT, 50 %
 * overlap).  All five metrics are recomputed every frame from the
 * current LTAS so the readouts settle as the average stabilises.
 *
 * The LTAS plot at the bottom marks the SPR low/high bands and the
 * singer's-formant cluster so you can SEE what each number is
 * measuring.
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
        spr = -60f; alphaRatio = 0f; hammarberg = 0f;
        sfaDb = -60f; twang = -60f;
        peakLowDb = peakHighDb = -90f;
        peakLowBin = peakHighBin = 0;
        peakSfBin = peakTwangBin = 0;
        peakSfDb = peakTwangDb = -90f;
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

    // Five resonance metrics.
    private float spr = -60f;          // Sundberg SPR
    private float alphaRatio = 0f;     // Frøkjær-Jensen alpha
    private float hammarberg = 0f;     // Hammarberg index
    private float sfaDb = -60f;        // singer's-formant amplitude
    private float twang = -60f;        // pop/CCM SPR variant
    // Band-peak locations (for plot markers).
    private float peakLowDb = -90f, peakHighDb = -90f;
    private int peakLowBin = 0, peakHighBin = 0;
    private float peakSfDb = -90f, peakTwangDb = -90f;
    private int peakSfBin = 0, peakTwangBin = 0;

    private static final int HIST_LEN = 256;
    private final float[] sprHist = new float[HIST_LEN];
    private int histW = 0;

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
        // Windowed FFT.
        for (int i = 0; i < FFT_N; i++) {
            int idx = (ringW + i) % FFT_N;
            fftRe[i] = audioRing[idx] * hann[i];
            fftIm[i] = 0f;
        }
        fft(fftRe, fftIm);
        // LTAS update per bin.
        float alpha = 1f / 430f;            // 5 s @ 86 fps
        if (!ltasReady) alpha = 0.05f;       // fill faster on cold start
        for (int k = 0; k < FFT_HALF; k++) {
            float mag = (float) Math.sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]);
            float magDb = 20f * (float) Math.log10(Math.max(1e-9f, mag));
            ltasDb[k] += alpha * (magDb - ltasDb[k]);
        }
        ltasFrames++;
        if (ltasFrames > 50) ltasReady = true;

        float binHz = sampleRate / (float) FFT_N;

        // ── SPR (Sundberg) ──
        // peak dB in 80..2000 Hz vs peak dB in 2000..4000 Hz.
        int kLow1  = Math.max(1, (int) Math.floor(80f   / binHz));
        int kLow2  = Math.min(FFT_HALF - 1, (int) Math.floor(2000f / binHz));
        int kHigh1 = kLow2;
        int kHigh2 = Math.min(FFT_HALF - 1, (int) Math.floor(4000f / binHz));
        float pLow = -120f; int iLow = kLow1;
        for (int k = kLow1; k <= kLow2; k++) {
            if (ltasDb[k] > pLow) { pLow = ltasDb[k]; iLow = k; }
        }
        float pHigh = -120f; int iHigh = kHigh1;
        for (int k = kHigh1; k <= kHigh2; k++) {
            if (ltasDb[k] > pHigh) { pHigh = ltasDb[k]; iHigh = k; }
        }
        peakLowDb = pLow; peakLowBin = iLow;
        peakHighDb = pHigh; peakHighBin = iHigh;
        spr = pHigh - pLow;

        // ── Alpha Ratio (Frøkjær-Jensen) ──
        // 10·log10(Σ pow 50..1000 Hz) − 10·log10(Σ pow 1..5 kHz)
        int kA1 = Math.max(1, (int) Math.floor(50f   / binHz));
        int kA2 = Math.min(FFT_HALF - 1, (int) Math.floor(1000f / binHz));
        int kA3 = kA2;
        int kA4 = Math.min(FFT_HALF - 1, (int) Math.floor(5000f / binHz));
        double aLo = 0, aHi = 0;
        for (int k = kA1; k <= kA2; k++) aLo += Math.pow(10.0, ltasDb[k] / 10.0);
        for (int k = kA3; k <= kA4; k++) aHi += Math.pow(10.0, ltasDb[k] / 10.0);
        alphaRatio = (float)(10.0 * Math.log10(Math.max(1e-12, aLo))
                            - 10.0 * Math.log10(Math.max(1e-12, aHi)));

        // ── Hammarberg Index ──
        // max(0..2 kHz) − max(2..5 kHz) — inverse spectral balance.
        int kHm1 = Math.max(1, (int) Math.floor(60f   / binHz));
        int kHm2 = Math.min(FFT_HALF - 1, (int) Math.floor(2000f / binHz));
        int kHm3 = kHm2;
        int kHm4 = Math.min(FFT_HALF - 1, (int) Math.floor(5000f / binHz));
        float pHm1 = -120f, pHm2 = -120f;
        for (int k = kHm1; k <= kHm2; k++) if (ltasDb[k] > pHm1) pHm1 = ltasDb[k];
        for (int k = kHm3; k <= kHm4; k++) if (ltasDb[k] > pHm2) pHm2 = ltasDb[k];
        hammarberg = pHm1 - pHm2;

        // ── SFA — singer's-formant amplitude in 2.5..3.5 kHz ──
        int kS1 = (int) Math.floor(2500f / binHz);
        int kS2 = Math.min(FFT_HALF - 1, (int) Math.floor(3500f / binHz));
        float pSf = -120f; int iSf = kS1;
        for (int k = kS1; k <= kS2; k++) {
            if (ltasDb[k] > pSf) { pSf = ltasDb[k]; iSf = k; }
        }
        peakSfDb = pSf; peakSfBin = iSf;
        sfaDb = pSf;        // absolute amplitude (LTAS dB scale)

        // ── Twang Index — peak 2..3 kHz − peak 0..2 kHz ──
        int kTw1 = (int) Math.floor(2000f / binHz);
        int kTw2 = Math.min(FFT_HALF - 1, (int) Math.floor(3000f / binHz));
        float pTw = -120f; int iTw = kTw1;
        for (int k = kTw1; k <= kTw2; k++) {
            if (ltasDb[k] > pTw) { pTw = ltasDb[k]; iTw = k; }
        }
        peakTwangDb = pTw; peakTwangBin = iTw;
        twang = pTw - pLow;

        // History.
        sprHist[histW] = spr;
        histW = (histW + 1) % HIST_LEN;
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
    private static final int COLOR_BAND_LOW    = 0xFF6DD3E0;
    private static final int COLOR_BAND_HIGH   = 0xFFF5C842;
    private static final int COLOR_BAND_SF     = 0xFFEE8A2C;
    private static final int COLOR_BAND_TWANG  = 0xFFE36C9C;
    private static final int COLOR_GREEN       = 0xFF6FE07A;
    private static final int COLOR_YELLOW      = 0xFFE0C040;
    private static final int COLOR_ORANGE      = 0xFFE0A040;
    private static final int COLOR_RED         = 0xFFE0606A;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            ltasLine, markLow, markHigh, markSf, markTwang, sprLine;
    private PluginPath ltasPath, sprPath;

    private int sprColor(float v) {
        if (v >= -10f) return COLOR_GREEN;
        if (v >= -20f) return COLOR_YELLOW;
        if (v >= -30f) return COLOR_ORANGE;
        return COLOR_RED;
    }
    private String sprVerdict(float v) {
        if (v >= -10f) return "RING";
        if (v >= -20f) return "TRAINED";
        if (v >= -30f) return "UNTRAINED";
        return "NO RING";
    }
    private int alphaColor(float v) {
        if (v <= -10f) return COLOR_GREEN;       // bright
        if (v <=   0f) return COLOR_YELLOW;
        if (v <=  10f) return COLOR_ORANGE;
        return COLOR_RED;                         // dark / muffled
    }
    private int hammColor(float v) {
        if (v <= 10f)  return COLOR_GREEN;       // bright
        if (v <= 20f)  return COLOR_YELLOW;
        if (v <= 30f)  return COLOR_ORANGE;
        return COLOR_RED;                         // dull
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
        canvas.drawText("pro: SPR + Alpha + Hamm + SFA + Twang  (LTAS 5 s)",
                W - 12f, 16f, textDim);

        float pad = 12f, headerH = 24f;
        // ── Top: 5 metric cards in a row ──
        float cardY0 = pad + headerH;
        float cardH = 64f;
        float gap = 6f;
        float cardW = (W - pad * 2 - 4 * gap) / 5f;

        drawMetric(canvas, pad + 0 * (cardW + gap), cardY0, cardW, cardH,
                "SPR",        String.format("%+.1f", spr),       "dB", sprColor(spr),
                sprVerdict(spr));
        drawMetric(canvas, pad + 1 * (cardW + gap), cardY0, cardW, cardH,
                "Alpha",      String.format("%+.1f", alphaRatio), "dB", alphaColor(alphaRatio),
                alphaRatio <= -5f ? "BRIGHT" : alphaRatio >= 5f ? "DARK" : "BAL");
        drawMetric(canvas, pad + 2 * (cardW + gap), cardY0, cardW, cardH,
                "Hamm",       String.format("%+.1f", hammarberg), "dB", hammColor(hammarberg),
                hammarberg <= 10f ? "OPEN" : hammarberg >= 25f ? "PRESSED" : "MID");
        drawMetric(canvas, pad + 3 * (cardW + gap), cardY0, cardW, cardH,
                "SFA",        String.format("%.0f", sfaDb),       "dB", COLOR_BAND_SF,
                "2.5-3.5 kHz");
        drawMetric(canvas, pad + 4 * (cardW + gap), cardY0, cardW, cardH,
                "Twang",      String.format("%+.1f", twang),      "dB", sprColor(twang),
                "2-3 kHz");

        // ── Middle: LTAS plot with band markers ──
        float plotX0 = pad + 28f;
        float plotX1 = W - pad;
        float plotY0 = cardY0 + cardH + 12f;
        float plotY1 = H - pad - 56f;
        if (plotY1 - plotY0 > 30f) {
            cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 6f, cardPaint);
            cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 6f, cardPaint);
            float plotW = plotX1 - plotX0;
            float plotH = plotY1 - plotY0;
            // Log-Hz axis 60..5500 Hz.
            float fMin = 60f, fMax = 5500f;
            double lmin = Math.log10(fMin), lmax = Math.log10(fMax);
            float dbMax = -120f;
            for (int k = 1; k < FFT_HALF; k++) if (ltasDb[k] > dbMax) dbMax = ltasDb[k];
            float dbMin = dbMax - 60f;
            float binHz = sampleRate / (float) FFT_N;

            // Band tints — low (0-2k cyan), high (2-4k yellow), SF cluster
            // (2.5-3.5k orange), twang (2-3k pink).  All translucent.
            shadeBand(canvas, plotX0, plotY0, plotW, plotH, lmin, lmax,
                    80f, 2000f,  0x14000000 | (COLOR_BAND_LOW   & 0x00FFFFFF));
            shadeBand(canvas, plotX0, plotY0, plotW, plotH, lmin, lmax,
                    2000f, 4000f, 0x14000000 | (COLOR_BAND_HIGH  & 0x00FFFFFF));
            shadeBand(canvas, plotX0, plotY0, plotW, plotH, lmin, lmax,
                    2500f, 3500f, 0x22000000 | (COLOR_BAND_SF    & 0x00FFFFFF));
            // LTAS curve.
            ltasPath.reset();
            boolean started = false;
            for (int k = 1; k < FFT_HALF; k++) {
                float f = k * binHz;
                if (f < fMin || f > fMax) continue;
                double lf = Math.log10(f);
                float px = plotX0 + (float)((lf - lmin) / (lmax - lmin)) * plotW;
                float d = ltasDb[k];
                if (d < dbMin) d = dbMin; if (d > dbMax) d = dbMax;
                float py = plotY1 - (d - dbMin) / (dbMax - dbMin) * plotH;
                if (!started) { ltasPath.moveTo(px, py); started = true; }
                else ltasPath.lineTo(px, py);
            }
            ltasLine.setColor(0xFFE6E6EA).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
            canvas.drawPath(ltasPath, ltasLine);

            // Peak markers.
            if (ltasReady) {
                markPeak(canvas, plotX0, plotY0, plotW, plotH, lmin, lmax, dbMin, dbMax,
                        peakLowBin * binHz, peakLowDb, COLOR_BAND_LOW, "Lo", markLow);
                markPeak(canvas, plotX0, plotY0, plotW, plotH, lmin, lmax, dbMin, dbMax,
                        peakHighBin * binHz, peakHighDb, COLOR_BAND_HIGH, "Hi", markHigh);
                markPeak(canvas, plotX0, plotY0, plotW, plotH, lmin, lmax, dbMin, dbMax,
                        peakSfBin * binHz, peakSfDb, COLOR_BAND_SF, "SF", markSf);
                markPeak(canvas, plotX0, plotY0, plotW, plotH, lmin, lmax, dbMin, dbMax,
                        peakTwangBin * binHz, peakTwangDb, COLOR_BAND_TWANG, "Tw", markTwang);
            }
            // Tick labels.
            int[] tickHz = { 100, 250, 500, 1000, 2500, 5000 };
            for (int hz : tickHz) {
                if (hz < fMin || hz > fMax) continue;
                float t = (float)((Math.log10(hz) - lmin) / (lmax - lmin));
                float px = plotX0 + t * plotW;
                cardPaint.setColor(0xFF353638).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
                canvas.drawLine(px, plotY0 + 4f, px, plotY1 - 4f, cardPaint);
                textDim.setColor(COLOR_TEXT_DIM).setTextSize(8f).setTextAlign(1);
                String lbl = hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz);
                canvas.drawText(lbl, px, plotY1 - 2f, textDim);
            }
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
            canvas.drawText("LTAS (log Hz)", plotX0 + 4f, plotY0 + 11f, textDim);
        }

        // ── Bottom: SPR history strip ──
        float histY0 = H - pad - 42f;
        float histY1 = H - pad - 4f;
        if (histY1 - histY0 > 20f) {
            cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(pad, histY0, W - pad, histY1, 6f, cardPaint);
            cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(pad, histY0, W - pad, histY1, 6f, cardPaint);
            float plotW = W - pad * 2;
            float plotH = histY1 - histY0;
            float step = plotW / (HIST_LEN - 1f);
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
            sprLine.setColor(sprColor(spr)).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
            canvas.drawPath(sprPath, sprLine);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
            canvas.drawText("SPR history", pad + 4f, histY0 + 11f, textDim);
        }
    }

    private void drawMetric(PluginCanvas canvas, float x, float y, float w, float h,
                             String label, String value, String unit,
                             int colour, String verdict) {
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x, y, x + w, y + h, 6f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(x, y, x + w, y + h, 6f, cardPaint);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText(label, x + 6f, y + 12f, textDim);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(8f).setTextAlign(2);
        canvas.drawText(unit, x + w - 6f, y + 12f, textDim);
        textBright.setColor(colour).setTextSize(18f).setTextAlign(1);
        canvas.drawText(value, x + w * 0.5f, y + h * 0.6f, textBright);
        textBright.setColor(colour).setTextSize(8.5f).setTextAlign(1);
        canvas.drawText(verdict, x + w * 0.5f, y + h - 5f, textBright);
    }

    private void shadeBand(PluginCanvas canvas, float x0, float y0,
                            float w, float h, double lmin, double lmax,
                            float fLo, float fHi, int colour) {
        float tLo = (float)((Math.log10(fLo) - lmin) / (lmax - lmin));
        float tHi = (float)((Math.log10(fHi) - lmin) / (lmax - lmin));
        if (tLo < 0) tLo = 0; if (tHi > 1) tHi = 1;
        cardPaint.setColor(colour).setStyle(PluginStyle.FILL);
        canvas.drawRect(x0 + tLo * w, y0, x0 + tHi * w, y0 + h, cardPaint);
    }

    private void markPeak(PluginCanvas canvas, float x0, float y0,
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
        canvas.drawCircle(px, py, 3.5f, paint);
        paint.setColor(colour).setTextSize(8f).setTextAlign(1);
        canvas.drawText(label, px, py - 5f, paint);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        ltasLine   = c.newPaint();
        markLow    = c.newPaint();
        markHigh   = c.newPaint();
        markSf     = c.newPaint();
        markTwang  = c.newPaint();
        sprLine    = c.newPaint();
        ltasPath   = c.newPath();
        sprPath    = c.newPath();
    }
}
