package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

// Limiter (native port) — brickwall peak limiter with 8 ms lookahead.
//
// Canvas-mode UI: scrolling input-peak history with the ceiling line
// drawn yellow + gain-reduction overlay where the limiter is
// clamping; big bottom GR readout with peak-hold. Yellow-on-black
// house theme, matches Compressor's layout style.
public final class Limiter
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private float[] buf;
    private int bufLen;
    private int idx = 0;
    private float env = 0f;
    private float gain = 1f;
    private float ceiling = -0.3f, release = 60f;
    private int sampleRate = 44100;

    private static final int HIST_LEN = 384;
    private final float[] histInDb = new float[HIST_LEN];
    private final float[] histGrDb = new float[HIST_LEN];
    private int histWrite = 0;
    private float lastGrDb = 0f;       // 0 = no reduction, positive = clamping
    private float lastInDb = -80f;
    private float peakHoldGr = 0f;
    private long peakHoldStampMs = 0L;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        bufLen = (int) Math.floor(sr * 0.008);
        buf = new float[bufLen];
        idx = 0;
        env = 0f;
        gain = 1f;
        java.util.Arrays.fill(histInDb, -80f);
        java.util.Arrays.fill(histGrDb, 0f);
        histWrite = 0;
        lastGrDb = 0f; lastInDb = -80f;
        peakHoldGr = 0f;
    }

    @Override public String[] parameterNames() { return new String[] { "ceiling", "release" }; }
    @Override public float parameterMin(String n) {
        if ("ceiling".equals(n)) return -12f;
        return 5f;
    }
    @Override public float parameterMax(String n) {
        if ("ceiling".equals(n)) return 0f;
        return 500f;
    }
    @Override public float parameterDefault(String n) {
        return "ceiling".equals(n) ? -0.3f : 60f;
    }
    @Override public String parameterLabel(String n) {
        return "ceiling".equals(n) ? "Ceil (dB)" : "Rel (ms)";
    }
    @Override public void setParameter(String n, float v) {
        if ("ceiling".equals(n)) ceiling = v;
        else if ("release".equals(n)) release = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float ceilLin = (float) Math.pow(10.0, ceiling / 20.0);
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, bufLen * 0.25));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        final float[] b = buf;
        final int bL = bufLen;
        final float ln10 = (float) Math.log(10);
        int ix = idx;
        float e = env, g = gain;
        final int n = input.length;
        final int histStride = Math.max(1, sampleRate / 200);
        int sampleAcc = 0;
        float maxEnv = 0f, maxGrDb = 0f;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float played = b[ix];
            b[ix] = x;
            ix++; if (ix >= bL) ix = 0;
            float rect = x < 0 ? -x : x;
            float coef = rect > e ? attCoef : relCoef;
            e = e + coef * (rect - e);
            float target = e > ceilLin ? ceilLin / e : 1f;
            float gCoef = target < g ? attCoef : relCoef;
            g = g + gCoef * (target - g);
            output[i] = played * g;

            if (e > maxEnv) maxEnv = e;
            // Convert gain (≤1) to GR dB (≥0).
            float grDb = g < 0.999999f ? (float) (-20.0 * Math.log(g) / ln10) : 0f;
            if (grDb > maxGrDb) maxGrDb = grDb;
            sampleAcc++;
            if (sampleAcc >= histStride) {
                histInDb[histWrite] = maxEnv > 1e-6f
                        ? (float) (20.0 * Math.log(maxEnv) / ln10) : -80f;
                histGrDb[histWrite] = maxGrDb;
                histWrite = (histWrite + 1) % HIST_LEN;
                sampleAcc = 0; maxEnv = 0f; maxGrDb = 0f;
            }
        }
        idx = ix; env = e; gain = g;
        lastInDb = e > 1e-6f ? (float) (20.0 * Math.log(e) / ln10) : -80f;
        lastGrDb = g < 0.999999f ? (float) (-20.0 * Math.log(g) / ln10) : 0f;
    }

    // ---- Visual ----
    private static final int COLOR_BG          = 0xFF050505;
    private static final int COLOR_GRID        = 0xFF1E1E22;
    private static final int COLOR_GRID_MID    = 0xFF2A2A2E;
    private static final int COLOR_TEXT_DIM    = 0xFF7C7C82;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_YELLOW      = 0xFFF5C842;
    private static final int COLOR_YELLOW_DIM  = 0x55F5C842;
    private static final int COLOR_YELLOW_FAINT= 0x33F5C842;
    private static final int COLOR_RED         = 0xFFE0606A;
    private static final int COLOR_BAR_BG      = 0xFF111114;

    private PluginPaint bgPaint, gridPaint, textDim, textBright,
            histLine, histFill, ceilingLine, grOverlay,
            grBarBg, grBarFill, peakHoldPip;
    private PluginPath histPath, histFillPath;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        final float W = width, H = height;
        float liveCeiling = paramOr(params, "ceiling", ceiling);

        // Update peak-hold (drops back over 1.5 s).
        if (lastGrDb > peakHoldGr) {
            peakHoldGr = lastGrDb;
            peakHoldStampMs = timeMs;
        } else if (timeMs - peakHoldStampMs > 1500L) {
            peakHoldGr = Math.max(lastGrDb, peakHoldGr - 0.3f);
        }

        bgPaint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, W, H, bgPaint);

        // Layout.
        float pad = 12f;
        float headerH = 22f;
        float grBarH = 22f;
        float plotX0 = pad + 28f;
        float plotY0 = pad + headerH;
        float plotX1 = W - pad;
        float plotY1 = H - pad - grBarH - 14f;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;
        if (plotW < 60f || plotH < 40f) return;

        // Header.
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("LIMITER", pad, pad + 13, textBright);
        textBright.setColor(COLOR_YELLOW).setTextSize(11f).setTextAlign(2);
        canvas.drawText(String.format("GR  %.1f dB (peak %.1f)",
                lastGrDb, peakHoldGr), W - pad, pad + 13, textBright);

        // Grid (0, -20, -40, -60).
        for (int db = 0; db >= -60; db -= 20) {
            float t = (-db) / 60f;
            float y = plotY0 + t * plotH;
            gridPaint.setColor(db == 0 || db == -60 ? COLOR_GRID_MID : COLOR_GRID)
                    .setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
            canvas.drawText(String.valueOf(db), plotX0 - 3f, y + 3f, textDim);
        }

        // Ceiling line (yellow horizontal).
        float ceY = plotY0 + ((-liveCeiling) / 60f) * plotH;
        ceilingLine.setColor(COLOR_YELLOW).setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f)
                .setGlow(COLOR_YELLOW, 5f);
        canvas.drawLine(plotX0, ceY, plotX1, ceY, ceilingLine);
        textBright.setColor(COLOR_YELLOW).setTextSize(10f).setTextAlign(2);
        canvas.drawText(String.format("ceiling %.1f dB", liveCeiling),
                plotX1 - 4f, ceY - 4f, textBright);

        // Input envelope trace.
        histPath.reset(); histFillPath.reset();
        float step = plotW / (HIST_LEN - 1f);
        boolean started = false;
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histWrite + i) % HIST_LEN;
            float db = histInDb[idx];
            if (db < -60f) db = -60f; if (db > 0f) db = 0f;
            float px = plotX0 + i * step;
            float py = plotY0 + ((-db) / 60f) * plotH;
            if (!started) {
                histPath.moveTo(px, py);
                histFillPath.moveTo(px, plotY1).lineTo(px, py);
                started = true;
            } else {
                histPath.lineTo(px, py);
                histFillPath.lineTo(px, py);
            }
        }
        histFillPath.lineTo(plotX0 + (HIST_LEN - 1) * step, plotY1).close();
        histFill.setColor(COLOR_YELLOW_FAINT).setStyle(PluginStyle.FILL);
        canvas.drawPath(histFillPath, histFill);
        histLine.setColor(COLOR_YELLOW).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
        canvas.drawPath(histPath, histLine);

        // GR overlay where clamping happened: red-tinted bar from the
        // ceiling line down, scaled by the GR amount.
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histWrite + i) % HIST_LEN;
            float gr = histGrDb[idx];
            if (gr <= 0.05f) continue;
            float px = plotX0 + i * step;
            float bh = Math.min(plotH * 0.5f, gr / 12f * plotH * 0.5f);
            grOverlay.setColor(COLOR_RED).setStyle(PluginStyle.FILL);
            canvas.drawRect(px, ceY, px + step + 0.5f, ceY + bh, grOverlay);
        }

        // Bottom GR bar (right-to-left fill).
        float barY0 = H - pad - grBarH;
        float barY1 = H - pad;
        float barX0 = plotX0;
        float barX1 = plotX1;
        grBarBg.setColor(COLOR_BAR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(barX0, barY0, barX1, barY1, 4f, grBarBg);
        float grNorm = Math.min(1f, lastGrDb / 12f);
        if (grNorm > 0.01f) {
            float fillX0 = barX1 - (barX1 - barX0) * grNorm;
            grBarFill.setColor(grNorm > 0.5f ? COLOR_RED : COLOR_YELLOW)
                    .setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(fillX0, barY0, barX1, barY1, 4f, grBarFill);
        }
        // Peak-hold pip.
        if (peakHoldGr > 0.05f) {
            float pkNorm = Math.min(1f, peakHoldGr / 12f);
            float pkX = barX1 - (barX1 - barX0) * pkNorm;
            peakHoldPip.setColor(0xFFFFE680).setStyle(PluginStyle.FILL);
            canvas.drawRect(pkX - 1.2f, barY0 + 1f, pkX + 1.2f, barY1 - 1f, peakHoldPip);
        }
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("GR (0 / 12 dB)", barX0 + 6f, barY0 - 2f, textDim);
    }

    private static float paramOr(Map<String, Float> p, String name, float fallback) {
        if (p == null) return fallback;
        Float v = p.get(name);
        return v != null ? v : fallback;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint      = c.newPaint();
        gridPaint    = c.newPaint();
        textDim      = c.newPaint();
        textBright   = c.newPaint();
        histLine     = c.newPaint();
        histFill     = c.newPaint();
        ceilingLine  = c.newPaint();
        grOverlay    = c.newPaint();
        grBarBg      = c.newPaint();
        grBarFill    = c.newPaint();
        peakHoldPip  = c.newPaint();
        histPath     = c.newPath();
        histFillPath = c.newPath();
    }
}
