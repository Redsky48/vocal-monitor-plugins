package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

// Compressor (native port) — feedforward peak compressor with split
// attack/release detector, soft-knee gain computer in the dB domain,
// and makeup gain.
//
// Canvas-mode UI: live transfer curve on the left (input-dB →
// output-dB) with the current operating point as a yellow dot;
// scrolling envelope + gain-reduction history on the right;
// gain-reduction bar across the bottom. Yellow-on-black house theme.
public final class Compressor
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private float env = 0f;
    private float gain = 1f;
    private int sampleRate = 44100;
    private float threshold = -18f, ratio = 4f, attack = 8f, release = 120f,
                  knee = 6f, makeup = 0f;

    // History rings for the scrolling envelope view. Block-aggregated
    // (one entry per ~5 ms of audio) so we keep ~2 s of meaningful
    // history without blowing the buffer at high sample rates.
    private static final int HIST_LEN = 384;
    private final float[] histInDb = new float[HIST_LEN];
    private final float[] histGrDb = new float[HIST_LEN];
    private int histWrite = 0;
    private float lastGrDb = 0f;
    private float lastInDb = -80f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        env = 0f; gain = 1f;
        java.util.Arrays.fill(histInDb, -80f);
        java.util.Arrays.fill(histGrDb, 0f);
        histWrite = 0;
        lastGrDb = 0f; lastInDb = -80f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "threshold", "ratio", "attack", "release", "knee", "makeup" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "threshold": return -60f;
            case "ratio":     return 1f;
            case "attack":    return 0.1f;
            case "release":   return 5f;
            case "knee":      return 0f;
            case "makeup":    return 0f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "threshold": return 0f;
            case "ratio":     return 20f;
            case "attack":    return 100f;
            case "release":   return 1000f;
            case "knee":      return 24f;
            case "makeup":    return 24f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "threshold": return -18f;
            case "ratio":     return 4f;
            case "attack":    return 8f;
            case "release":   return 120f;
            case "knee":      return 6f;
            case "makeup":    return 0f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "threshold": return "Thresh (dB)";
            case "ratio":     return "Ratio";
            case "attack":    return "Att (ms)";
            case "release":   return "Rel (ms)";
            case "knee":      return "Knee (dB)";
            case "makeup":    return "Makeup (dB)";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "threshold": threshold = v; break;
            case "ratio":     ratio = v; break;
            case "attack":    attack = v; break;
            case "release":   release = v; break;
            case "knee":      knee = v; break;
            case "makeup":    makeup = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float thresh = threshold;
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * attack / 1000.0));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        final float makeupLin = (float) Math.pow(10.0, makeup / 20.0);
        final float halfKnee = knee * 0.5f;
        final float invRatio = 1f / ratio;
        final float kneeLocal = knee;
        final float ln10 = (float) Math.log(10);
        float e = env;
        final int n = input.length;
        // Block-summary state for the history ring (downsample to one
        // entry per ~5 ms so the buffer keeps ~2 s of resolution).
        final int histStride = Math.max(1, sampleRate / 200);
        int sampleAcc = 0;
        float maxEnv = 0f;
        float sumGr = 0f;
        float runningGr = lastGrDb;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float rect = x < 0 ? -x : x;
            float coef = rect > e ? attCoef : relCoef;
            e = e + coef * (rect - e);

            float envDb = e > 1e-6f ? (float) (20.0 * Math.log(e) / ln10) : -120f;

            float gr = 0f;
            float diff = envDb - thresh;
            if (diff > -halfKnee) {
                if (diff < halfKnee && kneeLocal > 0f) {
                    float t = (diff + halfKnee) / kneeLocal;
                    gr = (1f - invRatio) * t * t * halfKnee;
                } else {
                    gr = (envDb - thresh) * (1f - invRatio);
                }
            }
            float targetGain = (float) Math.pow(10.0, -gr / 20.0);
            output[i] = x * targetGain * makeupLin;

            if (e > maxEnv) maxEnv = e;
            sumGr += gr;
            runningGr = gr;
            sampleAcc++;
            if (sampleAcc >= histStride) {
                float maxDb = maxEnv > 1e-6f ? (float) (20.0 * Math.log(maxEnv) / ln10) : -80f;
                histInDb[histWrite] = maxDb;
                histGrDb[histWrite] = sumGr / sampleAcc;
                histWrite = (histWrite + 1) % HIST_LEN;
                sampleAcc = 0; maxEnv = 0f; sumGr = 0f;
            }
        }
        env = e;
        lastGrDb = runningGr;
        lastInDb = e > 1e-6f ? (float) (20.0 * Math.log(e) / ln10) : -80f;
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
    private static final int COLOR_UNITY       = 0xFF3A3A40;
    private static final int COLOR_RED_DIM     = 0xFF111114;

    private PluginPaint bgPaint, gridPaint, textDim, textBright,
            curveLine, unityLine, dotFill, histLine, histFill, threshLine,
            grBarBg, grBarFill;
    private PluginPath curvePath, histPath, histFillPath;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        final float W = width, H = height;

        // Pull live params (host is the source of truth — values may
        // have moved between the last process() call and this frame).
        float liveThresh = paramOr(params, "threshold", threshold);
        float liveRatio  = paramOr(params, "ratio",     ratio);
        float liveKnee   = paramOr(params, "knee",      knee);

        // Background.
        bgPaint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, W, H, bgPaint);

        // Layout: square-ish transfer curve on the left, scrolling
        // history on the right, GR bar across the bottom.
        float pad = 12f;
        float headerH = 22f;
        float grBarH = 16f;
        float bottomLabelH = 14f;

        float topY = pad + headerH;
        float botY = H - pad - grBarH - 4f;
        float curveW = Math.min((H - topY - bottomLabelH - 8f), (W - pad * 3) * 0.42f);
        if (curveW < 80f) curveW = (W - pad * 3) * 0.42f;
        float curveX0 = pad + 28f;
        float curveY0 = topY;
        float curveX1 = curveX0 + curveW;
        float curveY1 = curveY0 + curveW;

        float histX0 = curveX1 + pad + 28f;
        float histY0 = topY;
        float histX1 = W - pad;
        float histY1 = botY - 12f;
        if (histX1 - histX0 < 80f) histX1 = histX0 + 80f;

        // Header
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("COMPRESSOR", pad, pad + 13, textBright);
        textBright.setColor(COLOR_YELLOW).setTextSize(11f).setTextAlign(2);
        canvas.drawText(String.format("GR  %.1f dB", -lastGrDb),
                W - pad, pad + 13, textBright);

        // --- Transfer curve ---
        drawTransferCurve(canvas, curveX0, curveY0, curveX1, curveY1,
                liveThresh, liveRatio, liveKnee);

        // --- Scrolling history ---
        drawHistory(canvas, histX0, histY0, histX1, histY1, liveThresh);

        // --- GR bar at the bottom ---
        float barY0 = H - pad - grBarH;
        float barY1 = H - pad;
        float barX0 = curveX0;
        float barX1 = histX1;
        grBarBg.setColor(COLOR_RED_DIM).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(barX0, barY0, barX1, barY1, 4f, grBarBg);
        // GR meter is read right-to-left: a 0 dB GR shows nothing,
        // more reduction fills from the right edge leftward, the
        // visual analogue of "ducking".
        float grNorm = Math.min(1f, (-lastGrDb) / 24f);
        float fillX0 = barX1 - (barX1 - barX0) * grNorm;
        if (grNorm > 0.01f) {
            grBarFill.setColor(grNorm > 0.5f ? COLOR_YELLOW : 0xCCF5C842)
                    .setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(fillX0, barY0, barX1, barY1, 4f, grBarFill);
        }
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(10f).setTextAlign(0);
        canvas.drawText("GR meter", barX0 + 6f, barY0 - 2f, textDim);
    }

    private void drawTransferCurve(PluginCanvas canvas,
            float x0, float y0, float x1, float y1,
            float liveThresh, float liveRatio, float liveKnee) {
        float w = x1 - x0;
        float h = y1 - y0;
        // Background box (subtle).
        gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
        canvas.drawRect(x0, y0, x1, y1, gridPaint);

        // Grid lines every 12 dB.
        for (int db = 0; db >= -60; db -= 12) {
            float t = (-db) / 60f;
            float yy = y0 + t * h;
            float xx = x0 + (1f - t) * w;
            gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
            canvas.drawLine(x0, yy, x1, yy, gridPaint);
            canvas.drawLine(xx, y0, xx, y1, gridPaint);
            if (db % 24 == 0) {
                textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
                canvas.drawText(String.valueOf(db), x0 - 3f, yy + 3f, textDim);
            }
        }
        // 1:1 unity diagonal (for reference).
        unityLine.setColor(COLOR_UNITY).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
        canvas.drawLine(x0, y1, x1, y0, unityLine);

        // Build the compressor curve sample-by-sample in dB space.
        curvePath.reset();
        float invR = 1f / liveRatio;
        float halfK = liveKnee * 0.5f;
        boolean started = false;
        for (int p = 0; p <= 60; p++) {
            float inDb = -60f + p;
            float diff = inDb - liveThresh;
            float gr = 0f;
            if (diff > -halfK) {
                if (diff < halfK && liveKnee > 0f) {
                    float t = (diff + halfK) / liveKnee;
                    gr = (1f - invR) * t * t * halfK;
                } else {
                    gr = diff * (1f - invR);
                }
            }
            float outDb = inDb - gr;
            // Map to canvas.
            float cx = x0 + ((inDb + 60f) / 60f) * w;
            float cy = y0 + ((-outDb) / 60f) * h;
            if (!started) { curvePath.moveTo(cx, cy); started = true; }
            else curvePath.lineTo(cx, cy);
        }
        curveLine.setColor(COLOR_YELLOW).setStyle(PluginStyle.STROKE)
                .setStrokeWidth(2f).setGlow(COLOR_YELLOW, 6f);
        canvas.drawPath(curvePath, curveLine);

        // Threshold marker — vertical line on the input axis.
        float thX = x0 + ((liveThresh + 60f) / 60f) * w;
        threshLine.setColor(COLOR_YELLOW_DIM).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
        canvas.drawLine(thX, y0, thX, y1, threshLine);

        // Current operating point as a glowing dot.
        if (lastInDb > -65f) {
            float opX = x0 + ((Math.max(-60f, Math.min(0f, lastInDb)) + 60f) / 60f) * w;
            float opOutDb = lastInDb - lastGrDb;  // gr is positive when reducing
            float opY = y0 + ((-Math.max(-60f, Math.min(0f, opOutDb))) / 60f) * h;
            dotFill.setColor(COLOR_YELLOW).setStyle(PluginStyle.FILL)
                    .setGlow(COLOR_YELLOW, 12f);
            canvas.drawCircle(opX, opY, 4.5f, dotFill);
        }

        // Axis label.
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(1);
        canvas.drawText("INPUT (dB)", (x0 + x1) * 0.5f, y1 + 11f, textDim);
    }

    private void drawHistory(PluginCanvas canvas,
            float x0, float y0, float x1, float y1, float liveThresh) {
        float w = x1 - x0, h = y1 - y0;
        // Box background.
        gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
        canvas.drawRect(x0, y0, x1, y1, gridPaint);
        // Grid: 0, -20, -40, -60.
        for (int db = 0; db >= -60; db -= 20) {
            float t = (-db) / 60f;
            float yy = y0 + t * h;
            gridPaint.setColor(db == 0 || db == -60 ? COLOR_GRID_MID : COLOR_GRID);
            canvas.drawLine(x0, yy, x1, yy, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            canvas.drawText(String.valueOf(db), x0 - 3f, yy + 3f, textDim);
        }
        // Threshold line.
        float thY = y0 + ((-liveThresh) / 60f) * h;
        threshLine.setColor(COLOR_YELLOW).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
        canvas.drawLine(x0, thY, x1, thY, threshLine);

        // Input envelope trace (yellow line + translucent fill below).
        histPath.reset();
        histFillPath.reset();
        float step = w / (HIST_LEN - 1f);
        boolean started = false;
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histWrite + i) % HIST_LEN;
            float db = histInDb[idx];
            if (db < -60f) db = -60f; if (db > 0f) db = 0f;
            float px = x0 + i * step;
            float py = y0 + ((-db) / 60f) * h;
            if (!started) {
                histPath.moveTo(px, py);
                histFillPath.moveTo(px, y1).lineTo(px, py);
                started = true;
            } else {
                histPath.lineTo(px, py);
                histFillPath.lineTo(px, py);
            }
        }
        histFillPath.lineTo(x0 + (HIST_LEN - 1) * step, y1).close();
        histFill.setColor(COLOR_YELLOW_FAINT).setStyle(PluginStyle.FILL);
        canvas.drawPath(histFillPath, histFill);
        histLine.setColor(COLOR_YELLOW).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
        canvas.drawPath(histPath, histLine);

        // GR overlay as descending bars along the top edge, one per
        // history slot, height proportional to the GR at that moment.
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histWrite + i) % HIST_LEN;
            float gr = histGrDb[idx];
            if (gr <= 0.1f) continue;
            float px = x0 + i * step;
            float bh = Math.min(h * 0.35f, gr / 24f * h * 0.35f);
            dotFill.setColor(COLOR_YELLOW_DIM).setStyle(PluginStyle.FILL);
            canvas.drawRect(px, y0, px + step + 0.5f, y0 + bh, dotFill);
        }

        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("INPUT + GR (last ~2 s)", x0 + 4f, y1 + 11f, textDim);
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
        curveLine    = c.newPaint();
        unityLine    = c.newPaint();
        dotFill      = c.newPaint();
        histLine     = c.newPaint();
        histFill     = c.newPaint();
        threshLine   = c.newPaint();
        grBarBg      = c.newPaint();
        grBarFill    = c.newPaint();
        curvePath    = c.newPath();
        histPath     = c.newPath();
        histFillPath = c.newPath();
    }
}
