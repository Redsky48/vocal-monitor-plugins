package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vocal Dynamics — full ITU-R BS.1770-4 / EBU R128 loudness meter:
 *
 *   - M   Momentary loudness     : 400 ms K-weighted block
 *   - S   Short-term loudness    : 3 s K-weighted block
 *   - I   Integrated loudness    : gated mean of 400 ms blocks
 *                                  (−70 LUFS absolute gate then
 *                                   −10 LU relative gate)
 *   - LRA Loudness range         : 3 s blocks, −70 LUFS abs + −20 LU
 *                                  relative gate, LRA = p95 − p10
 *   - TP  True peak              : 4× FIR-oversampled peak follower
 *                                  (12-tap windowed-sinc polyphase)
 *
 * K-weighting: high-shelf @ 1.5 kHz +4 dB then high-pass @ 38 Hz
 * Q = 0.5 (BS.1770). Mean-square calibrated with the standard
 * −0.691 dB offset.
 */
public final class VocalDynamics
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(envHist, -80f);
        java.util.Arrays.fill(audioRing, 0f);
        for (int j = 0; j < 4; j++) { hsState[j] = 0f; hpState[j] = 0f; }
        java.util.Arrays.fill(iBlockMs, 0f);
        java.util.Arrays.fill(lraBlockMs, 0f);
        java.util.Arrays.fill(tpBuf, 0f);
        peakEnv = 0f; rmsEnv = 0f;
        peakDb = rmsDb = momentaryLufs = shortLufs = -80f;
        integratedLufs = -80f; lra = 0f; truePeakDb = -80f;
        truePeakHold = 0f; truePeakHoldDecay = 0f;
        histW = 0; r400W = 0; r3000W = 0;
        sum400 = 0; sum3000 = 0; strideAcc100 = 0; strideAcc1000 = 0;
        iBlockW = 0; iBlockCount = 0;
        lraBlockW = 0; lraBlockCount = 0;
        tpW = 0;
        ringW = 0;
    }

    // Local audio ring filled by process() — fallback for render()
    // when the host doesn't supply streams["waveform"].
    private static final int ANALYSIS_SIZE = 1024;
    private final float[] audioRing = new float[ANALYSIS_SIZE];
    private int ringW = 0;

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // K-weighting biquads (BS.1770).
    private float[] hsCoefs, hpCoefs;
    private final float[] hsState = new float[4], hpState = new float[4];

    // 400 ms ring of squared K-weighted samples (for M).
    private float[] r400;
    private int r400W = 0;
    private double sum400 = 0;

    // 3 s ring of squared K-weighted samples (for S).
    private float[] r3000;
    private int r3000W = 0;
    private double sum3000 = 0;

    // Block strides: 100 ms (I path) + 1000 ms (LRA path).
    private int strideAcc100 = 0;
    private int strideAcc1000 = 0;

    // Block rings.  30 min capacity each.
    private static final int I_BLOCKS_MAX   = 18000;   // 30 min @ 100 ms
    private static final int LRA_BLOCKS_MAX = 1800;    // 30 min @ 1 s
    private final float[] iBlockMs   = new float[I_BLOCKS_MAX];
    private final float[] lraBlockMs = new float[LRA_BLOCKS_MAX];
    private final float[] lraSort    = new float[LRA_BLOCKS_MAX];
    private int iBlockW = 0, iBlockCount = 0;
    private int lraBlockW = 0, lraBlockCount = 0;

    // True peak (4× oversampled).
    private static final int TP_TAPS = 12;
    private final float[][] tpKernel = new float[4][TP_TAPS];
    private final float[] tpBuf = new float[TP_TAPS];
    private int tpW = 0;
    private float truePeakHold = 0f;
    private float truePeakHoldDecay = 0f;

    private float peakEnv = 0f, rmsEnv = 0f;
    private float peakDb = -80f, rmsDb = -80f;
    private float momentaryLufs = -80f, shortLufs = -80f;
    private float integratedLufs = -80f, lra = 0f, truePeakDb = -80f;

    private static final int HIST_LEN = 256;
    private final float[] envHist = new float[HIST_LEN];
    private int histW = 0;

    private boolean coefsReady = false;

    private void prepare() {
        hsCoefs = highShelf(1500f, 1.0f, 4.0f, sampleRate);
        hpCoefs = highPass(38f, 0.5f, sampleRate);
        r400  = new float[Math.max(1, (int)(sampleRate * 0.400))];
        r3000 = new float[Math.max(1, (int)(sampleRate * 3.000))];
        // 12-tap windowed-sinc kernel, 4 polyphase branches.
        for (int p = 0; p < 4; p++) {
            double sum = 0;
            for (int k = 0; k < TP_TAPS; k++) {
                double x = (k - (TP_TAPS / 2.0 - 0.5)) - p / 4.0;
                double s = Math.abs(x) < 1e-9 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
                double w = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * k / (TP_TAPS - 1.0)));
                tpKernel[p][k] = (float)(s * w);
                sum += tpKernel[p][k];
            }
            // Normalise so each polyphase branch has unity DC gain.
            for (int k = 0; k < TP_TAPS; k++) tpKernel[p][k] /= (float) sum;
        }
        truePeakHoldDecay = (float) Math.exp(-1.0 / (sampleRate * 1.5));   // ~1.5 s
        coefsReady = true;
    }

    // Pass-through + capture into a local ring; the per-sample LUFS
    // / true-peak / sliding-block machinery is invoked from render()
    // over either streams["waveform"] (preferred) or this ring.
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

    // Per-sample DSP — same as the original process() loop but
    // driven from audioRing.  Biquad / IIR / sliding-ring state is
    // kept across calls so M / S / I / LRA / TP evolve smoothly.
    private void analyseWindow() {
        if (!coefsReady) prepare();
        float peakRel = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.001));
        float rmsCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.300));
        int histStride = Math.max(1, sampleRate / 100);
        int stride100 = Math.max(1, sampleRate / 10);
        int stride1000 = Math.max(1, sampleRate);
        int histAcc = 0;
        for (int i = 0; i < ANALYSIS_SIZE; i++) {
            float s = audioRing[(ringW + i) % ANALYSIS_SIZE];
            float aAbs = s < 0 ? -s : s;
            if (aAbs > peakEnv) peakEnv = aAbs;
            else peakEnv += peakRel * (aAbs - peakEnv);
            rmsEnv += rmsCoef * (s * s - rmsEnv);
            tpBuf[tpW] = s;
            tpW = (tpW + 1) % TP_TAPS;
            float tpFrameMax = 0f;
            for (int p = 0; p < 4; p++) {
                float y = 0f;
                int idx = tpW;
                for (int k = 0; k < TP_TAPS; k++) {
                    y += tpBuf[idx] * tpKernel[p][k];
                    idx = (idx + 1) % TP_TAPS;
                }
                float ya = y < 0 ? -y : y;
                if (ya > tpFrameMax) tpFrameMax = ya;
            }
            if (tpFrameMax > truePeakHold) truePeakHold = tpFrameMax;
            else truePeakHold *= truePeakHoldDecay;
            float kw = biquad(s, hsCoefs, hsState);
            kw = biquad(kw, hpCoefs, hpState);
            float ms = kw * kw;
            float old400 = r400[r400W];
            r400[r400W] = ms;
            sum400 += ms - old400;
            r400W = (r400W + 1) % r400.length;
            float old3000 = r3000[r3000W];
            r3000[r3000W] = ms;
            sum3000 += ms - old3000;
            r3000W = (r3000W + 1) % r3000.length;
            strideAcc100++;
            if (strideAcc100 >= stride100) {
                strideAcc100 = 0;
                float meanMs = (float)(sum400 / r400.length);
                iBlockMs[iBlockW] = meanMs;
                iBlockW = (iBlockW + 1) % I_BLOCKS_MAX;
                if (iBlockCount < I_BLOCKS_MAX) iBlockCount++;
                recomputeIntegrated();
                momentaryLufs = (float)(-0.691
                        + 10 * Math.log10(Math.max(1e-9, meanMs)));
            }
            strideAcc1000++;
            if (strideAcc1000 >= stride1000) {
                strideAcc1000 = 0;
                float meanMs = (float)(sum3000 / r3000.length);
                lraBlockMs[lraBlockW] = meanMs;
                lraBlockW = (lraBlockW + 1) % LRA_BLOCKS_MAX;
                if (lraBlockCount < LRA_BLOCKS_MAX) lraBlockCount++;
                recomputeLRA();
            }
            histAcc++;
            if (histAcc >= histStride) {
                histAcc = 0;
                peakDb = (float)(20 * Math.log10(Math.max(1e-9f, peakEnv)));
                rmsDb  = (float)(10 * Math.log10(Math.max(1e-9f, rmsEnv)));
                shortLufs = (float)(-0.691
                        + 10 * Math.log10(Math.max(1e-9, sum3000 / r3000.length)));
                truePeakDb = (float)(20
                        * Math.log10(Math.max(1e-9f, truePeakHold)));
                envHist[histW] = peakDb;
                histW = (histW + 1) % HIST_LEN;
            }
        }
    }

    // BS.1770 integrated LUFS: −70 LUFS absolute gate, then relative
    // gate at (mean − 10 LU), recompute mean of survivors.
    private void recomputeIntegrated() {
        if (iBlockCount == 0) { integratedLufs = -80f; return; }
        double sum = 0; int n = 0;
        for (int k = 0; k < iBlockCount; k++) {
            float ms = iBlockMs[k];
            if (ms <= 0) continue;
            double lufs = -0.691 + 10 * Math.log10(ms);
            if (lufs < -70.0) continue;
            sum += ms; n++;
        }
        if (n == 0) { integratedLufs = -80f; return; }
        double meanLufs = -0.691 + 10 * Math.log10(sum / n);
        double rel = meanLufs - 10.0;
        double sum2 = 0; int n2 = 0;
        for (int k = 0; k < iBlockCount; k++) {
            float ms = iBlockMs[k];
            if (ms <= 0) continue;
            double lufs = -0.691 + 10 * Math.log10(ms);
            if (lufs < -70.0 || lufs < rel) continue;
            sum2 += ms; n2++;
        }
        integratedLufs = n2 > 0
                ? (float)(-0.691 + 10 * Math.log10(sum2 / n2))
                : (float) meanLufs;
    }

    // EBU R128 LRA: −70 LUFS abs gate + −20 LU relative gate,
    // LRA = LUFS_p95 − LUFS_p10 of survivors.
    private void recomputeLRA() {
        if (lraBlockCount < 2) { lra = 0f; return; }
        int ng = 0;
        double sumLufs = 0;
        for (int k = 0; k < lraBlockCount; k++) {
            float ms = lraBlockMs[k];
            if (ms <= 0) continue;
            double lufs = -0.691 + 10 * Math.log10(ms);
            if (lufs < -70.0) continue;
            lraSort[ng++] = (float) lufs;
            sumLufs += lufs;
        }
        if (ng < 2) { lra = 0f; return; }
        double meanLufs = sumLufs / ng;
        double rel = meanLufs - 20.0;
        int ng2 = 0;
        for (int k = 0; k < ng; k++) {
            if (lraSort[k] >= rel) lraSort[ng2++] = lraSort[k];
        }
        if (ng2 < 2) { lra = 0f; return; }
        java.util.Arrays.sort(lraSort, 0, ng2);
        int p10 = (int) Math.floor(0.10 * ng2);
        int p95 = (int) Math.ceil(0.95 * ng2) - 1;
        if (p95 < 0) p95 = 0;
        if (p95 >= ng2) p95 = ng2 - 1;
        lra = lraSort[p95] - lraSort[p10];
    }

    private static float biquad(float x, float[] c, float[] st) {
        float y = c[0] * x + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3];
        st[1] = st[0]; st[0] = x;
        st[3] = st[2]; st[2] = y;
        return y;
    }
    private static float[] highShelf(float fc, float q, float gainDb, int sr) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double beta = 2.0 * Math.sqrt(A) * alpha;
        double a0 = (A + 1) - (A - 1) * cs + beta;
        return new float[] {
            (float)(A * ((A + 1) + (A - 1) * cs + beta) / a0),
            (float)(-2 * A * ((A - 1) + (A + 1) * cs) / a0),
            (float)(A * ((A + 1) + (A - 1) * cs - beta) / a0),
            (float)(2 * ((A - 1) - (A + 1) * cs) / a0),
            (float)(((A + 1) - (A - 1) * cs - beta) / a0)
        };
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

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_M           = 0xFF6FE07A;
    private static final int COLOR_S           = 0xFFF5C842;
    private static final int COLOR_I           = 0xFF5BD9E0;
    private static final int COLOR_LRA         = 0xFFE36C9C;
    private static final int COLOR_TP          = 0xFFE34855;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            envLine;
    private PluginPath envPath;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        if (width < 60 || height < 60) return;
        prepareWindow(streams);
        analyseWindow();
        float W = width, H = height;
        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("VOCAL DYNAMICS", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText("ITU-R BS.1770-4 / EBU R128", W - 12f, 16f, textDim);

        float pad = 12f, headerH = 24f;
        // Top row: 5 numeric cards: M / S / I / LRA / TP.
        float cardY0 = pad + headerH;
        float cardH = 48f;
        float gap = 8f;
        float cardW = (W - pad * 2 - 4 * gap) / 5f;
        drawCard(canvas, pad + 0 * (cardW + gap), cardY0, cardW, cardH,
                "M",    String.format("%.1f", momentaryLufs),  "LUFS",      COLOR_M);
        drawCard(canvas, pad + 1 * (cardW + gap), cardY0, cardW, cardH,
                "S",    String.format("%.1f", shortLufs),      "LUFS",      COLOR_S);
        drawCard(canvas, pad + 2 * (cardW + gap), cardY0, cardW, cardH,
                "I",    String.format("%.1f", integratedLufs), "LUFS",      COLOR_I);
        drawCard(canvas, pad + 3 * (cardW + gap), cardY0, cardW, cardH,
                "LRA",  String.format("%.1f", lra),            "LU",        COLOR_LRA);
        drawCard(canvas, pad + 4 * (cardW + gap), cardY0, cardW, cardH,
                "TP",   String.format("%+.1f", truePeakDb),    "dBTP",      COLOR_TP);

        // Bottom: scrolling peak history with dB grid.
        float plotY0 = cardY0 + cardH + 10f;
        float plotY1 = H - pad - 16f;
        if (plotY1 - plotY0 < 30f) return;
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(pad + 28f, plotY0, W - pad, plotY1, 6f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(pad + 28f, plotY0, W - pad, plotY1, 6f, cardPaint);
        for (int db = 0; db >= -60; db -= 20) {
            float t = (-db) / 60f;
            float y = plotY0 + t * (plotY1 - plotY0);
            cardPaint.setColor(0xFF353638).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(pad + 28f, y, W - pad, y, cardPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            canvas.drawText(db + "", pad + 25f, y + 3f, textDim);
        }
        envPath.reset();
        float plotX0 = pad + 28f, plotX1 = W - pad;
        float plotW = plotX1 - plotX0, plotH = plotY1 - plotY0;
        float step = plotW / (HIST_LEN - 1f);
        boolean started = false;
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histW + i) % HIST_LEN;
            float db = envHist[idx];
            if (db < -60f) db = -60f; if (db > 0f) db = 0f;
            float px = plotX0 + i * step;
            float py = plotY0 + ((-db) / 60f) * plotH;
            if (!started) { envPath.moveTo(px, py); started = true; }
            else envPath.lineTo(px, py);
        }
        envLine.setColor(COLOR_TP).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
        canvas.drawPath(envPath, envLine);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9.5f).setTextAlign(0);
        canvas.drawText("peak history (last ~2.5 s)", plotX0 + 4f, plotY0 + 12f, textDim);
        float dr = (peakDb < -80f || rmsDb < -80f) ? 0f : (peakDb - rmsDb);
        textDim.setColor(COLOR_TEXT_BRIGHT).setTextAlign(2);
        canvas.drawText(String.format("DR  %.1f dB", dr),
                plotX1 - 4f, plotY0 + 12f, textDim);
    }

    private void drawCard(PluginCanvas canvas, float x, float y, float w, float h,
                           String tag, String value, String unit, int colour) {
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x, y, x + w, y + h, 6f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(x, y, x + w, y + h, 6f, cardPaint);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText(tag, x + 6f, y + 12f, textDim);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
        canvas.drawText(unit, x + w - 6f, y + 12f, textDim);
        textBright.setColor(colour).setTextSize(20f).setTextAlign(1);
        canvas.drawText(value, x + w * 0.5f, y + h - 10f, textBright);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        envLine    = c.newPaint();
        envPath    = c.newPath();
    }
}
