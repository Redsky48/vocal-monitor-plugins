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
    /** Pop the most recent {@link #save} or {@link #saveLayer}. */
    void restore();

    /**
     * Push an offscreen drawing layer onto the stack. Subsequent draw
     * calls land on a fresh transparent surface bounded by the rect
     * (left, top, right, bottom). When the matching {@link #restore}
     * fires, the layer is composited back onto the parent canvas
     * using {@code paint}'s blend mode + alpha — so blend modes that
     * need an isolated source (SCREEN, MULTIPLY, OVERLAY…) and per-
     * layer opacity work the same way Canvas2D's offscreen-canvas-
     * then-globalCompositeOperation pattern does in the JS engine.
     *
     * {@code paint} MAY be null — equivalent to a paint with default
     * SRC_OVER blend and full alpha. Backwards-compatible default:
     * older hosts that haven't implemented this method degrade to a
     * plain {@link #save}, which still gets transforms / clips right
     * even if blend / opacity composition is lost.
     */
    default void saveLayer(
        float left, float top, float right, float bottom,
        PluginPaint paint
    ) {
        save();
    }

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

    // ─── Persistent offscreen bitmaps ─────────────────────────────────

    /**
     * Get or create a persistent offscreen bitmap keyed by {@code key}.
     * The host caches the backing surface across frames; same key +
     * matching size returns the same buffer, so the plugin can read
     * the previous frame's pixels (e.g. for the JS engine's Fade
     * Trail effect — paint a partial-alpha rect over the bitmap,
     * then draw the new frame on top, then composite back).
     *
     * Default impl returns {@code null} — older hosts don't support
     * persistent bitmaps. Plugins MUST null-check and fall back to
     * direct rendering so they stay forward-compatible.
     */
    default PluginBitmap acquireBitmap(String key, float width, float height) {
        return null;
    }

    /**
     * Composite a previously-acquired {@link PluginBitmap} onto the
     * current canvas at (0, 0). The {@code paint}'s blend mode +
     * color alpha control the composition — pass a paint with the
     * layer's fxBlend + fxOpacity to mirror the JS engine's
     * {@code globalCompositeOperation} + {@code globalAlpha} on
     * the offscreen-canvas drawImage call. Default impl is a no-op
     * on older hosts.
     */
    default void drawBitmap(PluginBitmap bitmap, PluginPaint paint) {
        // older hosts don't support this — silently ignore.
    }
}
