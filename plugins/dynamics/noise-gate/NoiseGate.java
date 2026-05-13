package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.BlendMode;
import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

// Noise Gate (native port) - opens on rising-edge of openLin, closes
// only after env drops below closeLin AND hold has elapsed. Cleans up
// background hiss between phrases, kills mic bleed.
//
// Custom canvas-mode UI: scrolling envelope plot in dB, threshold line
// + hysteresis band overlaid, gate-state pill (OPEN / CLOSED), and a
// gain-reduction bar. Yellow-on-black "Vocal Monitor" house theme.
public final class NoiseGate
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private float env = 0f;
    private float gain = 0f;
    private int state = 0;
    private int holdSamples = 0;
    private int sampleRate = 44100;
    private float threshold = -45f, hysteresis = 6f, attack = 2f, hold = 30f, release = 80f;

    // History buffers for the scrolling envelope view. Updated at the
    // block boundary in process() with downsampled summaries — visual
    // doesn't need per-sample resolution and writing per-sample would
    // make the ring buffer either huge or wrap many times per frame.
    private static final int HIST_LEN = 384;
    private final float[] histEnv = new float[HIST_LEN];     // env in linear
    private final float[] histGain = new float[HIST_LEN];    // applied gain 0..1
    private final boolean[] histOpen = new boolean[HIST_LEN];
    private int histWrite = 0;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        env = 0f; gain = 0f; state = 0; holdSamples = 0;
        java.util.Arrays.fill(histEnv, 0f);
        java.util.Arrays.fill(histGain, 0f);
        java.util.Arrays.fill(histOpen, false);
        histWrite = 0;
    }

    @Override public String[] parameterNames() {
        return new String[] { "threshold", "hysteresis", "attack", "hold", "release" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "threshold":  return -80f;
            case "hysteresis": return 0f;
            case "attack":     return 0.1f;
            case "hold":       return 0f;
            case "release":    return 5f;
            default:           return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "threshold":  return 0f;
            case "hysteresis": return 24f;
            case "attack":     return 50f;
            case "hold":       return 500f;
            case "release":    return 1000f;
            default:           return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "threshold":  return -45f;
            case "hysteresis": return 6f;
            case "attack":     return 2f;
            case "hold":       return 30f;
            case "release":    return 80f;
            default:           return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "threshold":  return "Thresh (dB)";
            case "hysteresis": return "Hyst (dB)";
            case "attack":     return "Att (ms)";
            case "hold":       return "Hold (ms)";
            case "release":    return "Rel (ms)";
            default:           return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "threshold":  threshold = v; break;
            case "hysteresis": hysteresis = v; break;
            case "attack":     attack = v; break;
            case "hold":       hold = v; break;
            case "release":    release = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float openLin = (float) Math.pow(10.0, threshold / 20.0);
        final float closeLin = (float) Math.pow(10.0, (threshold - hysteresis) / 20.0);
        final float envCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * 0.005));
        final float attCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * attack / 1000.0));
        final float relCoef = 1f - (float) Math.exp(-1.0 / Math.max(1, sampleRate * release / 1000.0));
        final int holdN = (int) Math.floor(hold * sampleRate / 1000f);
        float e = env, g = gain;
        int st = state, hs = holdSamples;
        final int n = input.length;
        // Push one history entry per HIST_STRIDE samples so the ring
        // buffer represents the most recent ~2 seconds of audio at
        // ~5 ms resolution regardless of block size.
        final int histStride = Math.max(1, sampleRate / 200);
        int sampleAcc = 0;
        float envAccum = 0f, gainAccum = 0f; boolean openAccum = false;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float rect = x < 0 ? -x : x;
            e = e + envCoef * (rect - e);
            if (st == 0) {
                if (e > openLin) { st = 1; hs = holdN; }
            } else {
                if (e < closeLin) {
                    if (hs > 0) hs--;
                    else st = 0;
                } else {
                    hs = holdN;
                }
            }
            float target = st;
            float coef = target > g ? attCoef : relCoef;
            g = g + coef * (target - g);
            output[i] = x * g;

            envAccum += e;
            gainAccum += g;
            if (st == 1) openAccum = true;
            sampleAcc++;
            if (sampleAcc >= histStride) {
                histEnv[histWrite] = envAccum / sampleAcc;
                histGain[histWrite] = gainAccum / sampleAcc;
                histOpen[histWrite] = openAccum;
                histWrite = (histWrite + 1) % HIST_LEN;
                envAccum = 0f; gainAccum = 0f; openAccum = false; sampleAcc = 0;
            }
        }
        env = e; gain = g; state = st; holdSamples = hs;
    }

    // ---- Visual ----
    // Yellow-on-black house theme. Yellow accent matches the toggle /
    // EQ curve / "+ Add effect" button in the main app.
    private static final int COLOR_BG          = 0xFF050505;
    private static final int COLOR_GRID        = 0xFF1E1E22;
    private static final int COLOR_GRID_MID    = 0xFF2A2A2E;
    private static final int COLOR_TEXT_DIM    = 0xFF7C7C82;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_YELLOW      = 0xFFF5C842;
    private static final int COLOR_YELLOW_DIM  = 0x40F5C842;
    private static final int COLOR_YELLOW_FILL = 0x55F5C842;
    private static final int COLOR_RED_DIM     = 0xFF4A1E1E;

    private PluginPaint bgPaint, gridPaint, textDim, textBright, yellowLine, yellowFill,
            yellowDim, yellowGlow, pillFill, pillStroke, threshLine, hystFill,
            gainBarBg, gainBarFill, redBg;
    private PluginPath envPath, fillPath;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        final float W = width, H = height;

        // --- 1. Background ---
        bgPaint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, W, H, bgPaint);

        // --- 2. Layout ---
        float pad = 14f;
        float headerH = 28f;
        float gainBarH = 18f;
        float plotX0 = pad + 32f;            // leave room for dB labels
        float plotY0 = pad + headerH + 4f;
        float plotX1 = W - pad;
        float plotY1 = H - pad - gainBarH - 16f;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;
        if (plotW < 20f || plotH < 20f) return;

        // --- 3. Header: name + state pill ---
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(13f).setTextAlign(0);
        canvas.drawText("NOISE GATE", pad, pad + 14, textBright);
        boolean open = state == 1;
        String pillText = open ? "OPEN" : "CLOSED";
        float pillW = 78f, pillH = 22f;
        float pillX = W - pad - pillW, pillY = pad - 2f;
        if (open) {
            pillFill.setColor(COLOR_YELLOW).setStyle(PluginStyle.FILL)
                    .setGlow(COLOR_YELLOW, 8f);
            canvas.drawRoundRect(pillX, pillY, pillX + pillW, pillY + pillH, 11f, pillFill);
            textBright.setColor(0xFF101010).setTextSize(11f).setTextAlign(1);
            canvas.drawText(pillText, pillX + pillW / 2f, pillY + 15f, textBright);
        } else {
            pillStroke.setColor(COLOR_TEXT_DIM).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
            canvas.drawRoundRect(pillX, pillY, pillX + pillW, pillY + pillH, 11f, pillStroke);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(11f).setTextAlign(1);
            canvas.drawText(pillText, pillX + pillW / 2f, pillY + 15f, textDim);
        }

        // --- 4. Plot grid + dB labels (0, -20, -40, -60, -80) ---
        gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
        for (int db = 0; db >= -80; db -= 20) {
            float y = dbToY(db, plotY0, plotY1);
            int c = (db == 0 || db == -80) ? COLOR_GRID_MID : COLOR_GRID;
            gridPaint.setColor(c);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
            canvas.drawText(String.valueOf(db), plotX0 - 4f, y + 3f, textDim);
        }

        // --- 5. Hysteresis band (between threshold and close-threshold) ---
        float thY = dbToY(threshold, plotY0, plotY1);
        float clY = dbToY(threshold - hysteresis, plotY0, plotY1);
        hystFill.setColor(COLOR_YELLOW_DIM).setStyle(PluginStyle.FILL);
        canvas.drawRect(plotX0, thY, plotX1, clY, hystFill);

        // --- 6. Threshold line ---
        threshLine.setColor(COLOR_YELLOW).setStyle(PluginStyle.STROKE).setStrokeWidth(1.5f);
        canvas.drawLine(plotX0, thY, plotX1, thY, threshLine);
        // Threshold label tag
        textBright.setColor(COLOR_YELLOW).setTextSize(10f).setTextAlign(2);
        canvas.drawText(String.format("%.0f dB", threshold), plotX1 - 4f, thY - 4f, textBright);

        // --- 7. Scrolling envelope trace ---
        // Build path from oldest (left) to newest (right) sample.
        envPath.reset(); fillPath.reset();
        float stepX = plotW / (HIST_LEN - 1f);
        boolean started = false;
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histWrite + i) % HIST_LEN;
            float v = histEnv[idx];
            float dB = (float) (20 * Math.log10(Math.max(1e-5f, v)));
            float x = plotX0 + i * stepX;
            float y = dbToY(dB, plotY0, plotY1);
            if (!started) {
                envPath.moveTo(x, y);
                fillPath.moveTo(x, plotY1).lineTo(x, y);
                started = true;
            } else {
                envPath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        if (started) {
            fillPath.lineTo(plotX0 + (HIST_LEN - 1) * stepX, plotY1).close();
            yellowFill.setColor(COLOR_YELLOW_FILL).setStyle(PluginStyle.FILL);
            canvas.drawPath(fillPath, yellowFill);
            yellowLine.setColor(COLOR_YELLOW).setStyle(PluginStyle.STROKE)
                    .setStrokeWidth(1.6f).setGlow(COLOR_YELLOW, 4f);
            canvas.drawPath(envPath, yellowLine);
        }

        // --- 8. Gate "open" segments along the bottom of the plot ---
        // Tiny yellow strip beneath the trace, lit when the gate was
        // open at that time index. Helps correlate envelope vs gating.
        float stripY0 = plotY1 - 4f, stripY1 = plotY1 - 1f;
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histWrite + i) % HIST_LEN;
            if (!histOpen[idx]) continue;
            float x0 = plotX0 + i * stepX;
            float x1 = x0 + stepX + 0.5f;
            yellowDim.setColor(COLOR_YELLOW).setStyle(PluginStyle.FILL);
            canvas.drawRect(x0, stripY0, x1, stripY1, yellowDim);
        }

        // --- 9. Gain reduction bar at the bottom ---
        float barY0 = H - pad - gainBarH;
        float barY1 = H - pad;
        float barX0 = plotX0, barX1 = plotX1;
        redBg.setColor(0xFF111114).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(barX0, barY0, barX1, barY1, 4f, redBg);
        // Inverse-gain (i.e. reduction) drawn as the yellow part.
        float reduction = 1f - gain;
        float fillX1 = barX0 + (barX1 - barX0) * reduction;
        gainBarFill.setColor(reduction > 0.5f ? COLOR_YELLOW : 0xCCF5C842)
                .setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(barX0, barY0, fillX1, barY1, 4f, gainBarFill);
        // GR label
        float grDb = (float) (20 * Math.log10(Math.max(1e-4f, gain)));
        if (grDb < -60f) grDb = -60f;
        String grText = open
                ? "GR  0.0 dB"
                : String.format("GR  %.1f dB", grDb);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(10f).setTextAlign(0);
        canvas.drawText(grText, barX0 + 6f, barY0 - 3f, textDim);
    }

    private float dbToY(float db, float y0, float y1) {
        // 0 dB at top, -80 dB at bottom.
        float t = (-db) / 80f;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return y0 + (y1 - y0) * t;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint     = c.newPaint();
        gridPaint   = c.newPaint();
        textDim     = c.newPaint();
        textBright  = c.newPaint();
        yellowLine  = c.newPaint();
        yellowFill  = c.newPaint();
        yellowDim   = c.newPaint();
        yellowGlow  = c.newPaint();
        pillFill    = c.newPaint();
        pillStroke  = c.newPaint();
        threshLine  = c.newPaint();
        hystFill    = c.newPaint();
        gainBarBg   = c.newPaint();
        gainBarFill = c.newPaint();
        redBg       = c.newPaint();
        envPath     = c.newPath();
        fillPath    = c.newPath();
    }
}
