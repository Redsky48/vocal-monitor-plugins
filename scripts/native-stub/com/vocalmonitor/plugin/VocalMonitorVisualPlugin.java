package com.vocalmonitor.plugin;

import java.util.Map;

/**
 * Optional UI contract that a native plugin implements when it wants to
 * own its own panel via custom drawing instead of (or in addition to) a
 * declarative `ui` spec in the manifest.
 *
 * The plugin class implements BOTH {@link VocalMonitorNativePlugin}
 * (audio) and this interface (visual). The host detects the latter via
 * `instanceof` at install time — no manifest flag required to opt in,
 * though plugins that want only a spec-based panel and no canvas code
 * MUST NOT implement this interface (the host's spec / canvas mode
 * switch is driven by the `ui_kind` manifest field).
 *
 * See `PLUGIN_UI_API.md` in the registry repo for the full picture —
 * frame budget, available streams, glow / gradient recipes, etc.
 */
public interface VocalMonitorVisualPlugin extends VocalMonitorNativePlugin {

    /**
     * Called once by the host before the first {@link #render} call,
     * handing the plugin a {@link PluginHost} it can use to push
     * parameter updates back (e.g. from a knob drawn inside the
     * canvas and dragged by the user). Default impl ignores it — a
     * plugin that only displays state and doesn't render its own
     * controls can leave this alone.
     */
    default void setHost(PluginHost host) { /* opt-in */ }

    /**
     * Touch went down inside the panel. Coordinates are in the same
     * dp-equivalent units that {@link #render} draws in, with the
     * origin at the panel's top-left. Default impl ignores it. Pair
     * with {@link #onTouchMove} and {@link #onTouchUp} to implement
     * interactive controls.
     *
     * The host commits parameter changes for undo on touch-up, so
     * everything between down → up is bundled as one gesture even
     * if the plugin called {@link PluginHost#setParameter} on every
     * move event.
     */
    default void onTouchDown(float x, float y) { /* opt-in */ }

    /** Touch moved while down. See {@link #onTouchDown}. */
    default void onTouchMove(float x, float y) { /* opt-in */ }

    /** Touch released. See {@link #onTouchDown}. */
    default void onTouchUp(float x, float y) { /* opt-in */ }

    /**
     * Called by the host once per UI frame (typically 60 Hz) on the UI
     * thread. Aim for under 4 ms per call; anything over 16 ms drops a
     * frame; over 50 ms triggers the host's watchdog, which replaces
     * the panel with the fallback spec view for the rest of the session.
     *
     * @param canvas  abstract canvas — see {@link PluginCanvas}. The
     *                host adapter handles the platform-specific mapping
     *                to Skia / Cairo / etc.
     * @param width   panel width in logical pixels (dp-equivalent).
     * @param height  panel height in the same units.
     * @param timeMs  monotonic milliseconds since the panel was opened.
     *                Use this for animations — it survives clock changes
     *                and is consistent across plugin reloads.
     * @param params  snapshot of every parameter the plugin declared,
     *                keyed by the same names {@link #parameterNames}
     *                returned. Values are the latest committed by the
     *                host between the previous and this frame.
     * @param streams data streams the plugin requested in its manifest
     *                (`peak`, `rms`, `gainReduction`, `fft`, `waveform`,
     *                or any custom stream the plugin exposes via
     *                `customStream(name)`). Each entry is a `float[]`;
     *                scalar streams come as length-1 arrays. The host
     *                may pass empty arrays when no audio has flowed
     *                yet — handle that gracefully.
     */
    void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    );
}
