package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vibrato Analyzer — measures vibrato as a craft.  Extracts:
 *
 *   - Rate (Hz): how fast the cycle is (4 Hz lazy, 5–7 Hz musical,
 *     8 Hz+ nervous).
 *   - Depth (cents): peak-to-peak deviation around the note centre.
 *   - Regularity (0..1): how even the recent cycles are (high =
 *     controlled, low = unsteady).
 *   - Onset (ms): time from note-attack to first audible cycle.
 *
 * Approach: same YIN tracker as the other vocal-analysis plugins.
 * Smooth the cents-from-target signal with a 200 ms note-centre
 * lowpass to get the slow note centre, subtract to get the
 * deviation, then run autocorrelation on the deviation buffer to
 * find the dominant cycle period → rate.  Depth = RMS of the
 * deviation × 2.83 (peak-to-peak from RMS sinusoid).  Regularity
 * = 1 - (period stdev / period mean).
 */
public final class VibratoAnalyzer
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(devHist, 0f);
        java.util.Arrays.fill(centsHist, Float.NaN);
        ringW = 0; sampleAcc = 0; histW = 0;
        noteCentre = 0f; lastMidi = -1;
        noteOnsetSec = -1f;
        vibratoRate = 0f; vibratoDepth = 0f;
        vibratoReg = 0f; vibratoOnsetMs = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // ── YIN (same as PitchAccuracy) ──
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
    // Deviation buffer: last ~1 second (170 frames @ 5.8 ms/frame).
    private static final int DEV_LEN = 170;
    private final float[] devHist = new float[DEV_LEN];     // cents off the slow centre
    private final float[] centsHist = new float[DEV_LEN];   // raw cents for display
    private int histW = 0;
    private float noteCentre = 0f;
    private int lastMidi = -1;
    private float noteOnsetSec = -1f;    // wall time of last note start, seconds
    private float curWallSec = 0f;

    // Computed results.
    private float vibratoRate = 0f;       // Hz
    private float vibratoDepth = 0f;      // cents pk-to-pk
    private float vibratoReg = 0f;        // 0..1
    private float vibratoOnsetMs = 0f;    // ms from attack to detected vibrato

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % ANALYSIS_SIZE;
            sampleAcc++;
            if (sampleAcc >= ANALYSIS_HOP) {
                sampleAcc = 0;
                analyseFrame();
                curWallSec += ANALYSIS_HOP / (float) sampleRate;
            }
        }
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
        // Note-centre tracker: slow IIR on the absolute MIDI position,
        // so vibrato (fast) doesn't shift the centre but glissando
        // (slow) does.  Time constant ~200 ms.
        float midiAbs = (float) midi;
        if (lastMidi != midiRound) {
            // Note change → reset centre + onset timer.
            noteCentre = midiAbs;
            noteOnsetSec = curWallSec;
            vibratoOnsetMs = 0f;
            lastMidi = midiRound;
        } else {
            noteCentre += 0.05f * (midiAbs - noteCentre);  // ~30 ms IIR
        }
        float dev = (midiAbs - noteCentre) * 100f;   // cents off centre
        devHist[histW] = dev;
        centsHist[histW] = cents;
        histW = (histW + 1) % DEV_LEN;

        // Compute vibrato characteristics from the deviation buffer.
        computeVibratoStats();
    }

    private void computeVibratoStats() {
        // 1) Depth = RMS × 2.83 (peak-to-peak from sinusoid RMS).
        double sumSq = 0;
        for (float d : devHist) sumSq += d * d;
        float rmsDev = (float) Math.sqrt(sumSq / DEV_LEN);
        vibratoDepth = rmsDev * 2.83f;

        // 2) Rate via simple zero-crossing on the deviation signal —
        // count sign changes per full window, scale to Hz.
        int crossings = 0;
        float prev = devHist[(histW) % DEV_LEN];
        for (int i = 1; i < DEV_LEN; i++) {
            float cur = devHist[(histW + i) % DEV_LEN];
            if ((prev <= 0f && cur > 0f) || (prev >= 0f && cur < 0f)) crossings++;
            prev = cur;
        }
        // 2 crossings = 1 cycle. Window = DEV_LEN * ANALYSIS_HOP / SR.
        float windowSec = DEV_LEN * ANALYSIS_HOP / (float) sampleRate;
        vibratoRate = crossings * 0.5f / windowSec;

        // 3) Regularity: stdev / mean of cycle periods.  Cheap version
        // — track distance between successive zero crossings.
        int[] crossIdx = new int[DEV_LEN];
        int nCross = 0;
        prev = devHist[histW % DEV_LEN];
        for (int i = 1; i < DEV_LEN && nCross < crossIdx.length; i++) {
            float cur = devHist[(histW + i) % DEV_LEN];
            if (prev <= 0f && cur > 0f) crossIdx[nCross++] = i;
            prev = cur;
        }
        if (nCross >= 3) {
            float sum = 0, sumSqP = 0;
            int periods = nCross - 1;
            for (int i = 1; i < nCross; i++) {
                int p = crossIdx[i] - crossIdx[i - 1];
                sum += p; sumSqP += p * p;
            }
            float mean = sum / periods;
            float var = sumSqP / periods - mean * mean;
            if (var < 0f) var = 0f;
            float std = (float) Math.sqrt(var);
            vibratoReg = mean > 0f ? Math.max(0f, 1f - std / mean) : 0f;
        }

        // 4) Onset: if depth has crossed 10 cents AND we have an
        // attack time, the onset is "now - attackSec".  Otherwise 0.
        if (noteOnsetSec >= 0f && vibratoOnsetMs == 0f && vibratoDepth > 10f) {
            vibratoOnsetMs = Math.max(0f, (curWallSec - noteOnsetSec) * 1000f);
        }
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SIGNATURE   = 0xFFE36C9C; // pink
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
        float W = width, H = height;

        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);

        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("VIBRATO ANALYZER", 12f, 16f, textBright);

        // Layout: contour plot on top half, stat boxes below.
        float pad = 12f;
        float headerH = 24f;
        float statsH = 70f;
        float plotX0 = pad + 24f, plotX1 = W - pad;
        float plotY0 = pad + headerH;
        float plotY1 = H - pad - statsH;

        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        // Y axis: cents-off-centre, ±60 cents window.
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

        // Deviation contour.
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
        linePaint.setColor(COLOR_SIGNATURE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.5f);
        canvas.drawPath(devPath, linePaint);

        // Stats row — 4 cards.
        float statY0 = plotY1 + 10f;
        float statY1 = H - pad - 2f;
        float boxW = (plotW - 18f) / 4f;
        drawStatBox(canvas, plotX0,                    statY0, plotX0 + boxW,                    statY1,
                "RATE",      String.format("%.1f Hz", vibratoRate),    rateVerdict(vibratoRate));
        drawStatBox(canvas, plotX0 + (boxW + 6f),      statY0, plotX0 + (boxW + 6f) + boxW,      statY1,
                "DEPTH",     String.format("±%.0f c", vibratoDepth * 0.5f),
                depthVerdict(vibratoDepth));
        drawStatBox(canvas, plotX0 + 2 * (boxW + 6f),  statY0, plotX0 + 2 * (boxW + 6f) + boxW,  statY1,
                "REGULARITY", String.format("%.0f%%", vibratoReg * 100),
                regVerdict(vibratoReg));
        drawStatBox(canvas, plotX0 + 3 * (boxW + 6f),  statY0, plotX0 + 3 * (boxW + 6f) + boxW,  statY1,
                "ONSET",     vibratoOnsetMs > 0f ? String.format("%.0f ms", vibratoOnsetMs) : "-",
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
        if (hz >= 5f && hz <= 7f) return 0xFF6FE07A;   // musical
        if (hz >= 4f && hz <= 8f) return 0xFFE0C040;   // borderline
        if (hz > 0f) return 0xFFE0606A;                // off
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
