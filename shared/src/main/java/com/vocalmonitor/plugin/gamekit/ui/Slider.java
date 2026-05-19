package com.vocalmonitor.plugin.gamekit.ui;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.gamekit.Palette;

/**
 * Horizontal slider — value is stored in [0, 1].  Caller converts
 * to the real domain when reading via {@link #valueScaled}.
 *
 * Touch behaviour: tapping anywhere on the track jumps the thumb
 * to that x; dragging continues to track the finger until release.
 *
 *   private final Slider volSlider = new Slider().value01(0.6f);
 *
 *   onTouchDown: volSlider.touchDown(x, y);
 *   onTouchMove: volSlider.touchMove(x, y);
 *   onTouchUp:   volSlider.touchUp(x, y);
 *
 *   render:
 *     volSlider.draw(c, x0, y0, x1, y1, scale);
 *     float volume = volSlider.valueScaled(0f, 100f);
 *
 * For plugin parameters, drive the slider FROM `params` each frame
 * (`volSlider.value01(params.get("vol"))`) and write back through
 * `host.setParameter("vol", volSlider.valueScaled(...))`.  Two-way
 * binding keeps the slider in sync with external parameter changes
 * (e.g. preset load).
 */
public final class Slider {

    public final HitZone hit = new HitZone();
    private float value01 = 0f;
    private float trackX0 = 0f, trackX1 = 1f;

    private int activeColor = Palette.ACCENT_YELLOW;
    private int trackColor = Palette.UI_BORDER;

    public Slider value01(float v) {
        this.value01 = v < 0f ? 0f : (v > 1f ? 1f : v);
        return this;
    }
    public float value01() { return value01; }

    /** Map the internal 0..1 value into [lo, hi]. */
    public float valueScaled(float lo, float hi) {
        return lo + (hi - lo) * value01;
    }

    public Slider activeColor(int c) { this.activeColor = c; return this; }
    public Slider trackColor(int c)  { this.trackColor = c; return this; }

    public void touchDown(float x, float y) {
        if (hit.touchDown(x, y)) updateFromX(x);
    }
    public void touchMove(float x, float y) {
        hit.touchMove(x, y);
        if (hit.pressed()) updateFromX(x);
    }
    public void touchUp(float x, float y) { hit.touchUp(x, y); }

    private void updateFromX(float x) {
        if (trackX1 <= trackX0) return;
        float t = (x - trackX0) / (trackX1 - trackX0);
        if (t < 0f) t = 0f; if (t > 1f) t = 1f;
        value01 = t;
    }

    public void draw(
        PluginCanvas c,
        float x0, float y0, float x1, float y1,
        float scale
    ) {
        // Hit zone covers the full rect; track itself can be a
        // narrower band so visual feedback is cleaner.
        hit.bounds(x0, y0, x1, y1);
        trackX0 = x0 + (y1 - y0) * 0.4f;
        trackX1 = x1 - (y1 - y0) * 0.4f;
        float trackY = (y0 + y1) / 2f;
        float trackH = Math.max(2f, (y1 - y0) * 0.18f);
        // Track.
        PluginPaint trk = c.newPaint();
        trk.setColor(trackColor);
        c.drawRoundRect(trackX0, trackY - trackH / 2f,
            trackX1, trackY + trackH / 2f, trackH / 2f, trk);
        // Active portion.
        float thumbX = trackX0 + (trackX1 - trackX0) * value01;
        PluginPaint act = c.newPaint();
        act.setColor(activeColor);
        c.drawRoundRect(trackX0, trackY - trackH / 2f,
            thumbX, trackY + trackH / 2f, trackH / 2f, act);
        // Thumb.
        float thumbR = (y1 - y0) * 0.40f;
        PluginPaint thumb = c.newPaint();
        thumb.setColor(0xFFFFFFFF);
        c.drawCircle(thumbX, trackY, thumbR, thumb);
        PluginPaint thumbRim = c.newPaint();
        thumbRim.setColor(activeColor);
        thumbRim.setStyle(com.vocalmonitor.plugin.PluginStyle.STROKE);
        thumbRim.setStrokeWidth(Math.max(1.5f, 2.5f * scale));
        c.drawCircle(thumbX, trackY, thumbR, thumbRim);
    }
}
