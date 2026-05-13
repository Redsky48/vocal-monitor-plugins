package com.vocalmonitor.plugin;

/**
 * Drawing parameters handed to {@link PluginCanvas} primitives. Mirrors
 * the subset of Android `Paint` / Skia `SkPaint` that plugin authors
 * actually need for visual effects, plus convenience helpers for the
 * common "glow" pattern (BlurMaskFilter applied to the same paint).
 *
 * Instances are created via {@link PluginCanvas#newPaint()} and SHOULD
 * be cached across frames — the host adapter may wrap a native Skia
 * object that costs a heap allocation per construction.
 *
 * All setters return `this` so calls can chain:
 * <pre>
 *     PluginPaint p = canvas.newPaint()
 *         .setColor(0xFFFFD34A)
 *         .setStyle(PluginStyle.STROKE)
 *         .setStrokeWidth(2f)
 *         .setGlow(0xFFFFD34A, 12f);
 * </pre>
 */
public interface PluginPaint {

    /** Solid color in 0xAARRGGBB. Alpha 0 fully transparent. */
    PluginPaint setColor(int argb);

    /** Stroke width in logical pixels (dp-equivalent on Android). */
    PluginPaint setStrokeWidth(float dp);

    /** {@link PluginStyle#FILL} (default), {@link PluginStyle#STROKE}, etc. */
    PluginPaint setStyle(PluginStyle style);

    /** Antialiasing on (default true) or off. */
    PluginPaint setAntialias(boolean on);

    // ─── Shaders / gradients ──────────────────────────────────────────

    /**
     * Linear gradient between (x0,y0) and (x1,y1). `colors` and `stops`
     * must be the same length, with stops monotonically increasing in
     * [0, 1]. The host stretches the shader to the geometry being drawn.
     */
    PluginPaint setLinearGradient(
        float x0, float y0, float x1, float y1,
        int[] colors, float[] stops
    );

    /**
     * Radial gradient centred at (cx,cy) with [radius]. Same `colors`
     * + `stops` semantics as the linear variant.
     */
    PluginPaint setRadialGradient(
        float cx, float cy, float radius,
        int[] colors, float[] stops
    );

    /** Remove any shader previously set — back to a solid color fill. */
    PluginPaint clearShader();

    // ─── Effects ──────────────────────────────────────────────────────

    /**
     * Soft outer glow: paint draws as usual, plus a blurred copy of
     * itself in [color] underneath. Radius is the blur half-width in
     * logical pixels. Set to 0 to clear a previous glow.
     */
    PluginPaint setGlow(int color, float radiusDp);

    /**
     * Drop shadow at (dx,dy) offset from the painted shape, blurred by
     * [radiusDp] logical pixels. Color includes alpha.
     */
    PluginPaint setShadow(float dx, float dy, float radiusDp, int color);

    /** Override the compositing mode. Default is {@link BlendMode#SRC_OVER}. */
    PluginPaint setBlendMode(BlendMode mode);

    // ─── Text ─────────────────────────────────────────────────────────

    /** Text size in logical pixels for {@link PluginCanvas#drawText}. */
    PluginPaint setTextSize(float dp);

    /**
     * 0 = left-anchor at (x,y), 1 = center, 2 = right. Matches
     * Android `Paint.Align`.
     */
    PluginPaint setTextAlign(int alignment);
}
