package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Resonance Monitor — measures the singer's-formant cluster ratio.
 *
 *   ratio = energy in 2.5–3.5 kHz / total energy (60 Hz – 8 kHz)
 *
 * 0.0–0.05 → Pressed (no carry; voice sits behind the mix)
 * 0.05–0.15 → Balanced (typical good vocal)
 * 0.15+    → Ringing (operatic / strong belt / engaged twang)
 *
 * Visual: live ring-bar showing the ratio in real time, plus a
 * trailing line of recent values so the user can see how the
 * resonance changes through a phrase.
 */
public final class ResonanceMonitor
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        for (int j = 0; j < 4; j++) { sfState[j] = 0f; totalState[j] = 0f; }
        sfEnv = 0f; totalEnv = 0f;
        java.util.Arrays.fill(ratioHist, 0f);
        histW = 0;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // Bandpass for singer's formant band (2500-3500 Hz).
    private float[] sfCoefs;
    private final float[] sfState = new float[4];
    // Wideband (60-8000 Hz) bandpass for total reference energy.
    private float[] totalCoefs;
    private final float[] totalState = new float[4];
    private float sfEnv = 0f, totalEnv = 0f;
    private float ratio = 0f;

    private static final int HIST_LEN = 256;
    private final float[] ratioHist = new float[HIST_LEN];
    private int histW = 0;

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        if (sfCoefs == null) {
            sfCoefs = bandpass(3000f, 6.0f, sampleRate);   // narrow around 3 kHz
            totalCoefs = bandpass(1500f, 0.5f, sampleRate); // broad (~3 oct)
        }
        float envAtt = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.010));
        float envRel = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.080));
        int histStride = Math.max(1, sampleRate / 100);
        int acc = 0;
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            // Singer's formant band.
            float ysf = sfCoefs[0] * s + sfCoefs[1] * sfState[0] + sfCoefs[2] * sfState[1]
                       - sfCoefs[3] * sfState[2] - sfCoefs[4] * sfState[3];
            sfState[1] = sfState[0]; sfState[0] = s;
            sfState[3] = sfState[2]; sfState[2] = ysf;
            float sfAbs = ysf < 0 ? -ysf : ysf;
            sfEnv += (sfAbs > sfEnv ? envAtt : envRel) * (sfAbs - sfEnv);
            // Total broad reference.
            float yt = totalCoefs[0] * s + totalCoefs[1] * totalState[0] + totalCoefs[2] * totalState[1]
                      - totalCoefs[3] * totalState[2] - totalCoefs[4] * totalState[3];
            totalState[1] = totalState[0]; totalState[0] = s;
            totalState[3] = totalState[2]; totalState[2] = yt;
            float tAbs = yt < 0 ? -yt : yt;
            totalEnv += (tAbs > totalEnv ? envAtt : envRel) * (tAbs - totalEnv);
            acc++;
            if (acc >= histStride) {
                acc = 0;
                ratio = totalEnv > 1e-6f ? sfEnv / totalEnv : 0f;
                if (ratio > 1f) ratio = 1f;
                ratioHist[histW] = ratio;
                histW = (histW + 1) % HIST_LEN;
            }
        }
    }

    private static float[] bandpass(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float)(alpha / a0), 0f, (float)(-alpha / a0),
            (float)(-2.0 * cs / a0), (float)((1.0 - alpha) / a0)
        };
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_PRESSED     = 0xFFE0606A;
    private static final int COLOR_BALANCED    = 0xFF6FE07A;
    private static final int COLOR_RINGING     = 0xFFF5C842;
    private static final int COLOR_GRID        = 0xFF202125;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, ratioBar, ratioBg, ratioLine, verdictPaint;
    private PluginPath ratioPath;

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
        canvas.drawText("RESONANCE MONITOR", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText("singer's formant cluster (~3 kHz)", W - 12f, 16f, textDim);

        int verdictColor = ratio < 0.05f ? COLOR_PRESSED
                          : ratio < 0.15f ? COLOR_BALANCED : COLOR_RINGING;
        String verdict = ratio < 0.05f ? "PRESSED"
                          : ratio < 0.15f ? "BALANCED" : "RINGING";

        // Layout: big ring bar on the left, scrolling history on the right.
        float pad = 12f;
        float ringR = Math.min(H * 0.32f, 70f);
        float ringCx = pad + ringR + 8f;
        float ringCy = H * 0.55f;
        // Background ring.
        ratioBg.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(8f);
        canvas.drawCircle(ringCx, ringCy, ringR, ratioBg);
        // Filled arc — quick approximation via dots.
        ratioBar.setColor(verdictColor).setStyle(PluginStyle.STROKE).setStrokeWidth(8f);
        int segs = Math.max(2, (int) Math.ceil(ratio * 200));
        if (segs > 360) segs = 360;
        float a0 = (float) Math.PI * 0.75f;
        float a1 = a0 + (float)(2.0 * Math.PI * 0.7f) * Math.min(1f, ratio / 0.3f);
        float pxPrev = ringCx + ringR * (float) Math.cos(a0);
        float pyPrev = ringCy + ringR * (float) Math.sin(a0);
        for (int i = 1; i <= segs; i++) {
            float t = i / (float) segs;
            float a = a0 + (a1 - a0) * t;
            float px = ringCx + ringR * (float) Math.cos(a);
            float py = ringCy + ringR * (float) Math.sin(a);
            canvas.drawLine(pxPrev, pyPrev, px, py, ratioBar);
            pxPrev = px; pyPrev = py;
        }
        // Centre value.
        textBright.setColor(verdictColor).setTextSize(18f).setTextAlign(1);
        canvas.drawText(String.format("%.0f%%", ratio * 100), ringCx, ringCy + 4f, textBright);
        // Verdict.
        verdictPaint.setColor(verdictColor).setTextSize(11f).setTextAlign(1);
        canvas.drawText(verdict, ringCx, ringCy + ringR + 18f, verdictPaint);

        // Scrolling history plot on the right.
        float plotX0 = ringCx + ringR + 24f;
        float plotX1 = W - pad;
        float plotY0 = pad + 24f;
        float plotY1 = H - pad - 18f;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0, plotY0, plotX1, plotY1, 6f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0, plotY0, plotX1, plotY1, 6f, cardPaint);
        // Reference zones.
        gridPaint.setColor(0x33E0606A).setStyle(PluginStyle.FILL);
        canvas.drawRect(plotX0, plotY1 - 0.05f / 0.30f * plotH, plotX1, plotY1, gridPaint);
        gridPaint.setColor(0x336FE07A);
        canvas.drawRect(plotX0, plotY1 - 0.15f / 0.30f * plotH,
                plotX1, plotY1 - 0.05f / 0.30f * plotH, gridPaint);
        gridPaint.setColor(0x33F5C842);
        canvas.drawRect(plotX0, plotY0, plotX1, plotY1 - 0.15f / 0.30f * plotH, gridPaint);
        // Ratio contour.
        ratioPath.reset();
        float step = plotW / (HIST_LEN - 1f);
        boolean started = false;
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histW + i) % HIST_LEN;
            float v = ratioHist[idx];
            if (v > 0.30f) v = 0.30f;
            float px = plotX0 + i * step;
            float py = plotY1 - (v / 0.30f) * plotH;
            if (!started) { ratioPath.moveTo(px, py); started = true; }
            else ratioPath.lineTo(px, py);
        }
        ratioLine.setColor(0xFFE6E6EA).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
        canvas.drawPath(ratioPath, ratioLine);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
        canvas.drawText("ratio history", plotX0 + 4f, plotY0 + 12f, textDim);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint     = c.newPaint();
        cardPaint   = c.newPaint();
        textBright  = c.newPaint();
        textDim     = c.newPaint();
        gridPaint   = c.newPaint();
        ratioBar    = c.newPaint();
        ratioBg     = c.newPaint();
        ratioLine   = c.newPaint();
        verdictPaint = c.newPaint();
        ratioPath   = c.newPath();
    }
}
