package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginHost;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Bit-exact port of the app's built-in {@code BiquadEqualizer.kt} +
 * {@code EqualizerWidget.kt} — 10-band constant-Q peaking EQ with the
 * same visual + touch behaviour the host's graphic EQ ships with.
 *
 * DSP: 10 biquad peaking filters at ISO octave centres (31, 62, 125,
 * 250, 500, 1k, 2k, 4k, 8k, 16k Hz), Q = 1.4, RBJ "Audio EQ
 * Cookbook" formulas.  Identical to the Kotlin source, including the
 * "skip near-flat band" optimisation.
 *
 * UI: vertical bars per band tinted yellow, a smooth curve through
 * the thumbs, frequency labels under each band, centre 0-dB line +
 * faint ±7.5 dB markers.  Drag horizontally and every band the
 * finger crosses adopts that y-position as its new gain; touches
 * within ±0.5 dB of centre snap to 0.  Touch events push back
 * through {@link PluginHost#setParameter} so the host's standard
 * undo / preset save flow captures the gesture.
 */
public final class AppEq implements VocalMonitorVisualPlugin {

    private static final int      BAND_COUNT      = 10;
    private static final int[]    BAND_FREQUENCIES =
        new int[] { 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };
    private static final String[] BAND_LABELS =
        new String[] { "31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k" };
    private static final double   Q               = 1.4;
    private static final float    GAIN_RANGE_DB   = 15f;     // matches widget's ±15

    private int sampleRate = 44_100;
    private PluginHost host = null;

    private final float[]  gainsDb = new float[BAND_COUNT];
    private final double[] b0 = new double[BAND_COUNT];
    private final double[] b1 = new double[BAND_COUNT];
    private final double[] b2 = new double[BAND_COUNT];
    private final double[] a1 = new double[BAND_COUNT];
    private final double[] a2 = new double[BAND_COUNT];
    private final double[] z1 = new double[BAND_COUNT];
    private final double[] z2 = new double[BAND_COUNT];

    // Touch / drag state.
    private boolean dragging = false;
    private int lastBand = -1;
    // Cached canvas size for hit-tests done in touch callbacks.
    private float lastWidth = 1f, lastHeight = 1f;

    @Override public void setHost(PluginHost h) { this.host = h; }

    @Override
    public void init(int sr) {
        this.sampleRate = Math.max(8_000, sr);
        for (int i = 0; i < BAND_COUNT; i++) { gainsDb[i] = 0f; z1[i] = 0; z2[i] = 0; }
        recompute();
    }

    private void recompute() {
        for (int i = 0; i < BAND_COUNT; i++) {
            double freq = BAND_FREQUENCIES[i];
            double gain = gainsDb[i];
            if (Math.abs(gain) < 0.05) {
                b0[i] = 1.0; b1[i] = 0.0; b2[i] = 0.0;
                a1[i] = 0.0; a2[i] = 0.0;
                continue;
            }
            double a = Math.pow(10.0, gain / 40.0);
            double w0 = 2.0 * Math.PI * freq / sampleRate;
            double cosW0 = Math.cos(w0);
            double sinW0 = Math.sin(w0);
            double alpha = sinW0 / (2.0 * Q);
            double a0c = 1.0 + alpha / a;
            b0[i] = (1.0 + alpha * a) / a0c;
            b1[i] = (-2.0 * cosW0) / a0c;
            b2[i] = (1.0 - alpha * a) / a0c;
            a1[i] = (-2.0 * cosW0) / a0c;
            a2[i] = (1.0 - alpha / a) / a0c;
        }
    }

    @Override public String[] parameterNames() {
        return new String[] {
            "band0", "band1", "band2", "band3", "band4",
            "band5", "band6", "band7", "band8", "band9",
        };
    }
    @Override public float parameterMin(String n)     { return -GAIN_RANGE_DB; }
    @Override public float parameterMax(String n)     { return  GAIN_RANGE_DB; }
    @Override public float parameterDefault(String n) { return  0f; }
    @Override public String parameterLabel(String n) {
        if (n != null && n.length() == 5 && n.startsWith("band")) {
            char c = n.charAt(4);
            if (c >= '0' && c <= '9') return BAND_LABELS[c - '0'] + " Hz";
        }
        return n;
    }
    @Override public void setParameter(String n, float v) {
        if (n == null || n.length() != 5 || !n.startsWith("band")) return;
        char c = n.charAt(4);
        if (c < '0' || c > '9') return;
        int idx = c - '0';
        if (gainsDb[idx] == v) return;
        gainsDb[idx] = v;
        recompute();
        for (int i = 0; i < BAND_COUNT; i++) { z1[i] = 0; z2[i] = 0; }
    }

    @Override
    public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int s = 0; s < n; s++) {
            double x = input[s];
            for (int i = 0; i < BAND_COUNT; i++) {
                double y  = b0[i] * x + z1[i];
                z1[i]     = b1[i] * x - a1[i] * y + z2[i];
                z2[i]     = b2[i] * x - a2[i] * y;
                x = y;
            }
            if (x >  1.0) x =  1.0;
            if (x < -1.0) x = -1.0;
            output[s] = (float) x;
        }
    }

    // ── Touch ─────────────────────────────────────────────────
    @Override
    public void onTouchDown(float x, float y) {
        dragging = true;
        lastBand = -1;
        applyAt(x, y);
    }
    @Override
    public void onTouchMove(float x, float y) {
        if (!dragging) return;
        applyAt(x, y);
    }
    @Override
    public void onTouchUp(float x, float y) {
        dragging = false;
        lastBand = -1;
    }

    /** Translate a touch into (band, gain) and push via host. */
    private void applyAt(float x, float y) {
        if (lastWidth <= 0f || lastHeight <= 0f) return;
        float bandW = lastWidth / BAND_COUNT;
        int idx = (int) (x / bandW);
        if (idx < 0) idx = 0; if (idx >= BAND_COUNT) idx = BAND_COUNT - 1;
        float gain = yToGain(y, lastHeight);
        if (Math.abs(gain) < 0.5f) gain = 0f;
        if (gainsDb[idx] == gain) { lastBand = idx; return; }
        // Update locally so the curve re-renders immediately and let
        // the host catch up on the next setParameter dispatch.
        gainsDb[idx] = gain;
        recompute();
        for (int i = 0; i < BAND_COUNT; i++) { z1[i] = 0; z2[i] = 0; }
        if (host != null) host.setParameter("band" + idx, gain);
        lastBand = idx;
    }

    private static float yToGain(float y, float h) {
        float centerY = h / 2f;
        float frac = (centerY - y) / centerY;
        if (frac >  1f) frac =  1f;
        if (frac < -1f) frac = -1f;
        return frac * GAIN_RANGE_DB;
    }
    private static float gainToY(float gainDb, float h) {
        float centerY = h / 2f;
        return centerY - (gainDb / GAIN_RANGE_DB) * centerY;
    }

    // ── Render — pixel-equivalent to slim's EqualizerBody ────
    @Override
    public void render(
        PluginCanvas c, int width, int height, long timeMs,
        Map<String, Float> params, Map<String, float[]> streams
    ) {
        lastWidth = width;
        lastHeight = height;
        // Background.
        PluginPaint bg = c.newPaint();
        bg.setColor(0xFF080808);
        c.drawRect(0, 0, width, height, bg);

        float h = height;
        float w = width;
        float centerY = h / 2f;
        float bandW = w / BAND_COUNT;
        float scale = Math.min(width, height) / 360f;

        // Centre 0-dB line.
        PluginPaint zero = c.newPaint();
        zero.setColor(0xFFCFCFCF);
        c.drawLine(0, centerY, w, centerY, zero);

        // Faint ±7.5 dB markers (0.25 / 0.75 of height — matches widget).
        PluginPaint faint = c.newPaint();
        faint.setColor(0x66CFCFCF);
        c.drawLine(0, h * 0.25f, w, h * 0.25f, faint);
        c.drawLine(0, h * 0.75f, w, h * 0.75f, faint);

        // Per-band vertical guides.
        PluginPaint guide = c.newPaint();
        guide.setColor(0x40CFCFCF);
        for (int i = 0; i < BAND_COUNT; i++) {
            float x = (i + 0.5f) * bandW;
            c.drawLine(x, 0, x, h, guide);
        }

        // Curve through the thumbs.
        PluginPath path = c.newPath();
        for (int i = 0; i < BAND_COUNT; i++) {
            float x = (i + 0.5f) * bandW;
            float y = gainToY(gainsDb[i], h);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        PluginPaint curve = c.newPaint();
        curve.setColor(0xFFFFD34A);
        curve.setStyle(PluginStyle.STROKE);
        curve.setStrokeWidth(Math.max(2f, 3f * scale));
        c.drawPath(path, curve);

        // Per-band tinted bar from 0 to current value + thumb.
        PluginPaint bar = c.newPaint();
        bar.setColor(0x2DFFD34A);
        PluginPaint thumb = c.newPaint();
        thumb.setColor(0xFFFFD34A);
        for (int i = 0; i < BAND_COUNT; i++) {
            float x = (i + 0.5f) * bandW;
            float y = gainToY(gainsDb[i], h);
            float barTop = Math.min(centerY, y);
            float barBot = Math.max(centerY, y);
            c.drawRoundRect(
                x - bandW * 0.30f, barTop,
                x + bandW * 0.30f, barBot,
                2f * scale, bar
            );
            c.drawCircle(x, y, Math.max(4f, 7f * scale), thumb);
        }

        // Frequency labels along the bottom (inside the canvas so the
        // host doesn't need to make extra room).
        PluginPaint lbl = c.newPaint();
        lbl.setColor(0xFFAAAAAA);
        lbl.setTextSize(Math.max(8f, 10f * scale));
        lbl.setTextAlign(1);
        float labelY = h - 4f * scale;
        for (int i = 0; i < BAND_COUNT; i++) {
            float x = (i + 0.5f) * bandW;
            c.drawText(BAND_LABELS[i], x, labelY, lbl);
        }
    }
}
