package com.vocalmonitor.plugin.gamekit.ui;

/**
 * Headless tap-region — tracks down / up events against an
 * axis-aligned bounding rect and exposes:
 *
 *   pressed()           — true while a finger is down inside the rect
 *   clickedThisFrame()  — set true once when the finger goes up
 *                         AGAIN inside the rect (i.e. real click,
 *                         not just a drag that started here)
 *
 * The rect can be updated every render frame (HUD that moves with
 * the layout); call {@link #bounds} before {@link #touchDown}.
 *
 * Designed to be composed inside higher-level widgets — Button,
 * Toggle, Slider track their own HitZone for "did the user start
 * the gesture here".
 */
public final class HitZone {

    private float x0, y0, x1, y1;
    private boolean pressed = false;
    private boolean clicked = false;

    /** Update the bounds in-place — call from render before
     *  forwarding touch events. */
    public HitZone bounds(float x0, float y0, float x1, float y1) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        return this;
    }

    public boolean contains(float x, float y) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }

    /** Returns true if the touch landed inside the zone — caller may
     *  consume the event so other zones don't also see it. */
    public boolean touchDown(float x, float y) {
        if (contains(x, y)) { pressed = true; return true; }
        return false;
    }

    public void touchMove(float x, float y) {
        if (!pressed) return;
        // Drift outside cancels the press — same convention as a
        // standard button.
        if (!contains(x, y)) pressed = false;
    }

    public void touchUp(float x, float y) {
        if (pressed && contains(x, y)) clicked = true;
        pressed = false;
    }

    public boolean pressed() { return pressed; }

    /** Returns true once per click; reads-and-clears so repeated
     *  polls within a frame don't double-count. */
    public boolean clickedThisFrame() {
        if (clicked) { clicked = false; return true; }
        return false;
    }

    public void reset() { pressed = false; clicked = false; }
}
