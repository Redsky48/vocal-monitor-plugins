package com.vocalmonitor.plugin;

/**
 * Abstract canvas handed to {@link VocalMonitorVisualPlugin#render} so
 * plugins can draw arbitrary visuals without ever importing
 * `android.graphics.Canvas` or any other platform type.
 *
 * The host installs an adapter that translates each call into the
 * native draw primitive of whatever platform the plugin is running on
 * (Skia via Compose `DrawScope` on Android; the same Skia via Compose
 * Multiplatform on the planned PC DAW). The same `.dex` runs on both
 * — no platform `#ifdef`s, no recompile.
 *
 * Coordinates are in **logical pixels** (dp-equivalent). The host
 * applies device-pixel-ratio scaling internally; plugin code can assume
 * `width` and `height` from {@link VocalMonitorVisualPlugin#render} are
 * the same units it draws in.
 *
 * Calls SHOULD reuse {@link PluginPaint} / {@link PluginPath} instances
 * across frames — those wrap native objects that cost a heap allocation
 * per construction.
 */
public interface PluginCanvas {

    // ─── Shapes ───────────────────────────────────────────────────────

    void drawRect(
        float left, float top, float right, float bottom,
        PluginPaint paint
    );

    void drawRoundRect(
        float left, float top, float right, float bottom,
        float radius,
        PluginPaint paint
    );

    void drawCircle(float cx, float cy, float radius, PluginPaint paint);

    void drawLine(
        float x0, float y0,
        float x1, float y1,
        PluginPaint paint
    );

    void drawPath(PluginPath path, PluginPaint paint);

    /** Anchor is the baseline of the first glyph, at (x, y). */
    void drawText(String text, float x, float y, PluginPaint paint);

    // ─── Transform stack ──────────────────────────────────────────────

    /** Push the current transform + clip onto the stack. */
    void save();
    /** Pop the most recent {@link #save}. */
    void restore();

    void translate(float dx, float dy);
    void scale(float sx, float sy);
    /** Counter-clockwise rotation in degrees around the origin. */
    void rotate(float degrees);

    // ─── Clipping ─────────────────────────────────────────────────────

    /** Intersect the current clip with this axis-aligned rect. */
    void clipRect(float left, float top, float right, float bottom);

    // ─── Factories ────────────────────────────────────────────────────

    /** A fresh, empty path. Reset and reuse — don't allocate each frame. */
    PluginPath newPath();

    /** A fresh paint with defaults (color black, FILL, antialias on). */
    PluginPaint newPaint();
}
