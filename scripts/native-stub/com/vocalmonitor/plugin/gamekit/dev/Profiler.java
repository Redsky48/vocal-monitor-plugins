package com.vocalmonitor.plugin.gamekit.dev;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;

/**
 * Tiny render-loop profiler.  Wrap sections with
 * {@link #section}/{@link #end} pairs; pull averaged ms via
 * {@link #drawOverlay} for an on-screen readout.
 *
 *   private final Profiler prof = new Profiler();
 *
 *   render:
 *     prof.section("step");   stepWorld(dt, w, h);   prof.end();
 *     prof.section("draw");   drawScene(c, w, h);    prof.end();
 *     prof.section("particles"); particles.draw(c);  prof.end();
 *     prof.drawOverlay(c, w, h, scale);   // bottom-right tiny table
 *
 * Lock-free single-thread design; uses parallel fixed-size arrays
 * indexed by section name (linear lookup, fine for the ~10 sections
 * a plugin typically has).  Each section keeps a moving average
 * over the last `WINDOW` frames so the readout doesn't jitter.
 */
public final class Profiler {

    private static final int MAX_SECTIONS = 16;
    private static final int WINDOW = 30;        // ~0.5 s at 60 fps

    private final String[] names = new String[MAX_SECTIONS];
    private final long[] startNanos = new long[MAX_SECTIONS];
    private final float[] avgMs = new float[MAX_SECTIONS];
    private int count = 0;
    private int active = -1;

    private boolean enabled = true;

    public Profiler enabled(boolean e) { this.enabled = e; return this; }
    public boolean enabled()           { return enabled; }

    /** Start timing the named section.  Nesting is not supported —
     *  call {@link #end()} before opening another section. */
    public void section(String name) {
        if (!enabled) return;
        // Find existing slot or allocate a new one.
        int idx = -1;
        for (int i = 0; i < count; i++) {
            if (names[i].equals(name)) { idx = i; break; }
        }
        if (idx < 0) {
            if (count >= MAX_SECTIONS) return;   // silently drop
            idx = count++;
            names[idx] = name;
            avgMs[idx] = 0f;
        }
        active = idx;
        startNanos[idx] = System.nanoTime();
    }

    /** Close the current section. */
    public void end() {
        if (!enabled || active < 0) return;
        float ms = (System.nanoTime() - startNanos[active]) / 1_000_000f;
        // Exponential moving average over ~WINDOW frames.
        float a = 1f / WINDOW;
        avgMs[active] += a * (ms - avgMs[active]);
        active = -1;
    }

    /** Total averaged ms across all sections — should sit under ~16
     *  for a 60 fps frame budget. */
    public float totalMs() {
        float t = 0f;
        for (int i = 0; i < count; i++) t += avgMs[i];
        return t;
    }

    /** Draw a small, semi-transparent table of section ms in the
     *  bottom-right corner.  Stays out of the way; doesn't shake. */
    public void drawOverlay(PluginCanvas c, int width, int height, float scale) {
        if (!enabled || count == 0) return;
        float padX = 6f * scale, padY = 4f * scale;
        float lineH = 12f * scale;
        float w = 110f * scale;
        float totalH = lineH * (count + 1) + padY * 2;
        float x0 = width - w - padX;
        float y0 = height - totalH - padX;
        // Background.
        PluginPaint bg = c.newPaint();
        bg.setColor(0xCC101418);
        c.drawRoundRect(x0, y0, x0 + w, y0 + totalH, 4f * scale, bg);
        // Header.
        PluginPaint hdr = c.newPaint();
        hdr.setColor(totalMs() > 16f ? 0xFFE25656 : 0xFFCFCFCF);
        hdr.setTextSize(10f * scale);
        hdr.setTextAlign(0);
        c.drawText("frame " + fmt(totalMs()) + " ms",
            x0 + padX, y0 + padY + lineH, hdr);
        PluginPaint row = c.newPaint();
        row.setColor(0xFFAAAAAA);
        row.setTextSize(10f * scale);
        row.setTextAlign(0);
        for (int i = 0; i < count; i++) {
            c.drawText(names[i] + "  " + fmt(avgMs[i]),
                x0 + padX, y0 + padY + lineH * (i + 2), row);
        }
    }

    private static String fmt(float ms) {
        int whole = (int) ms;
        int tenths = Math.max(0, Math.min(9, (int) ((ms - whole) * 10f)));
        return whole + "." + tenths;
    }

    public void reset() {
        count = 0;
        active = -1;
    }
}
