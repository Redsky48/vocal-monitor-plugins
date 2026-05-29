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
 * Technique Detector — multi-label vocal-technique classifier.
 *
 * Where {@link RegisterDetector} answers "which register" (a single class),
 * this answers "which expressive techniques are happening right now", and
 * several can be true at once: a note can be breathy AND vibrato. So instead
 * of one softmax it runs an independent sigmoid per technique.
 *
 * It shares {@link RegisterFeatures} byte-for-byte with the offline trainer
 * (tools/register-trainer/train_technique.py), exactly like the register
 * detector — the 5-feature vector [f0, H1*-H2*, H1-A3, HRF, SPR] the phone
 * computes live is the same one technique.onnx was trained on.
 *
 * Honest scope: RegisterFeatures is a PER-FRAME (46 ms) extractor, so the
 * techniques surfaced here are the ones that are decidable from a single
 * frame's spectrum — breathiness (a phonation quality) and pharyngeal /
 * twang resonance. Genuinely temporal ornaments (vibrato rate, glissando
 * slide) need a sequence model and are intentionally NOT claimed here; the
 * vibrato-analyzer plugin owns that. The trainer's per-label ROC-AUC report
 * is what decided this set.
 *
 *  - **ML (preferred)** — host hands us technique.onnx; the 5-feature vector
 *    is fed to the MLP and each logit is squashed with a sigmoid into an
 *    independent 0..1 technique probability.
 *  - **Heuristic fallback** — no host / no model: per-technique scores from
 *    literature-tuned feature thresholds, so the panel always works.
 */
public final class TechniqueDetector
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;
    private final RegisterFeatures feat = new RegisterFeatures();

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        ringW = 0;
        java.util.Arrays.fill(prob, 0f);
        java.util.Arrays.fill(probSmooth, 0f);
        currentFreq = 0f; h1h2 = 0f; h1a3 = 0f; hrf = 0f; spr = 0f; oq = 0.5f;
        voiced = false;
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

    // ── Techniques ──
    // ORDER MUST MATCH train_technique.py's --labels order baked into
    // technique.onnx. Latvian display names for the on-device UI.
    private static final int N_TECH = 2;
    private static final String[] TECH    = { "BREATHY", "TWANG" };
    private static final String[] TECH_EN = { "airy phonation", "pharyngeal" };
    private static final int[] TECH_COLOURS = {
        0xFF5BD9E0, 0xFFF5C842
    };
    // Short pedagogical hint shown under the active technique.
    private static final String[] TECH_HINT = {
        "air mixed into the tone",     // breathy
        "bright pharyngeal resonance"  // pharyngeal / twang
    };

    private final float[] prob       = new float[N_TECH];
    private final float[] probSmooth = new float[N_TECH];
    private boolean voiced = false;

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
        for (int i = 0; i < FFT_N; i++) {
            frame[i] = audioRing[(ringW + i) % FFT_N];
        }
        if (!feat.analyse(frame, sampleRate)) {
            for (int i = 0; i < N_TECH; i++) probSmooth[i] *= 0.85f;
            voiced = false;
            return;
        }
        voiced = true;
        currentFreq = feat.f0;
        h1h2 = feat.h1h2;
        h1a3 = feat.h1a3;
        hrf  = feat.hrf;
        spr  = feat.spr;
        oq   = feat.oq;

        if (!modelTried) {
            modelTried = true;
            if (host != null) {
                try { model = host.loadModel("technique.onnx"); }
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
                if (out != null && out.length >= N_TECH) {
                    for (int i = 0; i < N_TECH; i++) prob[i] = sigmoid(out[i]);
                    scored = true;
                    usingModel = true;
                }
            } catch (Throwable t) {
                // One bad inference shouldn't kill the panel.
            }
        }
        if (!scored) {
            usingModel = false;
            scoreHeuristic();
        }

        for (int i = 0; i < N_TECH; i++) {
            probSmooth[i] += 0.25f * (prob[i] - probSmooth[i]);
        }
    }

    // Heuristic fallback when no model is available.
    //   breathy    — open-quotient / spectral-tilt voice: high H1*-H2*,
    //                low HRF (energy concentrated in the fundamental).
    //   pharyngeal — bright twang: high SPR (2-4 kHz energy lifted).
    private void scoreHeuristic() {
        float breathy = logistic(h1h2, 8f, 0.35f)
                      * logistic(-hrf, 0f, 0.25f);
        float pharyngeal = logistic(spr, -12f, 0.30f);
        prob[0] = clamp01(breathy);
        prob[1] = clamp01(pharyngeal);
    }

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    // Logistic ramp centred at c with slope k.
    private static float logistic(float x, float c, float k) {
        return (float) (1.0 / (1.0 + Math.exp(-k * (x - c))));
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
    // Per-technique "active" cutoff = F1-optimal threshold on held-out singers
    // (train_technique.py, 28-singer GTSinger+VocalSet split). Indexed like TECH.
    //   breathy    AUC 0.729  thr 0.30
    //   pharyngeal AUC 0.732  thr 0.55
    private static final float[] ACTIVE_THRESH = { 0.30f, 0.55f };

    private PluginPaint bgPaint, cardPaint, textBright, textDim, rowBg, rowFg;

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
        canvas.drawText("TECHNIQUE DETECTOR", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText(usingModel ? "ml: MLP / GTSinger" : "pro: H1*-H2* + SPR",
                W - 12f, 16f, textDim);

        // f0 readout, top-left under the title.
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText(voiced ? String.format("f0  %.0f Hz", currentFreq)
                               : "f0  —", 12f, 32f, textDim);

        // One row per technique: name, probability bar, active dot.
        float rowsY0 = 44f;
        float rowsY1 = H - 12f;
        float rowGap = 8f;
        float rowH = (rowsY1 - rowsY0 - rowGap * (N_TECH - 1)) / N_TECH;
        float labelW = 86f;
        float barX0 = 12f + labelW;
        float barX1 = W - 52f;
        for (int i = 0; i < N_TECH; i++) {
            float y0 = rowsY0 + i * (rowH + rowGap);
            float y1 = y0 + rowH;
            float v = probSmooth[i];
            boolean active = v >= ACTIVE_THRESH[i] && voiced;
            int col = TECH_COLOURS[i];

            // Technique name (brightens when active).
            textBright.setColor(active ? col : COLOR_TEXT_DIM)
                    .setTextSize(13f).setTextAlign(0);
            canvas.drawText(TECH[i], 12f, y0 + rowH * 0.42f, textBright);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8f).setTextAlign(0);
            canvas.drawText(active ? TECH_HINT[i] : TECH_EN[i],
                    12f, y0 + rowH * 0.82f, textDim);

            // Probability bar track.
            float barMidY = y0 + rowH * 0.5f;
            float barTop = barMidY - 7f, barBot = barMidY + 7f;
            rowBg.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(barX0, barTop, barX1, barBot, 5f, rowBg);
            rowBg.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(barX0, barTop, barX1, barBot, 5f, rowBg);
            // Fill.
            float fillX = barX0 + v * (barX1 - barX0);
            int fillCol = active ? col : ((col & 0x00FFFFFF) | 0x66000000);
            rowFg.setColor(fillCol).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(barX0, barTop, fillX, barBot, 5f, rowFg);

            // Percentage readout on the right.
            textBright.setColor(active ? col : COLOR_TEXT_DIM)
                    .setTextSize(11f).setTextAlign(2);
            canvas.drawText(String.format("%.0f%%", v * 100f), W - 12f, barMidY + 4f, textBright);
        }
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        rowBg      = c.newPaint();
        rowFg      = c.newPaint();
    }
}
