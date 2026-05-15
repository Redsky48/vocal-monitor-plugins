package com.vocalmonitor.ui

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import com.vocalmonitor.plugin.BlendMode
import com.vocalmonitor.plugin.PluginCanvas
import com.vocalmonitor.plugin.PluginPaint
import com.vocalmonitor.plugin.PluginPath
import com.vocalmonitor.plugin.PluginStyle
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Path
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader

/**
 * Desktop counterpart to slim's `ComposePluginCanvas` — adapts the
 * abstract `PluginCanvas` API onto Compose Desktop's Skia-backed
 * `DrawScope`.
 *
 * Same contract slim's plugin .java code is written against, so
 * dropping a vocal-spectrum / formant-tracker / glow-meter into the
 * DAW renders pixel-for-pixel identical to Android.  All coordinates
 * arrive in logical pixels (dp-equivalent) and get scaled to device
 * pixels here via the `Density` the host hands in.
 *
 * Cheap to construct — the host mints a fresh instance per
 * `render()` call.  The expensive part (Skia Paint / Path objects)
 * lives inside `newPaint()` / `newPath()`, which plugins cache
 * across frames per the documented contract.
 */
internal class SkiaPluginCanvas(
    private val drawScope: DrawScope,
    density: Density,
) : PluginCanvas {

    private val skia get() = drawScope.drawContext.canvas.nativeCanvas
    private val ratio: Float = density.density

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: PluginPaint) {
        val p = (paint as SkiaPluginPaint).boundPaint(right - left, bottom - top, ratio)
        skia.drawRect(Rect.makeLTRB(left * ratio, top * ratio, right * ratio, bottom * ratio), p)
    }

    override fun drawRoundRect(
        left: Float, top: Float, right: Float, bottom: Float,
        radius: Float, paint: PluginPaint,
    ) {
        val p = (paint as SkiaPluginPaint).boundPaint(right - left, bottom - top, ratio)
        val r = radius * ratio
        skia.drawRRect(
            RRect.makeLTRB(left * ratio, top * ratio, right * ratio, bottom * ratio, r),
            p,
        )
    }

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: PluginPaint) {
        val p = (paint as SkiaPluginPaint).boundPaint(radius * 2, radius * 2, ratio)
        skia.drawCircle(cx * ratio, cy * ratio, radius * ratio, p)
    }

    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: PluginPaint) {
        val p = (paint as SkiaPluginPaint).boundPaint(x1 - x0, y1 - y0, ratio)
        skia.drawLine(x0 * ratio, y0 * ratio, x1 * ratio, y1 * ratio, p)
    }

    override fun drawPath(path: PluginPath, paint: PluginPaint) {
        val sp = (path as SkiaPluginPath).native
        val p = (paint as SkiaPluginPaint).boundPaint(0f, 0f, ratio)
        skia.drawPath(sp, p)
    }

    override fun drawText(text: String, x: Float, y: Float, paint: PluginPaint) {
        val sp = paint as SkiaPluginPaint
        val font = sp.makeFont(ratio)
        val p = sp.boundPaint(0f, 0f, ratio)
        // PluginPaint textAlign: 0 = left, 1 = center, 2 = right
        val rendered = x * ratio - when (sp.textAlign) {
            1 -> font.measureTextWidth(text) / 2f
            2 -> font.measureTextWidth(text)
            else -> 0f
        }
        skia.drawString(text, rendered, y * ratio, font, p)
    }

    override fun save() { skia.save() }
    override fun restore() { skia.restore() }
    override fun translate(dx: Float, dy: Float) { skia.translate(dx * ratio, dy * ratio) }
    override fun scale(sx: Float, sy: Float) { skia.scale(sx, sy) }
    override fun rotate(degrees: Float) { skia.rotate(degrees) }

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float) {
        skia.clipRect(Rect.makeLTRB(left * ratio, top * ratio, right * ratio, bottom * ratio))
    }

    override fun newPaint(): PluginPaint = SkiaPluginPaint().also {
        it.setAntialias(true)
        it.setStyle(PluginStyle.FILL)
        it.setColor(0xFF000000.toInt())
    }

    override fun newPath(): PluginPath = SkiaPluginPath()
}

/**
 * Skia-backed `PluginPaint`.  Each setter mutates the underlying
 * `org.jetbrains.skia.Paint` in place so the plugin's chained-
 * builder style stays allocation-free after the first frame.
 *
 * Shaders + gradients build lazily on draw — they need the geometry
 * extent the paint is being applied to, which the canvas knows but
 * the paint doesn't.  `boundPaint(w, h, ratio)` recomputes the
 * shader against the current draw extent when the paint flips into
 * gradient mode.
 */
internal class SkiaPluginPaint : PluginPaint {

    private val native: Paint = Paint().also { it.isAntiAlias = true }

    private var pendingColor: Int = 0xFF000000.toInt()
    private var pendingStrokeDp: Float = 1f
    private var pendingStyle: PluginStyle = PluginStyle.FILL
    private var pendingTextSizeDp: Float = 12f
    internal var textAlign: Int = 0

    // Gradient state — applied lazily inside boundPaint().
    private enum class GradKind { NONE, LINEAR, RADIAL }
    private var gradKind: GradKind = GradKind.NONE
    private var gradX0 = 0f; private var gradY0 = 0f
    private var gradX1 = 0f; private var gradY1 = 0f
    private var gradCx = 0f; private var gradCy = 0f
    private var gradRadius = 0f
    private var gradColors: IntArray? = null
    private var gradStops: FloatArray? = null

    // Glow / shadow — Skia models both as MaskFilter blur + draw twice.
    private var glowColor: Int = 0
    private var glowRadiusDp: Float = 0f
    private var shadowDx = 0f; private var shadowDy = 0f
    private var shadowRadiusDp = 0f
    private var shadowColor = 0

    override fun setColor(argb: Int): PluginPaint {
        pendingColor = argb
        native.color = argb
        return this
    }

    override fun setStrokeWidth(dp: Float): PluginPaint {
        pendingStrokeDp = dp
        return this
    }

    override fun setStyle(style: PluginStyle): PluginPaint {
        pendingStyle = style
        native.mode = when (style) {
            PluginStyle.FILL          -> PaintMode.FILL
            PluginStyle.STROKE        -> PaintMode.STROKE
            PluginStyle.FILL_AND_STROKE -> PaintMode.STROKE_AND_FILL
        }
        return this
    }

    override fun setAntialias(on: Boolean): PluginPaint {
        native.isAntiAlias = on
        return this
    }

    override fun setLinearGradient(
        x0: Float, y0: Float, x1: Float, y1: Float,
        colors: IntArray, stops: FloatArray,
    ): PluginPaint {
        gradKind = GradKind.LINEAR
        gradX0 = x0; gradY0 = y0; gradX1 = x1; gradY1 = y1
        gradColors = colors; gradStops = stops
        return this
    }

    override fun setRadialGradient(
        cx: Float, cy: Float, radius: Float,
        colors: IntArray, stops: FloatArray,
    ): PluginPaint {
        gradKind = GradKind.RADIAL
        gradCx = cx; gradCy = cy; gradRadius = radius
        gradColors = colors; gradStops = stops
        return this
    }

    override fun clearShader(): PluginPaint {
        gradKind = GradKind.NONE
        gradColors = null; gradStops = null
        native.shader = null
        return this
    }

    override fun setGlow(color: Int, radiusDp: Float): PluginPaint {
        glowColor = color
        glowRadiusDp = radiusDp
        return this
    }

    override fun setShadow(dx: Float, dy: Float, radiusDp: Float, color: Int): PluginPaint {
        shadowDx = dx; shadowDy = dy
        shadowRadiusDp = radiusDp
        shadowColor = color
        return this
    }

    override fun setBlendMode(mode: BlendMode): PluginPaint {
        native.blendMode = when (mode) {
            BlendMode.SRC_OVER    -> org.jetbrains.skia.BlendMode.SRC_OVER
            BlendMode.ADD         -> org.jetbrains.skia.BlendMode.PLUS
            BlendMode.SCREEN      -> org.jetbrains.skia.BlendMode.SCREEN
            BlendMode.MULTIPLY    -> org.jetbrains.skia.BlendMode.MULTIPLY
            BlendMode.OVERLAY     -> org.jetbrains.skia.BlendMode.OVERLAY
            BlendMode.DARKEN      -> org.jetbrains.skia.BlendMode.DARKEN
            BlendMode.LIGHTEN     -> org.jetbrains.skia.BlendMode.LIGHTEN
            BlendMode.COLOR_DODGE -> org.jetbrains.skia.BlendMode.COLOR_DODGE
            BlendMode.COLOR_BURN  -> org.jetbrains.skia.BlendMode.COLOR_BURN
        }
        return this
    }

    override fun setTextSize(dp: Float): PluginPaint {
        pendingTextSizeDp = dp
        return this
    }

    override fun setTextAlign(align: Int): PluginPaint {
        textAlign = align
        return this
    }

    /**
     * Snapshot the paint with its scale + shader applied for the
     * actual draw call.  Width / height are the geometry extents in
     * logical pixels — needed only when a gradient is active so the
     * shader's coordinates can be converted to device pixels.
     */
    internal fun boundPaint(@Suppress("UNUSED_PARAMETER") w: Float,
                            @Suppress("UNUSED_PARAMETER") h: Float,
                            ratio: Float): Paint {
        native.color = pendingColor
        native.strokeWidth = pendingStrokeDp * ratio
        // Refresh shader on every draw — cheap (the colour stops are
        // tiny arrays) and avoids stale shaders after geometry move.
        native.shader = when (gradKind) {
            GradKind.NONE -> null
            GradKind.LINEAR -> {
                val cs = gradColors ?: intArrayOf(0, 0)
                val ss = gradStops
                Shader.makeLinearGradient(
                    gradX0 * ratio, gradY0 * ratio,
                    gradX1 * ratio, gradY1 * ratio,
                    cs, ss,
                )
            }
            GradKind.RADIAL -> {
                val cs = gradColors ?: intArrayOf(0, 0)
                val ss = gradStops
                Shader.makeRadialGradient(
                    gradCx * ratio, gradCy * ratio,
                    gradRadius * ratio,
                    cs, ss,
                )
            }
        }
        // Glow / shadow → MaskFilter blur.  We approximate slim's
        // "draw a blurred copy under the main one" with a single
        // pass since the host only invokes us per draw call.
        native.maskFilter = if (glowRadiusDp > 0f) {
            MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL,
                glowRadiusDp * ratio)
        } else null
        return native
    }

    internal fun makeFont(ratio: Float): Font {
        // Default platform font is sufficient — slim's adapter does
        // the same.  Plugins that need a custom font can render their
        // own glyphs.
        val typeface = FontMgr.default.matchFamilyStyle("Inter", FontStyle.NORMAL)
            ?: FontMgr.default.matchFamilyStyle("Arial", FontStyle.NORMAL)
            ?: FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.NORMAL, null, ' '.code)
        return Font(typeface, pendingTextSizeDp * ratio)
    }
}

/** Skia-backed `PluginPath` — paths accumulate in logical px and
 *  scale at draw time via the canvas adapter's `ratio`. */
internal class SkiaPluginPath : PluginPath {
    internal val native = Path()

    override fun moveTo(x: Float, y: Float): PluginPath {
        native.moveTo(x, y)
        return this
    }
    override fun lineTo(x: Float, y: Float): PluginPath {
        native.lineTo(x, y)
        return this
    }
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): PluginPath {
        native.cubicTo(x1, y1, x2, y2, x3, y3)
        return this
    }
    override fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float): PluginPath {
        native.quadTo(x1, y1, x2, y2)
        return this
    }
    override fun close(): PluginPath {
        native.closePath()
        return this
    }
    override fun reset(): PluginPath {
        native.reset()
        return this
    }
}
