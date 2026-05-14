package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Pitch Accuracy — pass-through audio plugin that measures, frame
 * by frame, the cents-from-nearest-semitone error and the slow
 * drift of pitch through a sustained note.  Visualises:
 *
 *   - Scrolling cents-error contour (±50 cents window).
 *   - Cents-from-target colour: ≤5 c green, ≤20 c yellow, more red.
 *   - Phrase-level summary: average cents error, longest drift.
 *   - Attack accuracy: cents at note onset vs target.
 *   - Release accuracy: cents at note end vs target.
 *
 * Uses the same YIN (cumulative-mean-normalized-difference) tracker
 * the Auto-Tune and Vocal Tuner plugins use, so per-frame readings
 * are consistent across the vocal suite.
 */
public final class PitchAccuracy
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(centsHist, Float.NaN);
        ringW = 0; sampleAcc = 0; histW = 0;
        currentMidi = 0; lastMidi = -1;
        attackCents = Float.NaN; releaseCents = Float.NaN;
        currentCents = Float.NaN; currentConfidence = 0f;
        phraseSumErr = 0; phraseFrames = 0;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // ── YIN tracker (mirrors the Auto-Tune / Vocal Tuner impl) ──
    private static final int   ANALYSIS_SIZE   = 1024;
    private static final int   ANALYSIS_HOP    = 256;
    private static final int   LAG_MIN = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.15f;
    private static final float A4 = 440f;
    private final float[] audioRing = new float[ANALYSIS_SIZE];
    private final float[] yinBuf = new float[ANALYSIS_SIZE];
    private final float[] yinDiff = new float[LAG_MAX + 1];
    private final float[] yinCMND = new float[LAG_MAX + 1];
    private int ringW = 0, sampleAcc = 0;

    // ── History rings ──
    private static final int HIST_LEN = 384;
    private final float[] centsHist = new float[HIST_LEN];  // NaN = unvoiced
    private int histW = 0;
    private int currentMidi = 0;       // current rounded MIDI note
    private int lastMidi = -1;         // for note-change detection
    private float attackCents = Float.NaN, releaseCents = Float.NaN;
    private float currentCents = Float.NaN;
    private float currentConfidence = 0f;
    // Phrase-level stats — accumulate while a note is sustained.
    private double phraseSumErr = 0;
    private int phraseFrames = 0;
    private float phraseAvgError = 0f;

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
            }
        }
    }

    private void analyseFrame() {
        // Linearise ring + energy gate.
        double energy = 0;
        for (int i = 0; i < ANALYSIS_SIZE; i++) {
            int idx = (ringW + i) % ANALYSIS_SIZE;
            float v = audioRing[idx];
            yinBuf[i] = v;
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / ANALYSIS_SIZE);
        if (rms < 0.003f) {
            // Unvoiced — push NaN and reset transient note tracking.
            centsHist[histW] = Float.NaN;
            histW = (histW + 1) % HIST_LEN;
            currentCents = Float.NaN;
            currentConfidence = 0f;
            if (lastMidi >= 0 && !Float.isNaN(currentCents)) {
                releaseCents = currentCents;
            }
            lastMidi = -1;
            return;
        }
        // YIN difference.
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
        if (chosen < 0) {
            centsHist[histW] = Float.NaN;
            histW = (histW + 1) % HIST_LEN;
            currentConfidence = 0f;
            return;
        }
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
        currentConfidence = 1f - yinCMND[chosen];
        double semitones = 12.0 * (Math.log(freq / A4) / Math.log(2.0));
        double midi = 69.0 + semitones;
        int midiRound = (int) Math.round(midi);
        float cents = (float) ((midi - midiRound) * 100.0);
        currentMidi = midiRound;
        currentCents = cents;
        centsHist[histW] = cents;
        histW = (histW + 1) % HIST_LEN;
        // Detect note change → snapshot release of previous note +
        // attack of new note.
        if (midiRound != lastMidi) {
            attackCents = cents;
            if (lastMidi >= 0) releaseCents = cents;
            lastMidi = midiRound;
            phraseSumErr = Math.abs(cents);
            phraseFrames = 1;
        } else {
            phraseSumErr += Math.abs(cents);
            phraseFrames++;
        }
        phraseAvgError = phraseFrames > 0 ? (float)(phraseSumErr / phraseFrames) : 0f;
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_ACCENT      = 0xFFF5C842;
    private static final int COLOR_GREEN       = 0xFF6FE07A;
    private static final int COLOR_YELLOW      = 0xFFE0C040;
    private static final int COLOR_RED         = 0xFFE0606A;
    private static final int COLOR_GRID        = 0xFF202125;

    private static final String[] NOTE_NAMES = {
        "C","C#","D","D#","E","F","F#","G","G#","A","A#","B"
    };

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, contourLine, contourFill, dotPaint;
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

        // Header.
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("PITCH ACCURACY", 12f, 16f, textBright);
        // Current note + cents on the right.
        if (!Float.isNaN(currentCents) && currentConfidence > 0.3f) {
            int idx = ((currentMidi % 12) + 12) % 12;
            int octave = (currentMidi / 12) - 1;
            String note = NOTE_NAMES[idx] + octave;
            int col = colourForCents(Math.abs(currentCents));
            textBright.setColor(col).setTextSize(14f).setTextAlign(2);
            canvas.drawText(String.format("%s  %+.1f c", note, currentCents),
                    W - 12f, 17f, textBright);
        } else {
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(11f).setTextAlign(2);
            canvas.drawText("-", W - 12f, 17f, textDim);
        }

        // Layout: scrolling cents contour plot fills most of the
        // canvas; stats row at the bottom.
        float pad = 12f;
        float headerH = 24f;
        float statsH = 30f;
        float plotX0 = pad + 28f;
        float plotY0 = pad + headerH;
        float plotX1 = W - pad;
        float plotY1 = H - pad - statsH;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;

        // Card.
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        // Horizontal grid: 0, ±10, ±25, ±50 cents.
        int[] gridCents = { -50, -25, -10, 0, 10, 25, 50 };
        for (int c : gridCents) {
            float t = (c + 50f) / 100f;
            float y = plotY1 - t * plotH;
            int col = c == 0 ? COLOR_GREEN : COLOR_GRID;
            gridPaint.setColor(col).setStyle(PluginStyle.STROKE).setStrokeWidth(c == 0 ? 1.2f : 0.6f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            canvas.drawText((c > 0 ? "+" : "") + c, plotX0 - 3f, y + 3f, textDim);
        }

        // ±5 cents "in tune" band — translucent green fill.
        {
            float yTop = plotY1 - ((5 + 50f) / 100f) * plotH;
            float yBot = plotY1 - ((-5 + 50f) / 100f) * plotH;
            contourFill.setColor(0x336FE07A).setStyle(PluginStyle.FILL);
            canvas.drawRect(plotX0, yTop, plotX1, yBot, contourFill);
        }

        // Scrolling cents contour — newest sample on the right.
        linePath.reset();
        boolean started = false;
        float step = plotW / (HIST_LEN - 1f);
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histW + i) % HIST_LEN;
            float c = centsHist[idx];
            if (Float.isNaN(c)) { started = false; continue; }
            float cl = c; if (cl < -50f) cl = -50f; if (cl > 50f) cl = 50f;
            float px = plotX0 + i * step;
            float py = plotY1 - ((cl + 50f) / 100f) * plotH;
            if (!started) { linePath.moveTo(px, py); started = true; }
            else linePath.lineTo(px, py);
        }
        contourLine.setColor(COLOR_ACCENT).setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f);
        canvas.drawPath(linePath, contourLine);

        // Current-value dot at the right edge, coloured by accuracy.
        if (!Float.isNaN(currentCents)) {
            float cl = currentCents; if (cl < -50f) cl = -50f; if (cl > 50f) cl = 50f;
            float py = plotY1 - ((cl + 50f) / 100f) * plotH;
            int col = colourForCents(Math.abs(currentCents));
            dotPaint.setColor(col).setStyle(PluginStyle.FILL);
            canvas.drawCircle(plotX1, py, 4.5f, dotPaint);
        }

        // Stats row.
        float statsY = H - pad - 4f;
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(10f).setTextAlign(0);
        canvas.drawText(
                String.format("phrase avg  %.1f c", phraseAvgError),
                plotX0, statsY, textDim);
        if (!Float.isNaN(attackCents)) {
            int col = colourForCents(Math.abs(attackCents));
            textDim.setColor(col);
            canvas.drawText(String.format("attack  %+.1f c", attackCents),
                    plotX0 + plotW * 0.34f, statsY, textDim);
        }
        if (!Float.isNaN(releaseCents)) {
            int col = colourForCents(Math.abs(releaseCents));
            textDim.setColor(col);
            canvas.drawText(String.format("release  %+.1f c", releaseCents),
                    plotX0 + plotW * 0.65f, statsY, textDim);
        }
        textDim.setColor(COLOR_TEXT_DIM).setTextAlign(2);
        canvas.drawText(String.format("conf %.0f%%", currentConfidence * 100),
                plotX1, statsY, textDim);
    }

    private static int colourForCents(float absCents) {
        if (absCents <= 5f) return COLOR_GREEN;
        if (absCents <= 20f) return COLOR_YELLOW;
        return COLOR_RED;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint     = c.newPaint();
        cardPaint   = c.newPaint();
        textBright  = c.newPaint();
        textDim     = c.newPaint();
        gridPaint   = c.newPaint();
        contourLine = c.newPaint();
        contourFill = c.newPaint();
        dotPaint    = c.newPaint();
        linePath    = c.newPath();
        fillPath    = c.newPath();
    }
}
