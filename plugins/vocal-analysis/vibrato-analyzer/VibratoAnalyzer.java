package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vibrato Analyzer — measures vibrato as a craft.
 *
 * Rate detection uses **autocorrelation of the pitch-deviation
 * buffer** (robust to noise; the old zero-crossing approach was
 * easily fooled by jitter).  The autocorr peak in the 4–10 Hz lag
 * window is the dominant cycle period.
 *
 * Per-cycle stats are recorded **cycle-by-cycle** by detecting
 * positive zero-crossings on a lightly-smoothed deviation signal
 * and measuring depth + period between consecutive crossings.
 *
 * Vibrato "active" is asserted when the last ≥3 cycles have rate
 * stdev < 0.5 Hz AND depth ≥ 15 cents (≈ the threshold under
 * which classical pedagogy stops calling a wobble "vibrato").
 */
public final class VibratoAnalyzer
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(devHist, 0f);
        java.util.Arrays.fill(centsHist, Float.NaN);
        java.util.Arrays.fill(cycleRateHist, 0f);
        java.util.Arrays.fill(cycleDepthHist, 0f);
        ringW = 0; sampleAcc = 0; histW = 0;
        cycleN = 0;
        noteCentre = 0f; lastMidi = -1;
        noteOnsetSec = -1f; curWallSec = 0f;
        vibratoRate = 0f; vibratoDepth = 0f;
        vibratoReg = 0f; vibratoOnsetMs = 0f;
        vibratoActive = false;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // ── YIN ──
    private static final int   ANALYSIS_SIZE = 1024;
    private static final int   ANALYSIS_HOP  = 256;          // ~5.8 ms/frame at 44.1k
    private static final int   LAG_MIN = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.15f;
    private static final float A4 = 440f;
    private final float[] audioRing = new float[ANALYSIS_SIZE];
    private final float[] yinBuf = new float[ANALYSIS_SIZE];
    private final float[] yinDiff = new float[LAG_MAX + 1];
    private final float[] yinCMND = new float[LAG_MAX + 1];
    private int ringW = 0, sampleAcc = 0;

    // ── Vibrato analysis state ──
    private static final int DEV_LEN = 170;                 // ~1 s
    private final float[] devHist = new float[DEV_LEN];     // cents off slow centre
    private final float[] centsHist = new float[DEV_LEN];   // raw cents for display
    private int histW = 0;
    private float noteCentre = 0f;
    private int lastMidi = -1;
    private float noteOnsetSec = -1f;
    private float curWallSec = 0f;

    // Per-cycle ring (last 8 cycles).
    private static final int CYC_HIST = 8;
    private final float[] cycleRateHist  = new float[CYC_HIST];
    private final float[] cycleDepthHist = new float[CYC_HIST];
    private int cycleN = 0;

    private float vibratoRate = 0f;       // Hz (autocorrelation)
    private float vibratoDepth = 0f;      // cents pk-to-pk (most recent cycle)
    private float vibratoReg = 0f;        // 0..1 (1 - rate_stdev / mean)
    private float vibratoOnsetMs = 0f;    // ms from attack to detected vibrato
    private boolean vibratoActive = false;

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
        if (rms < 0.003f) {
            devHist[histW] = 0f;
            centsHist[histW] = Float.NaN;
            histW = (histW + 1) % DEV_LEN;
            lastMidi = -1;
            return;
        }
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
        if (chosen < 0) return;
        float refined = chosen;
        if (chosen > 0 && chosen < maxLag) {
            float y1 = yinCMND[chosen - 1];
            float y2 = yinCMND[chosen];
            float y3 = yinCMND[chosen + 1];
            float denom = 2f * (2f * y2 - y1 - y3);
            if (Math.abs(denom) > 1e-9f) {
                float adj = (y3 - y1) / denom;
                if (adj > -1f && adj < 1f) refined += adj;
            }
        }
        float freq = sampleRate / refined;
        if (freq < 60f || freq > 1500f) return;
        double semitones = 12.0 * (Math.log(freq / A4) / Math.log(2.0));
        double midi = 69.0 + semitones;
        int midiRound = (int) Math.round(midi);
        float cents = (float) ((midi - midiRound) * 100.0);
        float midiAbs = (float) midi;
        if (lastMidi != midiRound) {
            noteCentre = midiAbs;
            noteOnsetSec = curWallSec;
            vibratoOnsetMs = 0f;
            cycleN = 0;
            lastMidi = midiRound;
        } else {
            noteCentre += 0.05f * (midiAbs - noteCentre);
        }
        float dev = (midiAbs - noteCentre) * 100f;
        devHist[histW] = dev;
        centsHist[histW] = cents;
        histW = (histW + 1) % DEV_LEN;
        computeVibratoStats();
    }

    private void computeVibratoStats() {
        // 1) Autocorrelation rate: search lag corresponding to 4-10 Hz.
        float frameHz = sampleRate / (float) ANALYSIS_HOP;
        int lagMin = Math.max(2, (int) Math.floor(frameHz / 10f));   // 10 Hz
        int lagMax = Math.min(DEV_LEN / 3, (int) Math.ceil(frameHz / 4f));  // 4 Hz
        // Walk dev in chronological order — copy into a flat array.
        float[] xs = new float[DEV_LEN];
        double mean = 0;
        for (int i = 0; i < DEV_LEN; i++) {
            xs[i] = devHist[(histW + i) % DEV_LEN];
            mean += xs[i];
        }
        mean /= DEV_LEN;
        for (int i = 0; i < DEV_LEN; i++) xs[i] -= (float) mean;
        // Autocorrelation peak in [lagMin, lagMax].
        float bestVal = -1e30f; int bestLag = lagMin;
        float[] autoBuf = new float[lagMax + 2];
        for (int lag = lagMin; lag <= lagMax; lag++) {
            float sum = 0f;
            int span = DEV_LEN - lag;
            for (int i = 0; i < span; i++) sum += xs[i] * xs[i + lag];
            autoBuf[lag] = sum;
            if (sum > bestVal) { bestVal = sum; bestLag = lag; }
        }
        float refinedLag = bestLag;
        if (bestLag > lagMin && bestLag < lagMax) {
            float a0 = autoBuf[bestLag - 1], a1 = autoBuf[bestLag], a2 = autoBuf[bestLag + 1];
            float denom = (a0 - 2f * a1 + a2);
            if (Math.abs(denom) > 1e-9f) refinedLag = bestLag + 0.5f * (a0 - a2) / denom;
        }
        // Only report a rate if the autocorr peak is significant.
        double sumSq = 0;
        for (int i = 0; i < DEV_LEN; i++) sumSq += xs[i] * xs[i];
        float autoNorm = (float)(sumSq > 1e-6 ? bestVal / sumSq : 0);
        if (autoNorm > 0.25f && refinedLag > 0f) {
            vibratoRate = frameHz / refinedLag;
        } else {
            vibratoRate = 0f;
        }

        // 2) Per-cycle measurement via positive zero crossings.
        // Smooth lightly first (3-tap moving average).
        float[] sm = new float[DEV_LEN];
        sm[0] = xs[0]; sm[DEV_LEN - 1] = xs[DEV_LEN - 1];
        for (int i = 1; i < DEV_LEN - 1; i++) sm[i] = (xs[i-1] + xs[i] + xs[i+1]) / 3f;
        // Find positive zero crossings.
        int[] crossIdx = new int[DEV_LEN];
        int nCross = 0;
        float prev = sm[0];
        for (int i = 1; i < DEV_LEN; i++) {
            if (prev <= 0f && sm[i] > 0f) crossIdx[nCross++] = i;
            prev = sm[i];
        }
        // For each pair of consecutive crossings, compute period + depth.
        cycleN = 0;
        for (int c = 1; c < nCross && cycleN < CYC_HIST; c++) {
            int i0 = crossIdx[c - 1], i1 = crossIdx[c];
            float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
            for (int k = i0; k <= i1; k++) {
                if (sm[k] < lo) lo = sm[k];
                if (sm[k] > hi) hi = sm[k];
            }
            float pkpk = hi - lo;
            float period = i1 - i0;
            cycleRateHist[cycleN]  = period > 0f ? frameHz / period : 0f;
            cycleDepthHist[cycleN] = pkpk;
            cycleN++;
        }
        // Last cycle = most recent → primary depth readout.
        if (cycleN > 0) vibratoDepth = cycleDepthHist[cycleN - 1];

        // 3) Regularity from cycle-rate stdev.
        if (cycleN >= 3) {
            float sumR = 0, sumRSq = 0;
            for (int i = 0; i < cycleN; i++) { sumR += cycleRateHist[i]; sumRSq += cycleRateHist[i] * cycleRateHist[i]; }
            float m = sumR / cycleN;
            float v = sumRSq / cycleN - m * m; if (v < 0) v = 0;
            float std = (float) Math.sqrt(v);
            vibratoReg = m > 0f ? Math.max(0f, 1f - std / m) : 0f;
        } else if (cycleN > 0) {
            vibratoReg = 0f;
        }

        // 4) Vibrato active: ≥3 cycles, rate stdev < 0.5 Hz, depth ≥ 15 c.
        boolean active = false;
        if (cycleN >= 3) {
            float sumR = 0, sumRSq = 0, sumD = 0;
            for (int i = 0; i < cycleN; i++) {
                sumR += cycleRateHist[i];
                sumRSq += cycleRateHist[i] * cycleRateHist[i];
                sumD += cycleDepthHist[i];
            }
            float m = sumR / cycleN;
            float vv = sumRSq / cycleN - m * m; if (vv < 0) vv = 0;
            float std = (float) Math.sqrt(vv);
            float mD = sumD / cycleN;
            active = std < 0.5f && mD >= 15f;
        }
        vibratoActive = active;
        if (active && noteOnsetSec >= 0f && vibratoOnsetMs == 0f) {
            vibratoOnsetMs = Math.max(0f, (curWallSec - noteOnsetSec) * 1000f);
        }
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SIGNATURE   = 0xFFE36C9C;
    private static final int COLOR_ACTIVE      = 0xFF6FE07A;
    private static final int COLOR_GRID        = 0xFF202125;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, linePaint, fillPaint, statPaint;
    private PluginPath devPath, fillPath;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        if (width < 60 || height < 60) return;
        prepareWindow(streams);
        curWallSec = timeMs / 1000f;
        analyseFrame();
        float W = width, H = height;

        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("VIBRATO ANALYZER", 12f, 16f, textBright);
        if (vibratoActive) {
            textBright.setColor(COLOR_ACTIVE).setTextSize(11f).setTextAlign(2);
            canvas.drawText("● VIBRATO ACTIVE", W - 12f, 16f, textBright);
        } else {
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(11f).setTextAlign(2);
            canvas.drawText("○ no vibrato", W - 12f, 16f, textDim);
        }

        float pad = 12f, headerH = 24f, statsH = 70f;
        float plotX0 = pad + 24f, plotX1 = W - pad;
        float plotY0 = pad + headerH;
        float plotY1 = H - pad - statsH;

        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        float plotW = plotX1 - plotX0, plotH = plotY1 - plotY0;
        int[] gridC = { -50, -25, 0, 25, 50 };
        for (int c : gridC) {
            float t = (c + 60f) / 120f;
            float y = plotY1 - t * plotH;
            gridPaint.setColor(c == 0 ? 0xFF353638 : COLOR_GRID)
                    .setStyle(PluginStyle.STROKE).setStrokeWidth(c == 0 ? 1.1f : 0.6f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            canvas.drawText((c > 0 ? "+" : "") + c + "c", plotX0 - 3f, y + 3f, textDim);
        }

        devPath.reset();
        fillPath.reset();
        float step = plotW / (DEV_LEN - 1f);
        boolean started = false;
        for (int i = 0; i < DEV_LEN; i++) {
            int idx = (histW + i) % DEV_LEN;
            float d = devHist[idx];
            if (d < -60f) d = -60f; if (d > 60f) d = 60f;
            float px = plotX0 + i * step;
            float py = plotY1 - ((d + 60f) / 120f) * plotH;
            if (!started) {
                devPath.moveTo(px, py);
                fillPath.moveTo(px, plotY0 + plotH * 0.5f).lineTo(px, py);
                started = true;
            } else {
                devPath.lineTo(px, py);
                fillPath.lineTo(px, py);
            }
        }
        fillPath.lineTo(plotX0 + (DEV_LEN - 1) * step, plotY0 + plotH * 0.5f).close();
        fillPaint.setColor(0x33E36C9C).setStyle(PluginStyle.FILL);
        canvas.drawPath(fillPath, fillPaint);
        linePaint.setColor(vibratoActive ? COLOR_ACTIVE : COLOR_SIGNATURE)
                .setStyle(PluginStyle.STROKE).setStrokeWidth(1.5f);
        canvas.drawPath(devPath, linePaint);

        // Stats row — 4 cards.
        float statY0 = plotY1 + 10f;
        float statY1 = H - pad - 2f;
        float boxW = (plotW - 18f) / 4f;
        drawStatBox(canvas, plotX0, statY0, plotX0 + boxW, statY1,
                "RATE", String.format("%.1f Hz", vibratoRate), rateVerdict(vibratoRate));
        drawStatBox(canvas, plotX0 + (boxW + 6f), statY0, plotX0 + (boxW + 6f) + boxW, statY1,
                "DEPTH", String.format("±%.0f c", vibratoDepth * 0.5f),
                depthVerdict(vibratoDepth));
        drawStatBox(canvas, plotX0 + 2 * (boxW + 6f), statY0,
                plotX0 + 2 * (boxW + 6f) + boxW, statY1,
                "REGULARITY", String.format("%.0f%%", vibratoReg * 100),
                regVerdict(vibratoReg));
        drawStatBox(canvas, plotX0 + 3 * (boxW + 6f), statY0,
                plotX0 + 3 * (boxW + 6f) + boxW, statY1,
                "ONSET", vibratoOnsetMs > 0f ? String.format("%.0f ms", vibratoOnsetMs) : "-",
                COLOR_TEXT_DIM);
    }

    private void drawStatBox(PluginCanvas canvas, float x0, float y0, float x1, float y1,
                              String label, String value, int valColor) {
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0, x1, y1, 6f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(x0, y0, x1, y1, 6f, cardPaint);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(8f).setTextAlign(1);
        canvas.drawText(label, (x0 + x1) * 0.5f, y0 + 11f, textDim);
        statPaint.setColor(valColor).setTextSize(14f).setTextAlign(1);
        canvas.drawText(value, (x0 + x1) * 0.5f, y1 - 8f, statPaint);
    }

    private static int rateVerdict(float hz) {
        if (hz >= 5f && hz <= 7f) return 0xFF6FE07A;
        if (hz >= 4f && hz <= 8f) return 0xFFE0C040;
        if (hz > 0f) return 0xFFE0606A;
        return COLOR_TEXT_DIM;
    }
    private static int depthVerdict(float cents) {
        if (cents >= 20f && cents <= 70f) return 0xFF6FE07A;
        if (cents > 0f) return 0xFFE0C040;
        return COLOR_TEXT_DIM;
    }
    private static int regVerdict(float r) {
        if (r >= 0.7f) return 0xFF6FE07A;
        if (r >= 0.4f) return 0xFFE0C040;
        if (r > 0f) return 0xFFE0606A;
        return COLOR_TEXT_DIM;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        gridPaint  = c.newPaint();
        linePaint  = c.newPaint();
        fillPaint  = c.newPaint();
        statPaint  = c.newPaint();
        devPath    = c.newPath();
        fillPath   = c.newPath();
    }
}
