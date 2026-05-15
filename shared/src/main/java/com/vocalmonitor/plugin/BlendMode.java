package com.vocalmonitor.plugin;

/**
 * Compositing blend mode applied by a {@link PluginPaint} when drawing
 * primitives. The names match the canonical Porter-Duff / Skia set;
 * Android's `BlendMode` enum is essentially the same. Hosts on other
 * platforms (PC DAW with Cairo / Direct2D) map these to their nearest
 * equivalents.
 *
 * Default is {@link #SRC_OVER} — normal alpha-over compositing.
 *
 * {@link #ADD}, {@link #SCREEN}, and {@link #COLOR_DODGE} are the
 * primary tools for "glow" effects on dark backgrounds.
 */
public enum BlendMode {
    SRC_OVER,
    ADD,
    SCREEN,
    MULTIPLY,
    OVERLAY,
    DARKEN,
    LIGHTEN,
    COLOR_DODGE,
    COLOR_BURN,
}
