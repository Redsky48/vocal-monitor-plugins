package com.vocalmonitor.plugin.gamekit.ui;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.gamekit.Palette;

/**
 * Circular rotary knob.  Touching inside grabs it; vertical drag
 * sweeps the value (up = increase, down = decrease).  Range is
 * normalised [0, 1] internally; caller maps to the real domain.
 *
 * The indicator is a notch on the disk; the value-arc sweeps
 * from −135° (min) to +135° (max), so the full sweep is 270° —
 * matches the convention of most plugin GUIs and feels natural.
 *
 *   private final Knob cutoff = new Knob().value01(0.6f);
 *   ... in onTouch / render same as Slider ...
 */
public final class Knob {

    private static final float SWEEP_DEG = 270f;
    private static final float START_DEG = -SWEEP_DEG / 2f;  // -135°

    public final HitZone hit = new HitZone();
    private float value01 = 0f;
    private boolean dragging = false;
    private float dragStartY = 0f;
    private float dragStartValue = 0f;
    private float dragSensitivity = 0.005f;     // per-px drag → 0..1 units

    private int knobColor = 0xFF22262C;
    private int arcColor = Palette.ACCENT_YELLOW;
    private int notchColor = 0xFFFFFFFF;

    public Knob value01(float v) {
        this.value01 = v < 0f ? 0f : (v > 1f ? 1f : v);
        return this;
    }
    public float value01() { return value01; }
    public float valueScaled(float lo, float hi) { return lo + (hi - lo) * value01; }

    public Knob sensitivity(float perPx) { this.dragSensitivity = perPx; return this; }
    public Knob arcColor(int c)          { this.arcColor = c; return this; }
    public Knob knobColor(int c)         { this.knobColor = c; return this; }

    public void touchDown(float x, float y) {
        if (hit.touchDown(x, y)) {
            dragging = true;
            dragStartY = y;
            dragStartValue = value01;
        }
    }
    public void touchMove(float x, float y) {
        hit.touchMove(x, y);
        if (dragging) {
            float dy = dragStartY - y;       // up = positive
            float v = dragStartValue + dy * dragSensitivity;
            value01 = v < 0f ? 0f : (v > 1f ? 1f : v);
        }
    }
    public void touchUp(float x, float y) {
        hit.touchUp(x, y);
        dragging = false;
    }

    public void draw(PluginCanvas c, float cx, float cy, float radius, float scale) {
        hit.bounds(cx - radius, cy - radius, cx + radius, cy + radius);
        // Background ring (un-swept).
        PluginPaint ringBg = c.newPaint();
        ringBg.setColor(Palette.UI_BORDER);
        ringBg.setStyle(PluginStyle.STROKE);
        ringBg.setStrokeWidth(Math.max(2f, 4f * scale));
        c.drawCircle(cx, cy, radius, ringBg);
        // Knob disc.
        PluginPaint disc = c.newPaint();
        disc.setRadialGradient(cx - radius * 0.3f, cy - radius * 0.3f, radius * 1.4f,
            new int[] { Palette.lighten(knobColor, 0.25f), knobColor, Palette.darken(knobColor, 0.3f) },
            new float[] { 0f, 0.5f, 1f });
        c.drawCircle(cx, cy, radius * 0.85f, disc);
        // Notch indicator — pointing at the swept angle.
        float angDeg = START_DEG + SWEEP_DEG * value01;
        float angRad = (float) Math.toRadians(angDeg - 90f);
        float nx0 = cx + (float) Math.cos(angRad) * radius * 0.30f;
        float ny0 = cy + (float) Math.sin(angRad) * radius * 0.30f;
        float nx1 = cx + (float) Math.cos(angRad) * radius * 0.72f;
        float ny1 = cy + (float) Math.sin(angRad) * radius * 0.72f;
        PluginPaint notch = c.newPaint();
        notch.setColor(notchColor);
        notch.setStrokeWidth(Math.max(2f, 3f * scale));
        c.drawLine(nx0, ny0, nx1, ny1, notch);
        // Swept arc — sample line segments along the arc.
        PluginPaint arc = c.newPaint();
        arc.setColor(arcColor);
        arc.setStrokeWidth(Math.max(2f, 4f * scale));
        int steps = 32;
        float prevX = cx + (float) Math.cos(Math.toRadians(START_DEG - 90f)) * radius;
        float prevY = cy + (float) Math.sin(Math.toRadians(START_DEG - 90f)) * radius;
        float sweepEnd = START_DEG + SWEEP_DEG * value01;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float a = START_DEG + (sweepEnd - START_DEG) * t;
            float ar = (float) Math.toRadians(a - 90f);
            float px = cx + (float) Math.cos(ar) * radius;
            float py = cy + (float) Math.sin(ar) * radius;
            c.drawLine(prevX, prevY, px, py, arc);
            prevX = px; prevY = py;
        }
    }
}
