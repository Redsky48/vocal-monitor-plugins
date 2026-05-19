package com.vocalmonitor.plugin.gamekit.ui;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.gamekit.Palette;

/**
 * Pill-shaped on/off switch.  Holds its own boolean state; flips
 * on a click inside its bounds.  Caller queries {@link #value} each
 * frame to read the current state.
 *
 *   private final Toggle muteSwitch = new Toggle().value(false);
 *
 *   onTouchDown: muteSwitch.touchDown(x, y);
 *   onTouchUp:   muteSwitch.touchUp(x, y);
 *
 *   render: muteSwitch.draw(c, x0, y0, x1, y1, scale);
 *           if (muteSwitch.value()) ...
 */
public final class Toggle {

    public final HitZone hit = new HitZone();
    private boolean on = false;

    private int onColor = Palette.ACCENT_GREEN;
    private int offColor = 0xFF555566;

    public Toggle value(boolean v)    { this.on = v; return this; }
    public boolean value()            { return on; }

    public Toggle onColor(int c)      { this.onColor = c; return this; }
    public Toggle offColor(int c)     { this.offColor = c; return this; }

    public void touchDown(float x, float y) { hit.touchDown(x, y); }
    public void touchMove(float x, float y) { hit.touchMove(x, y); }
    public void touchUp(float x, float y)   { hit.touchUp(x, y); }

    /** Returns true on the frame the toggle FLIPPED (rather than the
     *  current value).  Use this to react once on each change. */
    public boolean draw(
        PluginCanvas c,
        float x0, float y0, float x1, float y1,
        float scale
    ) {
        hit.bounds(x0, y0, x1, y1);
        boolean clicked = hit.clickedThisFrame();
        if (clicked) on = !on;

        float h = y1 - y0;
        float radius = h * 0.5f;
        // Track.
        PluginPaint track = c.newPaint();
        track.setColor(on ? onColor : offColor);
        c.drawRoundRect(x0, y0, x1, y1, radius, track);
        // Knob.
        float knobR = h * 0.45f;
        float knobCx = on ? (x1 - knobR - h * 0.10f) : (x0 + knobR + h * 0.10f);
        float knobCy = (y0 + y1) / 2f;
        PluginPaint shadow = c.newPaint();
        shadow.setColor(0x44000000);
        c.drawCircle(knobCx + 1f * scale, knobCy + 1f * scale, knobR, shadow);
        PluginPaint knob = c.newPaint();
        knob.setColor(0xFFFFFFFF);
        c.drawCircle(knobCx, knobCy, knobR, knob);
        return clicked;
    }
}
