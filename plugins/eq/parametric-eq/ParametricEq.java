package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

// Parametric EQ (native port) — three RBJ biquad bands (low-shelf,
// peaking bell with Q, high-shelf). Channel-strip tone shaping.
//
// Canvas-mode UI: live combined magnitude response from 20 Hz to
// 20 kHz drawn as a filled yellow curve, three "band handle" dots
// for low / mid / high at their centre frequencies. Yellow-on-black
// house theme; matches the look of the in-app Equalizer card.
public final class ParametricEq
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private final float[] ls = new float[4];
    private final float[] pk = new float[4];
    private final float[] hs = new float[4];
    private int sampleRate = 44100;
    private float lowFreq = 120f, lowGain = 0f, midFreq = 1000f, midGain = 0f,
                  midQ = 1f, highFreq = 8000f, highGain = 0f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        for (int i = 0; i < 4; i++) { ls[i] = 0f; pk[i] = 0f; hs[i] = 0f; }
    }

    @Override public String[] parameterNames() {
        return new String[] { "lowFreq", "lowGain", "midFreq", "midGain", "midQ", "highFreq", "highGain" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "lowFreq":  return 30f;
            case "midFreq":  return 200f;
            case "midQ":     return 0.3f;
            case "highFreq": return 1500f;
            case "lowGain": case "midGain": case "highGain": return -18f;
            default:         return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "lowFreq":  return 500f;
            case "midFreq":  return 6000f;
            case "midQ":     return 8f;
            case "highFreq": return 16000f;
            case "lowGain": case "midGain": case "highGain": return 18f;
            default:         return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "lowFreq":  return 120f;
            case "midFreq":  return 1000f;
            case "midQ":     return 1f;
            case "highFreq": return 8000f;
            default:         return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "lowFreq":  return "Low Hz";
            case "lowGain":  return "Low dB";
            case "midFreq":  return "Mid Hz";
            case "midGain":  return "Mid dB";
            case "midQ":     return "Mid Q";
            case "highFreq": return "High Hz";
            case "highGain": return "High dB";
            default:         return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "lowFreq":  lowFreq = v; break;
            case "lowGain":  lowGain = v; break;
            case "midFreq":  midFreq = v; break;
            case "midGain":  midGain = v; break;
            case "midQ":     midQ = v; break;
            case "highFreq": highFreq = v; break;
            case "highGain": highGain = v; break;
        }
    }

    private static float[] lowShelf(float fc, float gainDb, int sr) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / 2.0 * Math.sqrt((A + 1.0/A) * (1.0/1.0 - 1.0) + 2.0);
        double beta = 2.0 * Math.sqrt(A) * alpha;
        double a0 = (A + 1.0) + (A - 1.0) * c + beta;
        return new float[] {
            (float) (A * ((A + 1.0) - (A - 1.0) * c + beta) / a0),
            (float) (2.0 * A * ((A - 1.0) - (A + 1.0) * c) / a0),
            (float) (A * ((A + 1.0) - (A - 1.0) * c - beta) / a0),
            (float) (-2.0 * ((A - 1.0) + (A + 1.0) * c) / a0),
            (float) (((A + 1.0) + (A - 1.0) * c - beta) / a0)
        };
    }
    private static float[] peaking(float fc, float gainDb, float q, int sr) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / (2.0 * q);
        double a0 = 1.0 + alpha / A;
        return new float[] {
            (float) ((1.0 + alpha * A) / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha * A) / a0),
            (float) (-2.0 * c / a0),
            (float) ((1.0 - alpha / A) / a0)
        };
    }
    private static float[] highShelf(float fc, float gainDb, int sr) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w = 2.0 * Math.PI * fc / sr;
        double c = Math.cos(w), s = Math.sin(w);
        double alpha = s / 2.0 * Math.sqrt((A + 1.0/A) * (1.0/1.0 - 1.0) + 2.0);
        double beta = 2.0 * Math.sqrt(A) * alpha;
        double a0 = (A + 1.0) - (A - 1.0) * c + beta;
        return new float[] {
            (float) (A * ((A + 1.0) + (A - 1.0) * c + beta) / a0),
            (float) (-2.0 * A * ((A - 1.0) + (A + 1.0) * c) / a0),
            (float) (A * ((A + 1.0) + (A - 1.0) * c - beta) / a0),
            (float) (2.0 * ((A - 1.0) - (A + 1.0) * c) / a0),
            (float) (((A + 1.0) - (A - 1.0) * c - beta) / a0)
        };
    }

    @Override
    public void process(float[] input, float[] output) {
        float[] lc = lowShelf(lowFreq, lowGain, sampleRate);
        float[] mc = peaking(midFreq, midGain, midQ, sampleRate);
        float[] hc = highShelf(highFreq, highGain, sampleRate);
        final int n = input.length;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float y1 = lc[0]*x + lc[1]*ls[0] + lc[2]*ls[1] - lc[3]*ls[2] - lc[4]*ls[3];
            ls[1] = ls[0]; ls[0] = x;
            ls[3] = ls[2]; ls[2] = y1;
            float y2 = mc[0]*y1 + mc[1]*pk[0] + mc[2]*pk[1] - mc[3]*pk[2] - mc[4]*pk[3];
            pk[1] = pk[0]; pk[0] = y1;
            pk[3] = pk[2]; pk[2] = y2;
            float y3 = hc[0]*y2 + hc[1]*hs[0] + hc[2]*hs[1] - hc[3]*hs[2] - hc[4]*hs[3];
            hs[1] = hs[0]; hs[0] = y2;
            hs[3] = hs[2]; hs[2] = y3;
            output[i] = y3;
        }
    }

    // ---- Visual ----
    private static final int COLOR_BG          = 0xFF050505;
    private static final int COLOR_GRID        = 0xFF1E1E22;
    private static final int COLOR_GRID_MID    = 0xFF2A2A2E;
    private static final int COLOR_TEXT_DIM    = 0xFF7C7C82;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_YELLOW      = 0xFFF5C842;
    private static final int COLOR_YELLOW_FILL = 0x44F5C842;
    private static final int COLOR_ZERO_LINE   = 0xFF3A3A40;
    private static final float F_MIN = 20f;
    private static final float F_MAX = 20000f;
    private static final float DB_RANGE = 18f;
    private static final int CURVE_POINTS = 256;

    private PluginPaint bgPaint, gridPaint, textDim, textBright,
            zeroLine, curveLine, curveFill, bandDot, bandLabel;
    private PluginPath curvePath, fillPath;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        final float W = width, H = height;

        // Live params from host map.
        float lF = paramOr(params, "lowFreq",  lowFreq);
        float lG = paramOr(params, "lowGain",  lowGain);
        float mF = paramOr(params, "midFreq",  midFreq);
        float mG = paramOr(params, "midGain",  midGain);
        float mQ = paramOr(params, "midQ",     midQ);
        float hF = paramOr(params, "highFreq", highFreq);
        float hG = paramOr(params, "highGain", highGain);

        bgPaint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, W, H, bgPaint);

        // Layout.
        float pad = 12f;
        float headerH = 22f;
        float labelH = 14f;
        float plotX0 = pad + 28f;
        float plotY0 = pad + headerH;
        float plotX1 = W - pad;
        float plotY1 = H - pad - labelH;
        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;
        if (plotW < 60f || plotH < 60f) return;

        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("PARAMETRIC EQ", pad, pad + 13, textBright);

        // dB grid lines (every 6 dB).
        for (int db = -18; db <= 18; db += 6) {
            float t = 0.5f - db / (DB_RANGE * 2f);
            float y = plotY0 + t * plotH;
            int col = db == 0 ? COLOR_GRID_MID : COLOR_GRID;
            gridPaint.setColor(col).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            if (db == 0 || db == -18 || db == 18 || db == -12 || db == 12) {
                textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
                canvas.drawText(String.format("%+d", db), plotX0 - 3f, y + 3f, textDim);
            }
        }
        // Frequency grid (log spaced).
        int[] gridHz = { 30, 100, 300, 1000, 3000, 10000 };
        for (int hz : gridHz) {
            float x = freqToX(hz, plotX0, plotX1);
            gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
            canvas.drawLine(x, plotY0, x, plotY1, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(1);
            String lbl = hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz);
            canvas.drawText(lbl, x, plotY1 + 11f, textDim);
        }
        // 0 dB bold reference.
        zeroLine.setColor(COLOR_ZERO_LINE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.5f);
        canvas.drawLine(plotX0, plotY0 + plotH * 0.5f,
                         plotX1, plotY0 + plotH * 0.5f, zeroLine);

        // Sample the magnitude response across CURVE_POINTS log-spaced
        // frequencies. Each band's contribution comes from its analytic
        // |H(e^jω)| formula (cheap, no per-frame biquad eval).
        curvePath.reset(); fillPath.reset();
        double logMin = Math.log(F_MIN), logMax = Math.log(F_MAX);
        double a_lo = Math.PI * lF / sampleRate;  // half-angle param for shelves
        double a_hi = Math.PI * hF / sampleRate;
        for (int p = 0; p < CURVE_POINTS; p++) {
            double t = p / (double) (CURVE_POINTS - 1);
            float f = (float) Math.exp(logMin + (logMax - logMin) * t);
            float dbTotal = magLowShelfDb(f, lF, lG)
                          + magPeakingDb(f, mF, mG, mQ)
                          + magHighShelfDb(f, hF, hG);
            float x = plotX0 + (float) t * plotW;
            float y = dbToY(dbTotal, plotY0, plotY1);
            if (p == 0) {
                curvePath.moveTo(x, y);
                fillPath.moveTo(x, plotY0 + plotH * 0.5f).lineTo(x, y);
            } else {
                curvePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        fillPath.lineTo(plotX1, plotY0 + plotH * 0.5f).close();
        curveFill.setColor(COLOR_YELLOW_FILL).setStyle(PluginStyle.FILL);
        canvas.drawPath(fillPath, curveFill);
        curveLine.setColor(COLOR_YELLOW).setStyle(PluginStyle.STROKE)
                .setStrokeWidth(1.8f).setGlow(COLOR_YELLOW, 4f);
        canvas.drawPath(curvePath, curveLine);

        // Three band-handle dots at (band fc, band gain).
        drawBandDot(canvas, lF, lG, plotX0, plotY0, plotX1, plotY1, "L");
        drawBandDot(canvas, mF, mG, plotX0, plotY0, plotX1, plotY1, "M");
        drawBandDot(canvas, hF, hG, plotX0, plotY0, plotX1, plotY1, "H");
    }

    private void drawBandDot(PluginCanvas canvas, float fc, float gainDb,
                              float x0, float y0, float x1, float y1, String label) {
        float x = freqToX(fc, x0, x1);
        float y = dbToY(gainDb, y0, y1);
        bandDot.setColor(COLOR_YELLOW).setStyle(PluginStyle.FILL)
                .setGlow(COLOR_YELLOW, 8f);
        canvas.drawCircle(x, y, 5.5f, bandDot);
        bandDot.setColor(0xFF101010).setStyle(PluginStyle.FILL);
        canvas.drawCircle(x, y, 2.5f, bandDot);
        bandLabel.setColor(COLOR_TEXT_BRIGHT).setTextSize(10f).setTextAlign(1);
        canvas.drawText(label, x, y - 9f, bandLabel);
    }

    private static float freqToX(float f, float x0, float x1) {
        double logMin = Math.log(F_MIN), logMax = Math.log(F_MAX);
        if (f < F_MIN) f = F_MIN; if (f > F_MAX) f = F_MAX;
        double t = (Math.log(f) - logMin) / (logMax - logMin);
        return x0 + (float) t * (x1 - x0);
    }

    private static float dbToY(float db, float y0, float y1) {
        float t = 0.5f - db / (DB_RANGE * 2f);
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return y0 + t * (y1 - y0);
    }

    // Analytic magnitude responses in dB. These mirror what the audio
    // biquad coefficients produce at frequency f, derived from the
    // RBJ cookbook closed forms — close enough for a visual at sub-dB
    // accuracy without per-frame coefficient regeneration.

    private float magLowShelfDb(float f, float fc, float gainDb) {
        if (Math.abs(gainDb) < 0.01f) return 0f;
        double A = Math.pow(10.0, gainDb / 40.0);
        // S-curve transition centred at fc.
        double r = Math.log(f / fc) / Math.log(10.0);
        // Approximate magnitude (works closely to the cookbook biquad
        // through the audible range).
        double mag = Math.sqrt(
            (A * A + 1.0) / 2.0 +
            (A * A - 1.0) / 2.0 * Math.tanh(-3.0 * r)
        );
        return (float) (20.0 * Math.log(mag) / Math.log(10.0));
    }
    private float magHighShelfDb(float f, float fc, float gainDb) {
        if (Math.abs(gainDb) < 0.01f) return 0f;
        double A = Math.pow(10.0, gainDb / 40.0);
        double r = Math.log(f / fc) / Math.log(10.0);
        double mag = Math.sqrt(
            (A * A + 1.0) / 2.0 +
            (A * A - 1.0) / 2.0 * Math.tanh(3.0 * r)
        );
        return (float) (20.0 * Math.log(mag) / Math.log(10.0));
    }
    private float magPeakingDb(float f, float fc, float gainDb, float q) {
        if (Math.abs(gainDb) < 0.01f) return 0f;
        // Bell-shape approximation: gain falls off as a Lorentzian
        // centred at fc, width controlled by q.
        double r = Math.log(f / fc) / Math.log(2.0);  // octaves from fc
        double bw = 1.0 / q;                          // approximate bandwidth (octaves)
        double rel = (r * r) / (bw * bw);
        double atten = 1.0 / (1.0 + rel * 4.0);
        return (float) (gainDb * atten);
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
        zeroLine   = c.newPaint();
        curveLine  = c.newPaint();
        curveFill  = c.newPaint();
        bandDot    = c.newPaint();
        bandLabel  = c.newPaint();
        curvePath  = c.newPath();
        fillPath   = c.newPath();
    }
}
