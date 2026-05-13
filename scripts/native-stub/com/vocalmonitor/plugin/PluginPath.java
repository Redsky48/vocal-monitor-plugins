package com.vocalmonitor.plugin;

/**
 * Mutable path of straight segments and Bezier curves drawable through
 * {@link PluginCanvas#drawPath(PluginPath, PluginPaint)}. Mirrors the
 * subset of Android `Path` / Skia `SkPath` that plugins need.
 *
 * Instances come from {@link PluginCanvas#newPath()} and should be
 * cached across frames; call {@link #reset()} between uses instead of
 * allocating a fresh one each tick.
 */
public interface PluginPath {

    /** Begin a new sub-path at (x, y). */
    PluginPath moveTo(float x, float y);

    /** Straight line from the current point to (x, y). */
    PluginPath lineTo(float x, float y);

    /** Quadratic Bezier through control (cx,cy) ending at (x,y). */
    PluginPath quadTo(float cx, float cy, float x, float y);

    /** Cubic Bezier through (c1x,c1y) + (c2x,c2y) ending at (x,y). */
    PluginPath cubicTo(
        float c1x, float c1y,
        float c2x, float c2y,
        float x,   float y
    );

    /** Close the current sub-path with a line back to its start. */
    PluginPath close();

    /** Clear every command — ready to be re-built for the next frame. */
    PluginPath reset();
}
