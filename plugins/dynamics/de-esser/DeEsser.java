package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

// De-esser (native port) — split-band sibilance ducker. Sidechain HP
// feeds an envelope follower that drops gain only on the HF band of
// the main signal — voice body stays intact, only the harsh sssss
// gets tamed.
//
// Canvas-mode UI: scrolling sibilance-band envelope (yellow) with the
// threshold line (yellow horizontal) and the live gain reduction
// drawn as a downward red bar where it exceeds threshold. Frequency
// readout + max-reduction params shown in the header. The split
// frequency is marked along the bottom as a vertical reference.
public final class DeEsser
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private final float[] hpA = new float[2], hpB = new float[2];
    private final float[] lpA = new float[2], lpB = new float[2];
    private final float[] scA = new float[2], scB = new float[2];
    private final float[] scA2 = new float[2], scB2 = new float[2];
    private float env = 0f;
    private int sampleRate = 44100;
    private float frequency = 6500f, threshold = -28f, reduction = 12f, release = 60f;

    private static final int HIST_LEN = 384;
    private final float[] histScDb = new float[HIST_LEN]; // sidechain (HF) envelope dB
    private final float[] histGrDb = new float[HIST_LEN]; // GR dB
    private int histWrite = 0;
    private float lastScDb = -80f;
    private float lastGrDb = 0f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 2; i++) {
            hpA[i] = hpB[i] = lpA[i] = lpB[i] = 0f;
            scA[i] = scB[i] = scA2[i] = scB2[i] = 0f;
        }
        env = 0f;
        java.util.Arrays.fill(histScDb, -80f);
        java.util.Arrays.fill(histGrDb, 0f);
        histWrite = 0;
        lastScDb = -80f; lastGrDb = 0f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "frequency", "threshold", "reduction", "release" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "frequency": return 2000f;
            case "threshold": return -60f;
            case "reduction": return 0f;
            case "release":   return 5f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "frequency": return 12000f;
            case "threshold": return 0f;
            case "reduction": return 24f;
            case "release":   return 400f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "frequency": return 6500f;
            case "threshold": return -28f;
            case "reduction": return 12f;
            case "release":   return 60f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "frequency": return "Freq (Hz)";
            case "threshold": return "Thresh (dB)";
            case "reduction": return "Max GR (dB)";
            case "release":   return "Rel (ms)";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "frequency": frequency = v; break;
            case "threshold": threshold = v; break;
            case "reduction": reduction = v; break;
            case "release":   release = v; break;
        }
    }

    private static float[] bqLP(float fc, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) ((1.0 - c) * 0.5 / a0),
            (float) ((1.0 - c) / a0),
            (float) ((1.0 - c) * 0.5 / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha) / a0)
        };
    }
    private static float[] bqHP(float fc, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float) ((1.0 + c) * 0.5 / a0),
            (float) (-(1.0 + c) / a0),
            (float) ((1.0 + c) * 0.5 / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        final float[] lp = bqLP(frequency, sampleRate);
        final float[] hp = bqHP(frequency, sampleRate);
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * 0.001));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        final float threshLin = (float) Math.pow(10.0, threshold / 20.0);
        final float maxGrLin = (float) Math.pow(10.0, -reduction / 20.0);
        final float ln10 = (float) Math.log(10);
        float e = env;
        final int n = input.length;
        final int histStride = Math.max(1, sampleRate / 200);
        int sampleAcc = 0;
        float maxSc = 0f, maxGrDb = 0f;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float lpOut = lp[0]*x + lp[1]*lpA[0] + lp[2]*lpA[1] - lp[3]*lpB[0] - lp[4]*lpB[1];
            lpA[1] = lpA[0]; lpA[0] = x;
            lpB[1] = lpB[0]; lpB[0] = lpOut;
            float hpOut = hp[0]*x + hp[1]*hpA[0] + hp[2]*hpA[1] - hp[3]*hpB[0] - hp[4]*hpB[1];
            hpA[1] = hpA[0]; hpA[0] = x;
            hpB[1] = hpB[0]; hpB[0] = hpOut;
            float sc1 = hp[0]*x + hp[1]*scA[0] + hp[2]*scA[1] - hp[3]*scB[0] - hp[4]*scB[1];
            scA[1] = scA[0]; scA[0] = x;
            scB[1] = scB[0]; scB[0] = sc1;
            float sc = hp[0]*sc1 + hp[1]*scA2[0] + hp[2]*scA2[1] - hp[3]*scB2[0] - hp[4]*scB2[1];
            scA2[1] = scA2[0]; scA2[0] = sc1;
            scB2[1] = scB2[0]; scB2[0] = sc;
            float rect = sc < 0 ? -sc : sc;
            float coef = rect > e ? attCoef : relCoef;
            e = e + coef * (rect - e);
            float g = 1f;
            if (e > threshLin) {
                g = threshLin / e;
                if (g < maxGrLin) g = maxGrLin;
            }
            output[i] = lpOut + hpOut * g;

            if (e > maxSc) maxSc = e;
            float grDb = g < 0.999999f ? (float) (-20.0 * Math.log(g) / ln10) : 0f;
            if (grDb > maxGrDb) maxGrDb = grDb;
            sampleAcc++;
            if (sampleAcc >= histStride) {
                histScDb[histWrite] = maxSc > 1e-6f
                        ? (float) (20.0 * Math.log(maxSc) / ln10) : -80f;
                histGrDb[histWrite] = maxGrDb;
                histWrite = (histWrite + 1) % HIST_LEN;
                sampleAcc = 0; maxSc = 0f; maxGrDb = 0f;
            }
        }
        env = e;
        lastScDb = e > 1e-6f ? (float) (20.0 * Math.log(e) / ln10) : -80f;
        // lastGrDb: take from the most recent slot we just wrote.
        int last = (histWrite + HIST_LEN - 1) % HIST_LEN;
        lastGrDb = histGrDb[last];
    }

    // ---- Visual ----
    private static final int COLOR_BG          = 0xFF050505;
    private static final int COLOR_GRID        = 0xFF1E1E22;
    private static final int COLOR_GRID_MID    = 0xFF2A2A2E;
    private static final int COLOR_TEXT_DIM    = 0xFF7C7C82;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_YELLOW      = 0xFFF5C842;
    private static final int COLOR_YELLOW_FILL = 0x44F5C842;
    private static final int COLOR_RED         = 0xFFE0606A;

    private PluginPaint bgPaint, gridPaint, textDim, textBright,
            scLine, scFill, threshLine, grOverlay;
    private PluginPath scPath, scFillPath;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        final float W = width, H = height;
        float liveThresh = paramOr(params, "threshold", threshold);
        float liveFreq   = paramOr(params, "frequency", frequency);

        bgPaint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, W, H, bgPaint);

        float pad = 12f;
        float headerH = 22f;
        float plotX0 = pad + 28f;
        float plotY0 = pad + headerH;
        float plotX1 = W - pad;
        float plotY1 = H - pad - 14f;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;
        if (plotW < 60f || plotH < 40f) return;

        // Header.
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("DE-ESSER", pad, pad + 13, textBright);
        textBright.setColor(COLOR_YELLOW).setTextSize(11f).setTextAlign(2);
        canvas.drawText(String.format("%.0f Hz  GR %.1f dB",
                liveFreq, lastGrDb), W - pad, pad + 13, textBright);

        // dB grid.
        for (int db = 0; db >= -60; db -= 20) {
            float t = (-db) / 60f;
            float y = plotY0 + t * plotH;
            gridPaint.setColor(db == 0 || db == -60 ? COLOR_GRID_MID : COLOR_GRID)
                    .setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            canvas.drawText(String.valueOf(db), plotX0 - 3f, y + 3f, textDim);
        }

        // Sidechain (HF) envelope trace.
        scPath.reset(); scFillPath.reset();
        float step = plotW / (HIST_LEN - 1f);
        boolean started = false;
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histWrite + i) % HIST_LEN;
            float db = histScDb[idx];
            if (db < -60f) db = -60f; if (db > 0f) db = 0f;
            float px = plotX0 + i * step;
            float py = plotY0 + ((-db) / 60f) * plotH;
            if (!started) {
                scPath.moveTo(px, py);
                scFillPath.moveTo(px, plotY1).lineTo(px, py);
                started = true;
            } else {
                scPath.lineTo(px, py);
                scFillPath.lineTo(px, py);
            }
        }
        scFillPath.lineTo(plotX0 + (HIST_LEN - 1) * step, plotY1).close();
        scFill.setColor(COLOR_YELLOW_FILL).setStyle(PluginStyle.FILL);
        canvas.drawPath(scFillPath, scFill);
        scLine.setColor(COLOR_YELLOW).setStyle(PluginStyle.STROKE).setStrokeWidth(1.5f);
        canvas.drawPath(scPath, scLine);

        // Threshold line.
        float thY = plotY0 + ((-liveThresh) / 60f) * plotH;
        threshLine.setColor(COLOR_YELLOW).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f)
                .setGlow(COLOR_YELLOW, 4f);
        canvas.drawLine(plotX0, thY, plotX1, thY, threshLine);
        textBright.setColor(COLOR_YELLOW).setTextSize(10f).setTextAlign(2);
        canvas.drawText(String.format("%.0f dB", liveThresh), plotX1 - 4f, thY - 4f, textBright);

        // GR overlay — red bars descending from the threshold line
        // wherever the de-esser was clamping the HF band.
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histWrite + i) % HIST_LEN;
            float gr = histGrDb[idx];
            if (gr <= 0.05f) continue;
            float px = plotX0 + i * step;
            float bh = Math.min(plotH * 0.5f, gr / 24f * plotH * 0.5f);
            grOverlay.setColor(COLOR_RED).setStyle(PluginStyle.FILL);
            canvas.drawRect(px, thY, px + step + 0.5f, thY + bh, grOverlay);
        }

        // Footer label.
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("HF sidechain envelope + GR (last ~2 s)",
                plotX0, plotY1 + 11f, textDim);
    }

    private static float paramOr(Map<String, Float> p, String name, float fallback) {
        if (p == null) return fallback;
        Float v = p.get(name);
        return v != null ? v : fallback;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        gridPaint  = c.newPaint();
        textDim    = c.newPaint();
        textBright = c.newPaint();
        scLine     = c.newPaint();
        scFill     = c.newPaint();
        threshLine = c.newPaint();
        grOverlay  = c.newPaint();
        scPath     = c.newPath();
        scFillPath = c.newPath();
    }
}
