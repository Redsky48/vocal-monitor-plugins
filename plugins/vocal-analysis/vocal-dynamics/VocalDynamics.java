package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vocal Dynamics — peak / RMS / LUFS-S (short term, 3 s) meters,
 * plus a 4-second scrolling envelope and a dynamic-range readout.
 *
 * LUFS implementation: K-weighting filter chain (high-shelf @ 1.5
 * kHz +4 dB then high-pass @ 38 Hz, BS.1770), then mean-square over
 * 3-second sliding window, then -0.691 dB calibration.
 *
 * Dynamic range = (max peak in last 4 s) - (min RMS in last 4 s).
 */
public final class VocalDynamics
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(envHist, -80f);
        java.util.Arrays.fill(lufsRing, 0f);
        for (int j = 0; j < 4; j++) { hsState[j] = 0f; hpState[j] = 0f; }
        histW = 0; lufsRingW = 0;
        peakDb = rmsDb = lufsDb = -80f;
        peakEnv = 0f; rmsEnv = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // K-weighting biquads (BS.1770).
    private float[] hsCoefs, hpCoefs;
    private final float[] hsState = new float[4], hpState = new float[4];
    // 3-sec LUFS ring of mean-square samples.
    private float[] lufsRing;
    private int lufsRingW = 0;
    private double lufsSumSq = 0;

    private float peakEnv = 0f, rmsEnv = 0f;
    private float peakDb = -80f, rmsDb = -80f, lufsDb = -80f;

    private static final int HIST_LEN = 256;
    private final float[] envHist = new float[HIST_LEN];
    private int histW = 0;

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        if (hsCoefs == null) {
            hsCoefs = highShelf(1500f, 1.0f, 4.0f, sampleRate);
            hpCoefs = highPass(38f, 0.5f, sampleRate);
            lufsRing = new float[sampleRate * 3];   // 3 s of MS samples
        }
        float peakRel = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.001));
        float rmsCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.300));
        int histStride = Math.max(1, sampleRate / 100);
        int acc = 0;
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            // Peak follower (instant attack, slow release).
            float aAbs = s < 0 ? -s : s;
            if (aAbs > peakEnv) peakEnv = aAbs;
            else peakEnv += peakRel * (aAbs - peakEnv);
            // RMS via energy IIR.
            rmsEnv += rmsCoef * (s * s - rmsEnv);
            // K-weighted sample for LUFS.
            float kw = biquad(s, hsCoefs, hsState);
            kw = biquad(kw, hpCoefs, hpState);
            float old = lufsRing[lufsRingW];
            float ms = kw * kw;
            lufsRing[lufsRingW] = ms;
            lufsSumSq += ms - old;
            lufsRingW = (lufsRingW + 1) % lufsRing.length;
            acc++;
            if (acc >= histStride) {
                acc = 0;
                peakDb = (float)(20 * Math.log10(Math.max(1e-9f, peakEnv)));
                rmsDb  = (float)(10 * Math.log10(Math.max(1e-9f, rmsEnv)));
                double meanSq = lufsSumSq / lufsRing.length;
                lufsDb = (float)(-0.691 + 10 * Math.log10(Math.max(1e-9, meanSq)));
                envHist[histW] = peakDb;
                histW = (histW + 1) % HIST_LEN;
            }
        }
    }

    private static float biquad(float x, float[] c, float[] st) {
        float y = c[0] * x + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3];
        st[1] = st[0]; st[0] = x;
        st[3] = st[2]; st[2] = y;
        return y;
    }
    private static float[] highShelf(float fc, float q, float gainDb, int sr) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double beta = 2.0 * Math.sqrt(A) * alpha;
        double a0 = (A + 1) - (A - 1) * cs + beta;
        return new float[] {
            (float)(A * ((A + 1) + (A - 1) * cs + beta) / a0),
            (float)(-2 * A * ((A - 1) + (A + 1) * cs) / a0),
            (float)(A * ((A + 1) + (A - 1) * cs - beta) / a0),
            (float)(2 * ((A - 1) - (A + 1) * cs) / a0),
            (float)(((A + 1) - (A - 1) * cs - beta) / a0)
        };
    }
    private static float[] highPass(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double a0 = 1 + alpha;
        return new float[] {
            (float)((1 + cs) * 0.5 / a0),
            (float)(-(1 + cs) / a0),
            (float)((1 + cs) * 0.5 / a0),
            (float)(-2 * cs / a0),
            (float)((1 - alpha) / a0)
        };
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_PEAK        = 0xFFE34855;
    private static final int COLOR_RMS         = 0xFFF5C842;
    private static final int COLOR_LUFS        = 0xFF6FE07A;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            barBg, barFill, envLine;
    private PluginPath envPath;

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
        canvas.drawText("VOCAL DYNAMICS", 12f, 16f, textBright);

        float pad = 12f, headerH = 24f;
        // Top: 3 horizontal bar meters (peak / RMS / LUFS).
        float barAreaY0 = pad + headerH;
        float barAreaH = 60f;
        float barW = W - pad * 2 - 80f;
        float labelX = pad;
        float barX0 = pad + 80f;
        drawBar(canvas, barX0, barAreaY0,            barX0 + barW, barAreaY0 + 14f,
                peakDb, "PEAK", COLOR_PEAK, labelX);
        drawBar(canvas, barX0, barAreaY0 + 20f,      barX0 + barW, barAreaY0 + 34f,
                rmsDb,  "RMS",  COLOR_RMS,  labelX);
        drawBar(canvas, barX0, barAreaY0 + 40f,      barX0 + barW, barAreaY0 + 54f,
                lufsDb, "LUFS-S", COLOR_LUFS, labelX);

        // Bottom: scrolling peak history.
        float plotY0 = barAreaY0 + barAreaH + 8f;
        float plotY1 = H - pad - 18f;
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(pad + 28f, plotY0, W - pad, plotY1, 6f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(pad + 28f, plotY0, W - pad, plotY1, 6f, cardPaint);
        // Grid dB.
        for (int db = 0; db >= -60; db -= 20) {
            float t = (-db) / 60f;
            float y = plotY0 + t * (plotY1 - plotY0);
            cardPaint.setColor(0xFF353638).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(pad + 28f, y, W - pad, y, cardPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            canvas.drawText(db + "", pad + 25f, y + 3f, textDim);
        }
        envPath.reset();
        float plotX0 = pad + 28f, plotX1 = W - pad;
        float plotW = plotX1 - plotX0, plotH = plotY1 - plotY0;
        float step = plotW / (HIST_LEN - 1f);
        boolean started = false;
        for (int i = 0; i < HIST_LEN; i++) {
            int idx = (histW + i) % HIST_LEN;
            float db = envHist[idx];
            if (db < -60f) db = -60f; if (db > 0f) db = 0f;
            float px = plotX0 + i * step;
            float py = plotY0 + ((-db) / 60f) * plotH;
            if (!started) { envPath.moveTo(px, py); started = true; }
            else envPath.lineTo(px, py);
        }
        envLine.setColor(COLOR_PEAK).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
        canvas.drawPath(envPath, envLine);

        // Dynamic range readout.
        float dr = (peakDb < -80f || rmsDb < -80f) ? 0f : (peakDb - rmsDb);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9.5f).setTextAlign(0);
        canvas.drawText("peak history (last ~2.5 s)", plotX0 + 4f, plotY0 + 12f, textDim);
        textDim.setColor(COLOR_TEXT_BRIGHT).setTextAlign(2);
        canvas.drawText(String.format("dynamic range  %.1f dB", dr),
                plotX1 - 4f, plotY0 + 12f, textDim);
    }

    private void drawBar(PluginCanvas canvas, float x0, float y0, float x1, float y1,
                          float db, String label, int colour, float labelX) {
        barBg.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0, x1, y1, 3f, barBg);
        // Fill: -60..0 dB → 0..1
        float t = (db + 60f) / 60f;
        if (t < 0f) t = 0f; if (t > 1f) t = 1f;
        float fx = x0 + (x1 - x0) * t;
        barFill.setColor(colour).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0, fx, y1, 3f, barFill);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(10f).setTextAlign(0);
        canvas.drawText(label, labelX, (y0 + y1) * 0.5f + 4f, textBright);
        textBright.setColor(colour).setTextSize(10f).setTextAlign(2);
        canvas.drawText(String.format("%.1f dB", db), x1 - 6f, (y0 + y1) * 0.5f + 4f, textBright);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        barBg      = c.newPaint();
        barFill    = c.newPaint();
        envLine    = c.newPaint();
        envPath    = c.newPath();
    }
}
