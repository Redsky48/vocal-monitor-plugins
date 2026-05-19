package com.vocalmonitor.plugin.gamekit;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginStyle;

/**
 * Stateless drawing helpers built on top of {@link PluginCanvas}.
 *
 * Each method allocates one or two {@link PluginPaint}s — fine for
 * one-shot HUD draws, but if you find yourself calling these inside
 * a tight per-particle loop, cache the paint yourself.
 *
 * Naming: methods that produce a single shape end in `panel`,
 * `pill`, `circle`, …; methods that produce composite UI (card with
 * shadow + text) are named for the resulting widget (`textCenter`,
 * `gradientSky`, `card`).
 */
public final class Gfx {
    private Gfx() {}

    // ── Backgrounds ──────────────────────────────────────────
    /** Two-stop vertical gradient covering the whole canvas. */
    public static void gradientSky(
        PluginCanvas c, int width, int height,
        int topColor, int bottomColor
    ) {
        PluginPaint p = c.newPaint();
        p.setLinearGradient(0, 0, 0, height,
            new int[] { topColor, bottomColor },
            new float[] { 0f, 1f });
        c.drawRect(0, 0, width, height, p);
    }

    /** Three-stop gradient (top → mid → bottom). */
    public static void gradient3(
        PluginCanvas c, int width, int height,
        int top, int mid, int bottom
    ) {
        PluginPaint p = c.newPaint();
        p.setLinearGradient(0, 0, 0, height,
            new int[] { top, mid, bottom },
            new float[] { 0f, 0.55f, 1f });
        c.drawRect(0, 0, width, height, p);
    }

    /** Flat fill. */
    public static void clear(PluginCanvas c, int width, int height, int color) {
        PluginPaint p = c.newPaint();
        p.setColor(color);
        c.drawRect(0, 0, width, height, p);
    }

    // ── Panels & cards ──────────────────────────────────────
    /** Rounded panel: fill + outline.  Stroke colour 0 = no outline. */
    public static void roundPanel(
        PluginCanvas c,
        float x0, float y0, float x1, float y1,
        float radius, int fill, int stroke, float strokeWidth
    ) {
        PluginPaint p = c.newPaint();
        p.setColor(fill);
        c.drawRoundRect(x0, y0, x1, y1, radius, p);
        if (stroke != 0 && strokeWidth > 0f) {
            PluginPaint s = c.newPaint();
            s.setColor(stroke);
            s.setStyle(PluginStyle.STROKE);
            s.setStrokeWidth(strokeWidth);
            c.drawRoundRect(x0, y0, x1, y1, radius, s);
        }
    }

    /** Drop a soft shadow behind the next thing you'll draw at the
     *  same rect.  Cheap fake: a slightly-larger dark round-rect with
     *  a low-alpha colour.  Call BEFORE the foreground draw. */
    public static void softShadow(
        PluginCanvas c,
        float x0, float y0, float x1, float y1,
        float radius, float offsetX, float offsetY, int color
    ) {
        PluginPaint p = c.newPaint();
        p.setColor(color);
        c.drawRoundRect(x0 + offsetX, y0 + offsetY, x1 + offsetX, y1 + offsetY, radius, p);
    }

    /** White card with ink border + drop shadow — the standard
     *  "ready / game over" overlay panel. */
    public static void card(
        PluginCanvas c,
        float x0, float y0, float x1, float y1,
        float radius, float strokeWidth
    ) {
        softShadow(c, x0, y0, x1, y1, radius, 4f, 4f, 0x77000000);
        roundPanel(c, x0, y0, x1, y1, radius,
            Palette.UI_BG_CARD, Palette.UI_TEXT_INK, strokeWidth);
    }

    /** Pill-shaped chip (text on rounded-rect).  Sized to text via
     *  caller-supplied width / height — no measureText API yet. */
    public static void pill(
        PluginCanvas c,
        float cx, float cy, float halfW, float halfH,
        String text, int textSize, int fill, int textColor
    ) {
        roundPanel(c, cx - halfW, cy - halfH, cx + halfW, cy + halfH, halfH, fill, 0, 0f);
        textCenter(c, text, cx, cy + textSize * 0.35f, textSize, textColor);
    }

    // ── Shapes ──────────────────────────────────────────────
    /** Filled circle with optional outline.  stroke == 0 → no outline. */
    public static void strokeCircle(
        PluginCanvas c, float cx, float cy, float r,
        int fill, int stroke, float strokeWidth
    ) {
        if ((fill >>> 24) != 0) {
            PluginPaint p = c.newPaint();
            p.setColor(fill);
            c.drawCircle(cx, cy, r, p);
        }
        if (stroke != 0 && strokeWidth > 0f) {
            PluginPaint s = c.newPaint();
            s.setColor(stroke);
            s.setStyle(PluginStyle.STROKE);
            s.setStrokeWidth(strokeWidth);
            c.drawCircle(cx, cy, r, s);
        }
    }

    /** Filled ring (annulus): outer outline + inner cutout.  Quicker
     *  than two strokeCircle calls because there's no fill paint. */
    public static void ring(PluginCanvas c, float cx, float cy, float r,
                            float strokeWidth, int color) {
        PluginPaint p = c.newPaint();
        p.setColor(color);
        p.setStyle(PluginStyle.STROKE);
        p.setStrokeWidth(strokeWidth);
        c.drawCircle(cx, cy, r, p);
    }

    // ── Text ────────────────────────────────────────────────
    /** Centred text at (cx, cy) — y is interpreted as text baseline.
     *  Use for HUD readouts; the host's `drawText` uses the same
     *  centring rules. */
    public static void textCenter(
        PluginCanvas c, String text, float cx, float cy,
        float size, int color
    ) {
        PluginPaint t = c.newPaint();
        t.setColor(color);
        t.setTextSize(size);
        t.setTextAlign(1);
        c.drawText(text, cx, cy, t);
    }

    /** Left-anchored text. */
    public static void textLeft(
        PluginCanvas c, String text, float x, float y,
        float size, int color
    ) {
        PluginPaint t = c.newPaint();
        t.setColor(color);
        t.setTextSize(size);
        t.setTextAlign(0);
        c.drawText(text, x, y, t);
    }

    /** Right-anchored text. */
    public static void textRight(
        PluginCanvas c, String text, float x, float y,
        float size, int color
    ) {
        PluginPaint t = c.newPaint();
        t.setColor(color);
        t.setTextSize(size);
        t.setTextAlign(2);
        c.drawText(text, x, y, t);
    }

    // ── Meters / level bars ─────────────────────────────────
    /** Horizontal level bar: background + filled portion.  Level is
     *  clamped to [0, 1].  Fill color picked from {@link Palette#meterColor}. */
    public static void levelBar(
        PluginCanvas c,
        float x0, float y0, float x1, float y1,
        float level01
    ) {
        float l = Ease.clamp(level01, 0f, 1f);
        float radius = (y1 - y0) * 0.5f;
        PluginPaint bg = c.newPaint();
        bg.setColor(Palette.UI_BORDER);
        c.drawRoundRect(x0, y0, x1, y1, radius, bg);
        if (l > 0f) {
            PluginPaint fg = c.newPaint();
            fg.setColor(Palette.meterColor(l));
            c.drawRoundRect(x0, y0, x0 + (x1 - x0) * l, y1, radius, fg);
        }
    }
}
