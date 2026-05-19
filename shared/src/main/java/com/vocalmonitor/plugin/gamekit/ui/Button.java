package com.vocalmonitor.plugin.gamekit.ui;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.gamekit.Palette;

/**
 * Tappable rounded-rect button.  Stateful — holds a {@link HitZone}.
 * One instance per logical button; reuse across render frames so the
 * pressed-while-held visual works.
 *
 * Usage:
 *   private final Button startBtn = new Button();
 *
 *   onTouchDown:  startBtn.touchDown(x, y);
 *   onTouchMove:  startBtn.touchMove(x, y);
 *   onTouchUp:    startBtn.touchUp(x, y);
 *
 *   render:
 *     if (startBtn.draw(c, "Start", cx-60, cy-20, cx+60, cy+20,
 *                       Palette.ACCENT_YELLOW, scale)) {
 *         startGame();
 *     }
 *
 * `draw()` returns true once when the user finishes tapping inside
 * the button — convenient for the common "set bounds + check click"
 * pattern in render().
 */
public final class Button {

    public final HitZone hit = new HitZone();

    private int fill = Palette.ACCENT_YELLOW;
    private int textColor = Palette.UI_TEXT_INK;
    private int pressedTint = 0;       // 0 = auto-darken on press

    public Button fill(int color)        { this.fill = color; return this; }
    public Button textColor(int color)   { this.textColor = color; return this; }
    public Button pressedTint(int color) { this.pressedTint = color; return this; }

    public void touchDown(float x, float y) { hit.touchDown(x, y); }
    public void touchMove(float x, float y) { hit.touchMove(x, y); }
    public void touchUp(float x, float y)   { hit.touchUp(x, y); }
    public boolean pressed()                { return hit.pressed(); }

    /**
     * Draw the button at the given rect, return true if the user
     * finished tapping inside it this frame.  Bounds update happens
     * inside so callers don't need to call hit.bounds() separately.
     */
    public boolean draw(
        PluginCanvas c, String label,
        float x0, float y0, float x1, float y1,
        float scale
    ) {
        hit.bounds(x0, y0, x1, y1);
        float radius = (y1 - y0) * 0.30f;
        int bgColor = fill;
        if (hit.pressed()) {
            bgColor = pressedTint != 0 ? pressedTint : Palette.darken(fill, 0.20f);
        }
        // Shadow.
        PluginPaint shadow = c.newPaint();
        shadow.setColor(0x55000000);
        float off = hit.pressed() ? 1f * scale : 3f * scale;
        c.drawRoundRect(x0 + off, y0 + off, x1 + off, y1 + off, radius, shadow);
        // Fill + outline.
        PluginPaint p = c.newPaint();
        p.setColor(bgColor);
        c.drawRoundRect(x0, y0, x1, y1, radius, p);
        PluginPaint stroke = c.newPaint();
        stroke.setColor(Palette.UI_TEXT_INK);
        stroke.setStyle(PluginStyle.STROKE);
        stroke.setStrokeWidth(Math.max(1f, 2f * scale));
        c.drawRoundRect(x0, y0, x1, y1, radius, stroke);
        // Label.
        PluginPaint t = c.newPaint();
        t.setColor(textColor);
        t.setTextSize((y1 - y0) * 0.45f);
        t.setTextAlign(1);
        c.drawText(label, (x0 + x1) / 2f, (y0 + y1) / 2f + (y1 - y0) * 0.16f, t);
        return hit.clickedThisFrame();
    }
}
