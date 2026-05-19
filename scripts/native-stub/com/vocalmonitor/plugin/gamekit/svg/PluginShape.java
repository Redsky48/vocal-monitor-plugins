package com.vocalmonitor.plugin.gamekit.svg;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;

/**
 * A parsed-once-drawn-many SVG image.  Holds intermediate path
 * geometry as compact float arrays (no PluginPath / PluginPaint
 * yet) and materializes them onto the host's canvas the first time
 * {@link #draw} is called.  After that, draws are just a few
 * {@code canvas.drawPath} calls per layer.
 *
 * Constructed by {@link Svg#parse(String)}.  Immutable from the
 * outside — the only mutating method is the internal cache fill
 * inside {@link #draw}, which is single-thread by design (canvases
 * are not thread-safe anyway).
 *
 * Draw API:
 *   shape.draw(canvas, x, y, scale)
 *
 * The shape's own coordinate system is its SVG viewBox; the
 * {@code x, y, scale} arguments place + size it in the host canvas.
 * `scale = 1` renders at viewBox-native pixels; pass
 * `min(width, height) / shape.viewBoxHeight()` to fit the shape
 * to a target box.
 */
public final class PluginShape {

    // ── Layer storage (parsed from SVG) ─────────────────────
    // Per layer: one packed command stream + one fill colour + one
    // stroke colour + one stroke width.  Either colour may be 0
    // (transparent) which suppresses that draw.
    final int[]    cmdTypes;       // op codes, one per command, MOVE / LINE / CUBIC / QUAD / CLOSE
    final int[]    cmdOffsets;     // index into coords[] where each command's args begin
    final float[]  coords;         // flat XY pairs (each command consumes its known count)
    final int[]    layerStarts;    // first cmd index per layer
    final int[]    layerEnds;      // last cmd index + 1 per layer
    final int[]    layerFills;     // ARGB; 0 = no fill
    final int[]    layerStrokes;   // ARGB; 0 = no stroke
    final float[]  layerStrokeWs;  // stroke widths (viewBox units)
    final float vbX, vbY, vbW, vbH;

    // ── Lazy materialisation cache ──────────────────────────
    private PluginCanvas cachedCanvas = null;
    private PluginPath[] cachedPaths = null;

    // Op codes — package-private so Svg can write them.
    static final int OP_MOVE  = 0;
    static final int OP_LINE  = 1;
    static final int OP_CUBIC = 2;
    static final int OP_QUAD  = 3;
    static final int OP_CLOSE = 4;

    PluginShape(
        int[] cmdTypes, int[] cmdOffsets, float[] coords,
        int[] layerStarts, int[] layerEnds,
        int[] layerFills, int[] layerStrokes, float[] layerStrokeWs,
        float vbX, float vbY, float vbW, float vbH
    ) {
        this.cmdTypes = cmdTypes;
        this.cmdOffsets = cmdOffsets;
        this.coords = coords;
        this.layerStarts = layerStarts;
        this.layerEnds = layerEnds;
        this.layerFills = layerFills;
        this.layerStrokes = layerStrokes;
        this.layerStrokeWs = layerStrokeWs;
        this.vbX = vbX; this.vbY = vbY; this.vbW = vbW; this.vbH = vbH;
    }

    /** Did the parser produce any drawable geometry?  Empty shape
     *  draws to nothing — useful for graceful fallback. */
    public boolean isEmpty() { return layerStarts.length == 0; }

    /** SVG viewBox width.  Use to pick a {@code scale} when fitting
     *  the shape to a target rect. */
    public float viewBoxWidth()  { return vbW; }
    public float viewBoxHeight() { return vbH; }

    /**
     * Draw the shape at (x, y) scaled by `scale`.  The top-left of
     * the SVG viewBox lands at (x, y) and one viewBox unit becomes
     * `scale` host-canvas units.  If you want to render at a target
     * size of `W × H`, pass scale = min(W / vbW, H / vbH).
     */
    public void draw(PluginCanvas canvas, float x, float y, float scale) {
        drawAnisotropic(canvas, x, y, scale, scale);
    }

    /** Draw with independent X / Y scale — for stretching a shape
     *  non-uniformly.  Most callers want {@link #draw}. */
    public void drawAnisotropic(
        PluginCanvas canvas,
        float x, float y, float scaleX, float scaleY
    ) {
        if (isEmpty()) return;
        materialise(canvas);
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(scaleX, scaleY);
        canvas.translate(-vbX, -vbY);
        // Per-layer fills + strokes.
        for (int i = 0; i < layerStarts.length; i++) {
            int fill = layerFills[i];
            int stroke = layerStrokes[i];
            if ((fill >>> 24) != 0) {
                PluginPaint p = canvas.newPaint();
                p.setColor(fill);
                canvas.drawPath(cachedPaths[i], p);
            }
            if ((stroke >>> 24) != 0 && layerStrokeWs[i] > 0f) {
                PluginPaint s = canvas.newPaint();
                s.setColor(stroke);
                s.setStyle(PluginStyle.STROKE);
                s.setStrokeWidth(layerStrokeWs[i]);
                canvas.drawPath(cachedPaths[i], s);
            }
        }
        canvas.restore();
    }

    /** Draw with every layer's fill colour replaced by `tint`.
     *  Useful for "icon recoloring" — load one neutral SVG, render
     *  in many colours.  Stroke colour is unchanged. */
    public void drawTinted(
        PluginCanvas canvas, float x, float y, float scale, int tint
    ) {
        if (isEmpty()) return;
        materialise(canvas);
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(scale, scale);
        canvas.translate(-vbX, -vbY);
        for (int i = 0; i < layerStarts.length; i++) {
            int fill = layerFills[i];
            int stroke = layerStrokes[i];
            if ((fill >>> 24) != 0) {
                PluginPaint p = canvas.newPaint();
                p.setColor(tint);
                canvas.drawPath(cachedPaths[i], p);
            }
            if ((stroke >>> 24) != 0 && layerStrokeWs[i] > 0f) {
                PluginPaint s = canvas.newPaint();
                s.setColor(stroke);
                s.setStyle(PluginStyle.STROKE);
                s.setStrokeWidth(layerStrokeWs[i]);
                canvas.drawPath(cachedPaths[i], s);
            }
        }
        canvas.restore();
    }

    private void materialise(PluginCanvas canvas) {
        if (cachedCanvas == canvas && cachedPaths != null) return;
        int n = layerStarts.length;
        PluginPath[] out = new PluginPath[n];
        for (int li = 0; li < n; li++) {
            PluginPath path = canvas.newPath();
            int start = layerStarts[li];
            int end = layerEnds[li];
            for (int ci = start; ci < end; ci++) {
                int op = cmdTypes[ci];
                int off = cmdOffsets[ci];
                switch (op) {
                    case OP_MOVE:
                        path.moveTo(coords[off], coords[off + 1]);
                        break;
                    case OP_LINE:
                        path.lineTo(coords[off], coords[off + 1]);
                        break;
                    case OP_CUBIC:
                        path.cubicTo(
                            coords[off],     coords[off + 1],
                            coords[off + 2], coords[off + 3],
                            coords[off + 4], coords[off + 5]
                        );
                        break;
                    case OP_QUAD:
                        path.quadTo(
                            coords[off],     coords[off + 1],
                            coords[off + 2], coords[off + 3]
                        );
                        break;
                    case OP_CLOSE:
                        path.close();
                        break;
                }
            }
            out[li] = path;
        }
        cachedPaths = out;
        cachedCanvas = canvas;
    }
}
