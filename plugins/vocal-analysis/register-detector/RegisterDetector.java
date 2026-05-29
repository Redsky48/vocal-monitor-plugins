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
 * Register Detector — pro-grade vocal register classifier.
 *
 * Distinguishes 5 vocal registers (Roubeau's M1/M2 + Sundberg's belt
 * + the head-falsetto continuum) from a 5-feature acoustic vector pulled
 * straight from the voice-science literature: f0 (YIN), formant-corrected
 * H1*-H2* (Iseli-Alwan 2007), H1-A3 spectral tilt, Harmonic Richness
 * Factor (Childers), and Singer's Power Ratio (Sundberg). All extraction
 * lives in {@link RegisterFeatures} so it is shared byte-for-byte with the
 * offline trainer.
 *
 * Classification has two modes, chosen at runtime:
 *
 *  - **ML (preferred)** — if the host can hand us an ONNX model declared
 *    as the {@code register.onnx} asset (via {@link PluginHost#loadModel}),
 *    the 5-feature vector is fed to a small MLP trained on VocalSet and
 *    the softmax over its 5 logits drives the bars. This learns feature
 *    interactions and real decision boundaries instead of assuming
 *    feature independence.
 *  - **Heuristic fallback** — when no host / no model (older host, model
 *    not shipped, load failed), we fall back to the literature-tuned
 *    multinomial product of per-feature gaussians. Always available, so
 *    the plugin is fully functional with or without the model.
 *
 * Honest scope: even the ML path is audio-only and trained on weak
 * (F0-tessitura + technique) labels, not EGG ground truth — it is a
 * better-calibrated estimator, not a clinical instrument.
 */
public final class RegisterDetector
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    private final RegisterFeatures feat = new RegisterFeatures();

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        ringW = 0;
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

    // ── ML inference (optional) ──
    // Set once by the host before the first render(); we lazily ask it
    // for the model on the first analysed frame so a missing model just
    // routes to the heuristic without a per-frame retry.
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

    // ── Registers ──
    private static final int N_REG = 5;
    private static final String[] REG = { "CHEST", "MIX", "HEAD", "FALSETTO", "BELT" };
    private static final int[] REG_COLOURS = {
        0xFFE34855, 0xFFEE8A2C, 0xFF5BD9E0, 0xFFA060E0, 0xFFF5C842
    };

    // Per-register (μ, σ) tables for the heuristic fallback. Values tuned
    // from Henrich 2005 (H1-H2 / OQ), Sundberg 1990 / 2001 (SPR / chest /
    // belt), Childers 1991 (HRF), Stevens 1998 (H1-A3).
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

    private final float[] scores      = new float[N_REG];
    private final float[] scoreSmooth = new float[N_REG];
    private int bestIdx = -1;

    // Latest acoustic measurements (mirrored from feat for the readout).
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
        // De-ring the latest FFT_N samples into a time-ordered frame.
        for (int i = 0; i < FFT_N; i++) {
            frame[i] = audioRing[(ringW + i) % FFT_N];
        }
        if (!feat.analyse(frame, sampleRate)) {
            // Unvoiced / silent — decay the bars and clear the label.
            for (int i = 0; i < N_REG; i++) scoreSmooth[i] *= 0.85f;
            bestIdx = -1;
            return;
        }
        // Mirror measurements for the readout panel.
        currentFreq = feat.f0;
        h1h2 = feat.h1h2;
        h1a3 = feat.h1a3;
        hrf  = feat.hrf;
        spr  = feat.spr;
        oq   = feat.oq;

        // Lazily acquire the model on the first voiced frame.
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
            } catch (Throwable t) {
                // One bad inference shouldn't kill the panel — drop to
                // the heuristic for this frame.
            }
        }
        if (!scored) {
            usingModel = false;
            scoreHeuristic();
        }

        // Normalise to the strongest register and smooth for stability.
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

    // Heuristic fallback: per-register product of per-feature gaussians.
    private void scoreHeuristic() {
        for (int i = 0; i < N_REG; i++) {
            float e1 = gauss(currentFreq, MU_F0[i],   SG_F0[i]);
            float e2 = gauss(h1h2,         MU_H1H2[i], SG_H1H2[i]);
            float e3 = gauss(h1a3,         MU_H1A3[i], SG_H1A3[i]);
            float e4 = gauss(hrf,          MU_HRF[i],  SG_HRF[i]);
            float e5 = gauss(spr,          MU_SPR[i],  SG_SPR[i]);
            scores[i] = e1 * e2 * e3 * e4 * e5;
        }
    }

    // Numerically-stable softmax of the first n logits of src into dst.
    private static void softmaxInto(float[] src, float[] dst, int n) {
        float m = src[0];
        for (int i = 1; i < n; i++) if (src[i] > m) m = src[i];
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            float e = (float) Math.exp(src[i] - m);
            dst[i] = e;
            sum += e;
        }
        if (sum > 1e-12f) for (int i = 0; i < n; i++) dst[i] /= sum;
    }

    private static float gauss(float x, float c, float s) {
        float d = (x - c) / s;
        return (float) Math.exp(-0.5 * d * d);
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
        canvas.drawText(usingModel ? "ml: MLP on VocalSet" : "pro: H1*-H2* + HRF + SPR",
                W - 12f, 16f, textDim);

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
                "H1*-H2*", String.format("%+.1f dB", h1h2));
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
