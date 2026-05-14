package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Pitch Accuracy — pass-through audio plugin measuring per-frame
 * cents-from-nearest-semitone, with **pYIN-style multi-candidate
 * YIN + Viterbi smoothing** to kill the octave errors that single-
 * threshold YIN is famous for.
 *
 *   - Each frame collects the best 3 YIN-CMND minima (not just the
 *     first below threshold).
 *   - Forward Viterbi over the last 8 frames picks the cheapest
 *     path through the candidate trellis.  Transition cost =
 *     λ · |Δsemitones|, so isolated octave jumps are dominated by
 *     a much cheaper "stay near previous" path.
 *   - Voicing probability = 1 − CMND of the chosen candidate;
 *     phrase-average error is voicing-probability-weighted so
 *     uncertain frames don't bias the score.
 */
public final class PitchAccuracy
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(centsHist, Float.NaN);
        for (int f = 0; f < VITERBI_N; f++) {
            for (int s = 0; s < K_CAND; s++) {
                candCmnd[f][s] = 1f;
                candSemi[f][s] = 0f;
            }
            candCount[f] = 0;
        }
        ringW = 0; sampleAcc = 0; histW = 0;
        bufW = 0; bufFilled = 0;
        currentMidi = 0; lastMidi = -1;
        attackCents = Float.NaN; releaseCents = Float.NaN;
        currentCents = Float.NaN; currentConfidence = 0f;
        phraseSumErr = 0; phraseSumW = 0; phraseAvgError = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // YIN config.
    private static final int   ANALYSIS_SIZE = 1024;
    private static final int   ANALYSIS_HOP  = 256;
    private static final int   LAG_MIN = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.15f;
    private static final float CAND_THRESHOLD = 0.45f;   // relaxed for candidate pool
    private static final float A4 = 440f;
    private final float[] audioRing = new float[ANALYSIS_SIZE];
    private final float[] yinBuf = new float[ANALYSIS_SIZE];
    private final float[] yinDiff = new float[LAG_MAX + 1];
    private final float[] yinCMND = new float[LAG_MAX + 1];
    private int ringW = 0, sampleAcc = 0;

    // Multi-candidate Viterbi smoothing.
    private static final int VITERBI_N = 8;
    private static final int K_CAND    = 3;
    private static final float LAMBDA  = 0.10f;          // transition weight
    private final float[][] candCmnd = new float[VITERBI_N][K_CAND];
    private final float[][] candSemi = new float[VITERBI_N][K_CAND];
    private final int[] candCount = new int[VITERBI_N];
    private final float[][] dpCost = new float[VITERBI_N][K_CAND];
    private final int[][]   dpBack = new int[VITERBI_N][K_CAND];
    private int bufW = 0, bufFilled = 0;

    // History rings.
    private static final int HIST_LEN = 384;
    private final float[] centsHist = new float[HIST_LEN];
    private int histW = 0;

    private int currentMidi = 0;
    private int lastMidi = -1;
    private float attackCents = Float.NaN, releaseCents = Float.NaN;
    private float currentCents = Float.NaN;
    private float currentConfidence = 0f;
    private double phraseSumErr = 0, phraseSumW = 0;
    private float phraseAvgError = 0f;

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
            pushFrameUnvoiced();
            centsHist[histW] = Float.NaN;
            histW = (histW + 1) % HIST_LEN;
            currentCents = Float.NaN;
            currentConfidence = 0f;
            if (lastMidi >= 0 && !Float.isNaN(currentCents)) releaseCents = currentCents;
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
        // Collect candidates: local minima below CAND_THRESHOLD, sorted by cmnd ascending.
        candCount[bufW] = 0;
        for (int tau = LAG_MIN; tau < maxLag - 1; tau++) {
            float v = yinCMND[tau];
            if (v < CAND_THRESHOLD && v <= yinCMND[tau - 1] && v <= yinCMND[tau + 1]) {
                // Parabolic interpolation around the minimum.
                float y1 = yinCMND[tau - 1], y2 = v, y3 = yinCMND[tau + 1];
                float denom = 2f * (2f * y2 - y1 - y3);
                float refined = tau;
                if (Math.abs(denom) > 1e-9f) {
                    float adj = (y3 - y1) / denom;
                    if (adj > -1f && adj < 1f) refined += adj;
                }
                float freq = sampleRate / refined;
                if (freq < 60f || freq > 1500f) continue;
                float semi = (float)(12.0 * (Math.log(freq / A4) / Math.log(2.0)) + 69.0);
                insertCandidate(bufW, v, semi);
            }
        }
        if (candCount[bufW] == 0) {
            // No good candidates — use a single fallback that takes the
            // global minimum, even if above threshold.
            int best = LAG_MIN; float bestV = yinCMND[LAG_MIN];
            for (int tau = LAG_MIN + 1; tau < maxLag; tau++) {
                if (yinCMND[tau] < bestV) { bestV = yinCMND[tau]; best = tau; }
            }
            float freq = sampleRate / best;
            if (freq >= 60f && freq <= 1500f) {
                float semi = (float)(12.0 * (Math.log(freq / A4) / Math.log(2.0)) + 69.0);
                candCmnd[bufW][0] = bestV;
                candSemi[bufW][0] = semi;
                candCount[bufW] = 1;
            }
        }
        if (candCount[bufW] == 0) {
            pushFrameUnvoiced();
            centsHist[histW] = Float.NaN;
            histW = (histW + 1) % HIST_LEN;
            return;
        }
        // Viterbi over last min(bufFilled+1, VITERBI_N) frames.
        bufFilled = Math.min(VITERBI_N, bufFilled + 1);
        int chosenCmnd = runViterbi();
        // chosenCmnd encodes (semi, cmnd) of the smoothed current frame.
        float smoothedSemi = lastChosenSemi;
        float smoothedCmnd = lastChosenCmnd;
        bufW = (bufW + 1) % VITERBI_N;

        int midiRound = Math.round(smoothedSemi);
        float cents = (smoothedSemi - midiRound) * 100f;
        currentMidi = midiRound;
        currentCents = cents;
        currentConfidence = Math.max(0f, 1f - smoothedCmnd);
        centsHist[histW] = cents;
        histW = (histW + 1) % HIST_LEN;
        // Note-change detection + attack/release tracking.
        if (midiRound != lastMidi) {
            attackCents = cents;
            if (lastMidi >= 0) releaseCents = cents;
            lastMidi = midiRound;
            phraseSumErr = Math.abs(cents) * currentConfidence;
            phraseSumW = currentConfidence;
        } else {
            phraseSumErr += Math.abs(cents) * currentConfidence;
            phraseSumW += currentConfidence;
        }
        phraseAvgError = phraseSumW > 1e-3 ? (float)(phraseSumErr / phraseSumW) : 0f;
    }

    // Insert (cmnd, semi) into candCmnd[frame][·] keeping the K_CAND
    // lowest-cmnd entries, sorted ascending by cmnd.
    private void insertCandidate(int frame, float cmnd, float semi) {
        int n = candCount[frame];
        // Find insertion point.
        int pos = n;
        for (int i = 0; i < n; i++) {
            if (cmnd < candCmnd[frame][i]) { pos = i; break; }
        }
        if (pos >= K_CAND) return;
        // Shift right.
        int limit = Math.min(K_CAND - 1, n);
        for (int i = limit; i > pos; i--) {
            candCmnd[frame][i] = candCmnd[frame][i - 1];
            candSemi[frame][i] = candSemi[frame][i - 1];
        }
        candCmnd[frame][pos] = cmnd;
        candSemi[frame][pos] = semi;
        if (n < K_CAND) candCount[frame] = n + 1;
    }

    private void pushFrameUnvoiced() {
        candCount[bufW] = 0;
        bufFilled = 0;        // reset Viterbi history at unvoiced gaps
        bufW = (bufW + 1) % VITERBI_N;
    }

    private float lastChosenSemi = 0f, lastChosenCmnd = 1f;

    private int runViterbi() {
        // Walk frames in chronological order starting at the oldest of
        // the bufFilled most-recent frames.
        int startIdx = (bufW + VITERBI_N - bufFilled + 1) % VITERBI_N;
        // Frame 0 of the trellis = startIdx in our ring.
        for (int s = 0; s < K_CAND; s++) {
            if (s < candCount[startIdx]) {
                dpCost[0][s] = candCmnd[startIdx][s];
            } else {
                dpCost[0][s] = Float.POSITIVE_INFINITY;
            }
            dpBack[0][s] = -1;
        }
        for (int f = 1; f < bufFilled; f++) {
            int curIdx = (startIdx + f) % VITERBI_N;
            int prevIdx = (startIdx + f - 1) % VITERBI_N;
            for (int s = 0; s < K_CAND; s++) {
                if (s >= candCount[curIdx]) {
                    dpCost[f][s] = Float.POSITIVE_INFINITY;
                    dpBack[f][s] = -1;
                    continue;
                }
                float obs = candCmnd[curIdx][s];
                float bestCost = Float.POSITIVE_INFINITY;
                int bestPrev = -1;
                for (int p = 0; p < K_CAND; p++) {
                    if (p >= candCount[prevIdx]) continue;
                    if (Float.isInfinite(dpCost[f - 1][p])) continue;
                    float trans = LAMBDA * Math.abs(candSemi[curIdx][s] - candSemi[prevIdx][p]);
                    float c = dpCost[f - 1][p] + trans;
                    if (c < bestCost) { bestCost = c; bestPrev = p; }
                }
                dpCost[f][s] = obs + bestCost;
                dpBack[f][s] = bestPrev;
            }
        }
        // Pick best final state.
        int lastF = bufFilled - 1;
        int bestS = 0;
        float bestC = dpCost[lastF][0];
        for (int s = 1; s < K_CAND; s++) {
            if (dpCost[lastF][s] < bestC) { bestC = dpCost[lastF][s]; bestS = s; }
        }
        // Read off the smoothed semi+cmnd for the most-recent frame.
        int curIdx = (startIdx + lastF) % VITERBI_N;
        lastChosenSemi = candSemi[curIdx][bestS];
        lastChosenCmnd = candCmnd[curIdx][bestS];
        return bestS;
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
        prepareWindow(streams);
        analyseFrame();
        float W = width, H = height;

        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("PITCH ACCURACY", 12f, 16f, textBright);
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

        float pad = 12f, headerH = 24f, statsH = 30f;
        float plotX0 = pad + 28f;
        float plotY0 = pad + headerH;
        float plotX1 = W - pad;
        float plotY1 = H - pad - statsH;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;

        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

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
        {
            float yTop = plotY1 - ((5 + 50f) / 100f) * plotH;
            float yBot = plotY1 - ((-5 + 50f) / 100f) * plotH;
            contourFill.setColor(0x336FE07A).setStyle(PluginStyle.FILL);
            canvas.drawRect(plotX0, yTop, plotX1, yBot, contourFill);
        }

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

        if (!Float.isNaN(currentCents)) {
            float cl = currentCents; if (cl < -50f) cl = -50f; if (cl > 50f) cl = 50f;
            float py = plotY1 - ((cl + 50f) / 100f) * plotH;
            int col = colourForCents(Math.abs(currentCents));
            dotPaint.setColor(col).setStyle(PluginStyle.FILL);
            canvas.drawCircle(plotX1, py, 4.5f, dotPaint);
        }

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
