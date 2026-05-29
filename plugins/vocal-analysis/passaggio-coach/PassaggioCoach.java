package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.InferenceSession;
import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginHost;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Passaggio Coach — register-transition smoothness trainer.
 *
 * The passaggio (the chest→head "gear change", and its troublesome zone
 * around the upper-middle voice) is the single hardest thing in voice
 * training: cross it badly and the tone cracks, flips, or jams; cross it
 * well and chest blends through "mixed voice" into head with no audible
 * seam. This plugin coaches exactly that crossover.
 *
 * It is an ANALYTIC LAYER over the Register Detector's ML model, not a new
 * trained model. Each voiced frame it runs the same {@code register.onnx}
 * MLP (loaded as an asset) on the same byte-for-byte 5-feature vector from
 * {@link RegisterFeatures}, takes the softmax over CHEST/MIX/HEAD/FALSETTO/
 * BELT, and collapses it to a single continuous "register coordinate" on a
 * low→high axis (chest=0, mix=1, head/falsetto=2; belt sits near chest-mix).
 * That coordinate, the pitch, and the mix engagement are pushed through a
 * rolling history, and the smoothness of any in-progress transition is
 * scored live:
 *
 *   • mix engagement — a healthy passaggio rides MIX through the middle of
 *     the crossover instead of flipping chest→head with nothing between;
 *   • jerk — the second difference of the register coordinate; a smooth
 *     blend moves gradually, a break jumps;
 *   • voicing continuity — a crack drops voicing mid-transition.
 *
 * When no model / no host is available it falls back to the Register
 * Detector's literature-tuned heuristic, so the coordinate (and the coach)
 * still work, just less precisely.
 *
 * Honest scope: audio-only, no EGG; the register estimate underneath is a
 * better-calibrated estimator, not a clinical instrument. The smoothness
 * score is a coaching aid, not a grade.
 */
public final class PassaggioCoach
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;
    private final RegisterFeatures feat = new RegisterFeatures();

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        ringW = 0;
        java.util.Arrays.fill(scores, 0f);
        histN = 0; histW = 0;
        coordSmooth = 1f; mixSmooth = 0f; scoreSmooth = 0f;
        currentFreq = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // ── ML inference (optional) ──
    private PluginHost host;
    private InferenceSession model;
    private boolean modelTried = false;
    private boolean usingModel = false;
    private final float[] featVec = new float[5];

    @Override public void setHost(PluginHost h) { this.host = h; }

    private static final int FFT_N = RegisterFeatures.FFT_N;
    private final float[] audioRing = new float[FFT_N];
    private final float[] frame     = new float[FFT_N];
    private int ringW = 0;

    // ── Registers (mirror RegisterDetector order + heuristic tables) ──
    private static final int N_REG = 5;
    // CHEST, MIX, HEAD, FALSETTO, BELT — position on the chest→head axis.
    private static final float[] REG_COORD = { 0f, 1f, 2f, 2f, 0.5f };

    private static final float[] MU_F0    = { 200f, 380f, 550f, 750f, 520f };
    private static final float[] SG_F0    = { 100f, 130f, 150f, 200f, 150f };
    private static final float[] MU_H1H2  = {   1f,   5f,  11f,  16f,   0f };
    private static final float[] SG_H1H2  = {   4f,   4f,   4f,   5f,   4f };
    private static final float[] MU_H1A3  = {  15f,  22f,  30f,  35f,  12f };
    private static final float[] SG_H1A3  = {   8f,   8f,   8f,  10f,   8f };
    private static final float[] MU_HRF   = {  -3f,   3f,   8f,  15f,  -2f };
    private static final float[] SG_HRF   = {   4f,   4f,   5f,   6f,   4f };
    private static final float[] MU_SPR   = { -22f, -15f, -20f, -25f,  -7f };
    private static final float[] SG_SPR   = {   6f,   6f,   6f,   7f,   5f };

    private final float[] scores = new float[N_REG];

    // ── Rolling history of the crossover ──
    // ~6 s at one analysed frame per render (≈60 fps would be 360; we keep
    // a generous ring and only score the most recent WIN samples).
    private static final int HIST = 320;
    private final float[] hCoord  = new float[HIST]; // register coordinate 0..2
    private final float[] hF0     = new float[HIST]; // Hz (0 = unvoiced gap)
    private final float[] hMix    = new float[HIST]; // P(mix) 0..1
    private final boolean[] hVoiced = new boolean[HIST];
    private int histW = 0;   // write cursor (next slot)
    private int histN = 0;   // valid count (saturates at HIST)

    // Window of recent frames used to judge the live transition.
    private static final int WIN = 64;

    // Smoothed live read-outs.
    private float coordSmooth = 1f;
    private float mixSmooth = 0f;
    private float scoreSmooth = 0f;   // 0..1 smoothness
    private float currentFreq = 0f;
    private boolean voiced = false;

    // Transition state, recomputed each frame.
    private boolean inTransition = false;
    private float transRange = 0f;    // register-coord span over the window

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
        for (int i = 0; i < FFT_N; i++) {
            frame[i] = audioRing[(ringW + i) % FFT_N];
        }
        if (!feat.analyse(frame, sampleRate)) {
            voiced = false;
            currentFreq = 0f;
            pushHistory(coordSmooth, 0f, 0f, false);
            updateTransition();
            return;
        }
        voiced = true;
        currentFreq = feat.f0;

        if (!modelTried) {
            modelTried = true;
            if (host != null) {
                try { model = host.loadModel("register.onnx"); }
                catch (Throwable t) { model = null; }
            }
        }

        boolean scored = false;
        if (model != null) {
            featVec[0] = feat.f0;
            featVec[1] = feat.h1h2;
            featVec[2] = feat.h1a3;
            featVec[3] = feat.hrf;
            featVec[4] = feat.spr;
            try {
                float[] out = model.run(featVec, new long[] { 1, 5 });
                if (out != null && out.length >= N_REG) {
                    softmaxInto(out, scores, N_REG);
                    scored = true;
                    usingModel = true;
                }
            } catch (Throwable t) { /* fall through to heuristic */ }
        }
        if (!scored) {
            usingModel = false;
            scoreHeuristic();
            // Heuristic scores aren't normalised — make them a distribution.
            float sum = 0f;
            for (float s : scores) sum += s;
            if (sum > 1e-12f) for (int i = 0; i < N_REG; i++) scores[i] /= sum;
        }

        // Collapse the register distribution to a single coordinate + mix.
        float coord = 0f;
        for (int i = 0; i < N_REG; i++) coord += scores[i] * REG_COORD[i];
        float mix = scores[1] + 0.5f * scores[4]; // MIX (+ belt half-credit)

        // Light smoothing for the live read-outs.
        coordSmooth += 0.30f * (coord - coordSmooth);
        mixSmooth   += 0.30f * (mix   - mixSmooth);

        pushHistory(coordSmooth, currentFreq, mixSmooth, true);
        updateTransition();
    }

    private void pushHistory(float coord, float f0, float mix, boolean v) {
        hCoord[histW]  = coord;
        hF0[histW]     = f0;
        hMix[histW]    = mix;
        hVoiced[histW] = v;
        histW = (histW + 1) % HIST;
        if (histN < HIST) histN++;
    }

    // Look back over the last WIN samples and judge the crossover quality.
    private void updateTransition() {
        int n = Math.min(WIN, histN);
        if (n < 8) { inTransition = false; transRange = 0f; return; }

        float cMin = Float.MAX_VALUE, cMax = -Float.MAX_VALUE;
        float mixPeak = 0f;
        int voicedCount = 0;
        // Iterate oldest→newest across the ring window.
        int startSlot = (histW - n + HIST) % HIST;
        float prev1 = 0f, prev2 = 0f; boolean have1 = false, have2 = false;
        float jerkAccum = 0f; int jerkCount = 0;
        for (int k = 0; k < n; k++) {
            int s = (startSlot + k) % HIST;
            float c = hCoord[s];
            if (c < cMin) cMin = c;
            if (c > cMax) cMax = c;
            if (hVoiced[s]) {
                voicedCount++;
                if (hMix[s] > mixPeak) mixPeak = hMix[s];
            }
            if (have2) {
                // Second difference = curvature/jerk of the register path.
                jerkAccum += Math.abs(c - 2f * prev1 + prev2);
                jerkCount++;
            }
            prev2 = prev1; prev1 = c;
            have2 = have1; have1 = true;
        }
        transRange = cMax - cMin;
        float voicedFrac = (float) voicedCount / n;

        // A genuine transition: the register coordinate moved a meaningful
        // span (≥ ~0.45 of one register step) and at least half-voiced.
        inTransition = transRange >= 0.45f && voicedFrac >= 0.5f;

        if (!inTransition) {
            // No crossover under way — let the score relax toward neutral.
            scoreSmooth += 0.05f * (0.5f - scoreSmooth);
            return;
        }

        float meanJerk = jerkCount > 0 ? jerkAccum / jerkCount : 0f;
        // Normalise the penalties to 0..1.
        //  jerk: ~0.04/step is glassy-smooth, ~0.18 is a hard flip.
        float jerkPen = clamp01((meanJerk - 0.03f) / 0.15f);
        //  voicing: every unvoiced frame inside a transition is a crack risk.
        float gapPen  = clamp01((0.95f - voicedFrac) / 0.45f);
        //  mix: riding mix ≥0.5 through the middle is the ideal blend.
        float mixBonus = clamp01(mixPeak / 0.55f);

        float smooth = 0.35f + 0.45f * mixBonus - 0.50f * jerkPen - 0.40f * gapPen;
        smooth = clamp01(smooth);
        // Track the live transition responsively but without flicker.
        scoreSmooth += 0.15f * (smooth - scoreSmooth);
    }

    private void scoreHeuristic() {
        for (int i = 0; i < N_REG; i++) {
            float e1 = gauss(currentFreq, MU_F0[i],   SG_F0[i]);
            float e2 = gauss(feat.h1h2,   MU_H1H2[i], SG_H1H2[i]);
            float e3 = gauss(feat.h1a3,   MU_H1A3[i], SG_H1A3[i]);
            float e4 = gauss(feat.hrf,    MU_HRF[i],  SG_HRF[i]);
            float e5 = gauss(feat.spr,    MU_SPR[i],  SG_SPR[i]);
            scores[i] = e1 * e2 * e3 * e4 * e5;
        }
    }

    private static void softmaxInto(float[] src, float[] dst, int n) {
        float m = src[0];
        for (int i = 1; i < n; i++) if (src[i] > m) m = src[i];
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            float e = (float) Math.exp(src[i] - m);
            dst[i] = e; sum += e;
        }
        if (sum > 1e-12f) for (int i = 0; i < n; i++) dst[i] /= sum;
    }

    private static float gauss(float x, float c, float s) {
        float d = (x - c) / s;
        return (float) Math.exp(-0.5 * d * d);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    // Register colours along the chest→head axis (match Register Detector).
    private static final int COL_CHEST = 0xFFE34855;
    private static final int COL_MIX   = 0xFFEE8A2C;
    private static final int COL_HEAD  = 0xFF5BD9E0;
    private static final int COL_GOOD  = 0xFF4CCB6E;
    private static final int COL_WARN  = 0xFFF5C842;
    private static final int COL_BAD   = 0xFFE34855;

    private PluginPaint bgPaint, cardPaint, textBright, textDim, fg, trail;

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
        canvas.drawText("PASSAGGIO COACH", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText(usingModel ? "ml: register MLP" : "pro: register heuristic",
                W - 12f, 16f, textDim);

        // ── Left column: live verdict / score ──
        int scorePct = Math.round(scoreSmooth * 100f);
        int verdictCol;
        String verdict;
        if (!inTransition) {
            verdictCol = COLOR_TEXT_DIM;
            verdict = voiced ? "Hold steady" : "—";
        } else if (scoreSmooth >= 0.70f) {
            verdictCol = COL_GOOD;  verdict = "Smooth blend";
        } else if (scoreSmooth >= 0.45f) {
            verdictCol = COL_WARN;  verdict = "Slightly uneven";
        } else {
            verdictCol = COL_BAD;   verdict = "Mind the break";
        }

        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("BLEND", 12f, 36f, textDim);
        textBright.setColor(inTransition ? verdictCol : COLOR_TEXT_DIM)
                .setTextSize(34f).setTextAlign(0);
        canvas.drawText(inTransition ? (scorePct + "%") : "––", 12f, 68f, textBright);
        textBright.setColor(verdictCol).setTextSize(12f).setTextAlign(0);
        canvas.drawText(verdict, 12f, 88f, textBright);

        // Pitch + register coordinate read-out.
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        String reg = coordLabel(coordSmooth);
        canvas.drawText(voiced ? String.format("f0  %.0f Hz   %s", currentFreq, reg)
                               : "f0  —", 12f, 104f, textDim);

        // ── Register trail (right side): the path of the crossover ──
        float trailX0 = W * 0.42f;
        float trailX1 = W - 12f;
        float trailY0 = 30f;
        float trailY1 = H - 28f;
        // Card.
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(trailX0, trailY0, trailX1, trailY1, 6f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(trailX0, trailY0, trailX1, trailY1, 6f, cardPaint);

        // Register guide lines (chest / mix / head bands) + labels.
        drawGuide(canvas, trailX0, trailX1, coordToY(0f, trailY0, trailY1), "CHEST", COL_CHEST);
        drawGuide(canvas, trailX0, trailX1, coordToY(1f, trailY0, trailY1), "MIX",   COL_MIX);
        drawGuide(canvas, trailX0, trailX1, coordToY(2f, trailY0, trailY1), "HEAD",  COL_HEAD);

        // Plot the coordinate history left→old to right→now.
        int n = histN;
        if (n >= 2) {
            int startSlot = (histW - n + HIST) % HIST;
            float prevX = 0f, prevY = 0f; boolean havePrev = false;
            for (int k = 0; k < n; k++) {
                int s = (startSlot + k) % HIST;
                float fx = (float) k / (n - 1);
                float x = trailX0 + 3f + fx * (trailX1 - trailX0 - 6f);
                if (!hVoiced[s]) { havePrev = false; continue; } // gap = break in line
                float y = coordToY(hCoord[s], trailY0, trailY1);
                int col = blendCol(hCoord[s]);
                // Fade older samples.
                int alpha = 0x40 + (int) (0xBF * fx);
                col = (col & 0x00FFFFFF) | (alpha << 24);
                if (havePrev) {
                    trail.setColor(col).setStyle(PluginStyle.STROKE).setStrokeWidth(2.2f);
                    canvas.drawLine(prevX, prevY, x, y, trail);
                }
                prevX = x; prevY = y; havePrev = true;
            }
            // Mark the live head of the trail.
            if (voiced) {
                float y = coordToY(coordSmooth, trailY0, trailY1);
                fg.setColor(blendCol(coordSmooth)).setStyle(PluginStyle.FILL);
                canvas.drawCircle(trailX1 - 4f, y, 3.5f, fg);
            }
        }
    }

    private void drawGuide(PluginCanvas canvas, float x0, float x1, float y,
                           String label, int col) {
        trail.setColor((col & 0x00FFFFFF) | 0x33000000)
                .setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawLine(x0 + 3f, y, x1 - 3f, y, trail);
        textDim.setColor((col & 0x00FFFFFF) | 0x99000000).setTextSize(8f).setTextAlign(0);
        canvas.drawText(label, x0 + 5f, y - 2f, textDim);
    }

    private static float coordToY(float coord, float y0, float y1) {
        // coord 0 (chest) at bottom, 2 (head) at top.
        float t = clamp01(coord / 2f);
        return y1 - t * (y1 - y0);
    }

    private static int blendCol(float coord) {
        // Interpolate chest→mix→head along the 0..2 axis.
        if (coord <= 1f) return lerpCol(COL_CHEST, COL_MIX, clamp01(coord));
        return lerpCol(COL_MIX, COL_HEAD, clamp01(coord - 1f));
    }

    private static int lerpCol(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static String coordLabel(float coord) {
        if (coord < 0.4f) return "chest";
        if (coord < 0.85f) return "chest-mix";
        if (coord < 1.15f) return "mix";
        if (coord < 1.6f) return "mix-head";
        return "head";
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        fg         = c.newPaint();
        trail      = c.newPaint();
    }
}
