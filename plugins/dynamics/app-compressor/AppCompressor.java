package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Bit-exact port of the app's built-in {@code Compressor.kt} +
 * transfer-curve canvas from {@code CompressorCard.kt}.
 *
 * DSP: single-channel feed-forward peak compressor with soft knee,
 * asymmetric attack/release envelope, makeup gain.  Identical
 * coefficients + math to the Kotlin source so plugin and host
 * produce identical output.
 *
 * Visual: dB-in / dB-out transfer curve plotted from the same
 * static-gain function the host uses for the in-app
 * CompressorCard.  60×60 dB grid, dotted 1:1 reference line, yellow
 * compression curve, vertical threshold marker.
 */
public final class AppCompressor implements VocalMonitorVisualPlugin {

    private static final float LN10 = 2.302585092994046f;

    private int sampleRate = 44_100;

    private float thresholdDb  = -18f;
    private float ratio         =   4f;
    private float attackMs      =   5f;
    private float releaseMs     = 120f;
    private float kneeWidthDb   =   6f;
    private float makeupGainDb  =   4f;

    private float attackCoef;
    private float releaseCoef;
    private float envelopeDb = 0f;

    @Override
    public void init(int sr) {
        this.sampleRate = Math.max(8_000, sr);
        envelopeDb = 0f;
        recomputeCoefs();
    }

    private float computeCoef(float timeMs) {
        float seconds = timeMs / 1000f;
        if (seconds < 1e-6f) seconds = 1e-6f;
        return (float) Math.exp(-1.0 / (sampleRate * seconds));
    }
    private void recomputeCoefs() {
        attackCoef  = computeCoef(attackMs);
        releaseCoef = computeCoef(releaseMs);
    }

    @Override public String[] parameterNames() {
        return new String[] {
            "threshold", "ratio", "attack", "release", "knee", "makeup",
        };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "threshold": return -60f;
            case "ratio":     return   1f;
            case "attack":    return 0.1f;
            case "release":   return   5f;
            case "knee":      return   0f;
            case "makeup":    return   0f;
            default:          return   0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "threshold": return    0f;
            case "ratio":     return   40f;
            case "attack":    return  500f;
            case "release":   return 3000f;
            case "knee":      return   24f;
            case "makeup":    return   24f;
            default:          return    1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "threshold": return -18f;
            case "ratio":     return   4f;
            case "attack":    return   5f;
            case "release":   return 120f;
            case "knee":      return   6f;
            case "makeup":    return   4f;
            default:          return   0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "threshold": return "Threshold dB";
            case "ratio":     return "Ratio";
            case "attack":    return "Attack ms";
            case "release":   return "Release ms";
            case "knee":      return "Knee dB";
            case "makeup":    return "Makeup dB";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "threshold": thresholdDb  = clamp(v, -60f,    0f); break;
            case "ratio":     ratio        = clamp(v,   1f,   40f); break;
            case "attack":    attackMs     = clamp(v, 0.1f,  500f); attackCoef  = computeCoef(attackMs); break;
            case "release":   releaseMs    = clamp(v,   5f, 3000f); releaseCoef = computeCoef(releaseMs); break;
            case "knee":      kneeWidthDb  = clamp(v,   0f,   24f); break;
            case "makeup":    makeupGainDb = clamp(v,   0f,   24f); break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        final float halfKnee = kneeWidthDb * 0.5f;
        float env = envelopeDb;
        for (int i = 0; i < n; i++) {
            float x = input[i];
            float absSample = Math.abs(x);
            if (absSample < 1e-6f) absSample = 1e-6f;
            float inputDb = 20f * (float) Math.log(absSample) / LN10;
            float excess = inputDb - thresholdDb;
            float reductionFactor = 1f - 1f / ratio;
            if (reductionFactor < 0f) reductionFactor = 0f;
            float instantGr;
            if (excess <= -halfKnee) {
                instantGr = 0f;
            } else if (excess >= halfKnee) {
                instantGr = excess * reductionFactor;
            } else if (kneeWidthDb > 0f) {
                float kx = excess + halfKnee;
                instantGr = kx * kx / (2f * kneeWidthDb) * reductionFactor;
            } else {
                instantGr = 0f;
            }
            if (instantGr > env) env = instantGr + (env - instantGr) * attackCoef;
            else                 env = instantGr + (env - instantGr) * releaseCoef;
            float finalGainDb = -env + makeupGainDb;
            float gain = (float) Math.pow(10.0, finalGainDb / 20.0);
            float y = x * gain;
            if (y >  1f) y =  1f;
            if (y < -1f) y = -1f;
            output[i] = y;
        }
        envelopeDb = env;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Static transfer curve point — same math as the Kotlin source's
     *  {@code Compressor.staticOutputDb} helper, used by the canvas. */
    private static float staticOutputDb(
        float inputDb, float thresholdDb, float ratio,
        float kneeWidthDb, float makeupGainDb
    ) {
        float excess = inputDb - thresholdDb;
        float halfKnee = kneeWidthDb / 2f;
        float reductionFactor = 1f - 1f / ratio;
        if (reductionFactor < 0f) reductionFactor = 0f;
        float gr;
        if (excess <= -halfKnee) {
            gr = 0f;
        } else if (excess >= halfKnee) {
            gr = excess * reductionFactor;
        } else if (kneeWidthDb > 0f) {
            float kx = excess + halfKnee;
            gr = kx * kx / (2f * kneeWidthDb) * reductionFactor;
        } else {
            gr = 0f;
        }
        return inputDb - gr + makeupGainDb;
    }

    // ── Render — pixel-equivalent to slim's TransferCurve ────
    @Override
    public void render(
        PluginCanvas c, int width, int height, long timeMs,
        Map<String, Float> params, Map<String, float[]> streams
    ) {
        // Background.
        PluginPaint bg = c.newPaint();
        bg.setColor(0xFF080808);
        c.drawRect(0, 0, width, height, bg);

        float w = width;
        float h = height;
        float scale = Math.min(width, height) / 360f;
        float padL = 28f * scale;
        final float xMin = -60f, xMax = 0f;
        final float yMin = -60f, yMax = 12f;  // 12dB headroom for makeup

        // X grid every 12 dB.
        PluginPaint grid = c.newPaint();
        grid.setColor(0x66CFCFCF);
        for (int db = -60; db <= 0; db += 12) {
            float x = padL + (db - xMin) / (xMax - xMin) * (w - padL);
            c.drawLine(x, 0f, x, h, grid);
        }
        // Y grid every 12 dB.
        for (int db = -60; db <= 12; db += 12) {
            float y = h - (db - yMin) / (yMax - yMin) * h;
            c.drawLine(padL, y, w, y, grid);
        }

        // 1:1 reference curve (faint).
        PluginPath ref = c.newPath();
        int refSteps = 60;
        for (int i = 0; i <= refSteps; i++) {
            float db = xMin + (xMax - xMin) * i / refSteps;
            float x = padL + (db - xMin) / (xMax - xMin) * (w - padL);
            float y = h - (db - yMin) / (yMax - yMin) * h;
            if (i == 0) ref.moveTo(x, y); else ref.lineTo(x, y);
        }
        PluginPaint refP = c.newPaint();
        refP.setColor(0x59CFCFCF);
        refP.setStyle(PluginStyle.STROKE);
        refP.setStrokeWidth(Math.max(1f, 1f * scale));
        c.drawPath(ref, refP);

        // Compression curve.
        PluginPath curve = c.newPath();
        int steps = 200;
        for (int i = 0; i <= steps; i++) {
            float inDb = xMin + (xMax - xMin) * i / steps;
            float outDb = staticOutputDb(inDb, thresholdDb, ratio, kneeWidthDb, makeupGainDb);
            float x = padL + (inDb - xMin) / (xMax - xMin) * (w - padL);
            float y = h - (outDb - yMin) / (yMax - yMin) * h;
            if (i == 0) curve.moveTo(x, y); else curve.lineTo(x, y);
        }
        PluginPaint cp = c.newPaint();
        cp.setColor(0xFFFFD34A);
        cp.setStyle(PluginStyle.STROKE);
        cp.setStrokeWidth(Math.max(1.5f, 2.5f * scale));
        c.drawPath(curve, cp);

        // Threshold marker.
        float thrX = padL + (thresholdDb - xMin) / (xMax - xMin) * (w - padL);
        PluginPaint thr = c.newPaint();
        thr.setColor(0x66FFD34A);
        c.drawLine(thrX, 0f, thrX, h, thr);

        // Axis labels.
        PluginPaint lbl = c.newPaint();
        lbl.setColor(0xFFAAAAAA);
        lbl.setTextSize(Math.max(8f, 10f * scale));
        lbl.setTextAlign(0);
        c.drawText("in dB",  padL + 4f * scale, h - 4f * scale, lbl);
        lbl.setTextAlign(2);
        c.drawText("out dB", w - 4f * scale, 12f * scale, lbl);
    }
}
