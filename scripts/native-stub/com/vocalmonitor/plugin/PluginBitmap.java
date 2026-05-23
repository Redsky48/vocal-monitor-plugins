package com.vocalmonitor.plugin;

/**
 * Persistent offscreen drawing surface handed out by
 * {@link PluginCanvas#acquireBitmap}. The same surface is returned
 * across frames for the same {@code key} (and matching size), so
 * pixels survive between {@link PluginCanvas#render} calls — the
 * primitive Fade Trail (the JS engine's `fxFade`) needs to read
 * the previous frame's contents and bleed them through a partial
 * alpha wipe.
 *
 * Typical use:
 * <pre>
 *   PluginBitmap bmp = canvas.acquireBitmap("layer_" + i, W, H);
 *   if (bmp != null) {
 *       bmp.fadeWipe(0.15f);              // dim previous frame
 *       renderInto(bmp.canvas(), …);     // paint new content
 *       canvas.drawBitmap(bmp, paint);   // composite back with blend
 *   }
 * </pre>
 *
 * Hosts older than the API extension return {@code null} from
 * {@link PluginCanvas#acquireBitmap}, so the plugin should always
 * null-check and fall back to direct rendering for compatibility.
 */
public interface PluginBitmap {

    /**
     * A {@link PluginCanvas} that draws into this bitmap. Same
     * coordinate space + density as the parent canvas — pass it
     * straight to the plugin's own paint helpers. Calls remain
     * valid until the next call to {@code acquireBitmap} on the
     * parent canvas for the same key (i.e. for the duration of the
     * current frame).
     */
    PluginCanvas canvas();

    /** Wipe the surface to fully transparent. */
    void clear();

    /**
     * Partial-alpha wipe — paints {@code rgba(0,0,0,alpha)} over
     * the entire surface so previous-frame pixels fade rather than
     * vanish. {@code alpha} in [0, 1]: 0 = no wipe (full trail
     * preservation, eventually overdraws onto a fully opaque
     * accumulator), 1 = full opaque black (no trail). The JS engine
     * uses {@code (1 - fxFade/100) * 0.95 + 0.05} so the lowest
     * non-zero fxFade still wipes at least 5%.
     */
    void fadeWipe(float alpha);
}
