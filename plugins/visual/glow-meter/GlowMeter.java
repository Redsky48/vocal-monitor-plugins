package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.BlendMode;
import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Reference example of a canvas-mode visual plugin. Pass-through audio
 * (no DSP) plus a pulsing glow ring whose radius and brightness track
 * the input level.
 *
 * The point of this plugin is to be a concise, copyable starting point
 * for plugin authors who want their own custom panel. It exercises the
 * APIs you're most likely to reach for first:
 *
 *  - {@link PluginCanvas#drawCircle} as a primitive
 *  - {@link PluginPaint#setGlow} for a soft outer glow
 *  - {@link PluginPaint#setBlendMode} with {@link BlendMode#ADD} so
 *    overlapping glow layers light up rather than wash out
 *  - {@link PluginPaint#setRadialGradient} for an inner fill
 *  - {@code timeMs} for time-driven animation
 *  - Self-computed audio level (a moving-average envelope of the
 *    block's RMS) so the visual responds to the live signal without
 *    needing the host's streams API
 *
 * The audio contract is a pass-through: {@code output[i] = input[i]}.
 * That keeps the plugin safe to drop anywhere in a chain while you're
 * iterating on the visual.
 */
public final class GlowMeter
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 48000;

    /** Smoothed RMS envelope (linear, 0..1). Read by render(). */
    private float envelope = 0f;

    // ─── Audio interface ──────────────────────────────────────────────

    @Override public void init(int sampleRate) {
        this.sampleRate = sampleRate;
        this.envelope = 0f;
    }

    @Override public String[] parameterNames() {
        return new String[0];
    }
    @Override public float parameterMin(String name) { return 0f; }
    @Override public float parameterMax(String name) { return 1f; }
    @Override public float parameterDefault(String name) { return 0f; }
    @Override public String parameterLabel(String name) { return name; }
    @Override public void setParameter(String name, float value) { }

    @Override public void process(float[] input, float[] output) {
        double sumSq = 0.0;
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            sumSq += s * s;
        }
        if (n == 0) return;
        float rms = (float) Math.sqrt(sumSq / n);
        // First-order smoother: blend ~50ms of history into the
        // envelope so the visual doesn't strobe on transients.
        float attack  = 0.50f;
        float release = 0.05f;
        envelope = (rms > envelope)
            ? envelope + (rms - envelope) * attack
            : envelope + (rms - envelope) * release;
    }

    // ─── Visual interface ─────────────────────────────────────────────
    // Per the SDK doc: cache PluginPaint / PluginPath across frames.
    // The host can render this plugin's panel 60+ times per second; if
    // we allocated a new Paint each call the GC would visibly hiccup.

    private PluginPaint bgPaint;
    private PluginPaint glowPaint;
    private PluginPaint innerPaint;

    @Override public void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    ) {
        if (bgPaint == null) {
            bgPaint    = canvas.newPaint();
            glowPaint  = canvas.newPaint();
            innerPaint = canvas.newPaint();
        }

        // Background — solid near-black so glow layers light up cleanly
        // when composited with ADD blend.
        bgPaint.setColor(0xFF050505).setStyle(PluginStyle.FILL);
        canvas.drawRect(0f, 0f, (float) width, (float) height, bgPaint);

        // Map the smoothed envelope to a 0..1 visual intensity. Clamp
        // because a hot mic can punch above 1.0 in linear units.
        float level = Math.max(0f, Math.min(1f, envelope * 4f));

        float cx = width  * 0.5f;
        float cy = height * 0.5f;
        float baseRadius = Math.min(width, height) * 0.18f;
        float radius    = baseRadius + baseRadius * 0.6f * level;

        // Subtle "breathing" so the ring is alive even at silence.
        float breath = (float) Math.sin(timeMs / 800.0) * 0.5f + 0.5f;
        radius += baseRadius * 0.05f * breath;

        // Glow ring — additive blend so multiple draws stack into a
        // bloom rather than over-painting each other to opaque yellow.
        int hot = lerpColor(0xFFFFD34A, 0xFFFF6F61, level);
        glowPaint
            .setColor(hot)
            .setStyle(PluginStyle.STROKE)
            .setStrokeWidth(3f + level * 6f)
            .setGlow(hot, 14f + level * 24f)
            .setBlendMode(BlendMode.ADD)
            .setAntialias(true);
        canvas.drawCircle(cx, cy, radius, glowPaint);

        // Inner soft fill via radial gradient — gives the ring some
        // body when the envelope is high, fades to almost nothing at
        // silence so we don't paint a permanent blob.
        int[] innerColors = new int[] {
            colorWithAlpha(hot, (int) (level * 200)),
            colorWithAlpha(hot, 0),
        };
        float[] innerStops = new float[] { 0f, 1f };
        innerPaint
            .setStyle(PluginStyle.FILL)
            .setRadialGradient(cx, cy, radius * 0.95f, innerColors, innerStops)
            .setBlendMode(BlendMode.ADD)
            .setAntialias(true);
        canvas.drawCircle(cx, cy, radius * 0.95f, innerPaint);
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int blue = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | blue;
    }

    private static int colorWithAlpha(int rgb, int alpha) {
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
}
