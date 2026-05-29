package com.vocalmonitor.plugin;

/**
 * Callback into the host so a canvas-mode plugin can push parameter
 * updates back from its own knobs / sliders drawn inside the panel.
 *
 * The host hands one of these to the plugin via
 * {@link VocalMonitorVisualPlugin#setHost(PluginHost)} once, before the
 * first {@code render()} call. The plugin holds the reference and calls
 * {@link #setParameter} during touch handling — typically from
 * {@link VocalMonitorVisualPlugin#onTouchMove(float, float)} as the
 * user drags a control.
 *
 * Coalescing: every {@code setParameter} call updates the host's
 * live state immediately so the audio engine sees the new value on
 * the next block. The host automatically commits the change (for
 * undo / preset capture) on touch-up, so the plugin doesn't need to
 * track "drag in progress" itself — call {@code setParameter} as
 * often as you like during a drag, the host bundles it into one
 * undo entry per gesture.
 */
public interface PluginHost {
    /**
     * Update one of the plugin's declared parameters. {@code name}
     * must match an entry from {@code parameterNames()}; values
     * outside {@code [parameterMin, parameterMax]} are clamped by
     * the host. Cheap to call — safe to spam from a 60 Hz touch
     * loop. The audio engine reads the new value on its next
     * process() invocation.
     */
    void setParameter(String name, float value);

    /**
     * Read a UTF-8 text asset shipped alongside the plugin.  Returns
     * {@code null} when the host doesn't ship assets, the named file
     * isn't present, or the plugin was authored against an older host
     * that doesn't implement this method.
     *
     * Plugins declare assets in {@code plugin.json} via an
     * {@code "assets": ["foo.svg", ...]} array.  Hosts fetch / cache
     * those files alongside the plugin's compiled class; this method
     * returns the on-disk text on demand.
     *
     * Use case: SVG vector art loaded via {@code Svg.parse} from the
     * gamekit, replacing dozens of procedural {@code drawPath} calls.
     * Plugins SHOULD null-check the return — older hosts won't
     * implement this and procedural fallback is the graceful path.
     */
    default String loadAssetText(String name) { return null; }

    /**
     * Read a binary asset shipped alongside the plugin.  Same resolution
     * rules as {@link #loadAssetText} — the file is declared in
     * {@code plugin.json}'s {@code "assets"} array and fetched / cached by
     * the host.  Returns {@code null} when the host doesn't ship assets,
     * the file is missing, or the plugin was authored against an older
     * host.  Use this for non-text resources such as ONNX models, PNG
     * sprites, or lookup tables.
     */
    default byte[] loadAssetBytes(String name) { return null; }

    /**
     * Load an ONNX model shipped as a plugin asset and return a ready
     * {@link InferenceSession}.  {@code assetName} must match an entry in
     * {@code plugin.json}'s {@code "assets"} array (e.g.
     * {@code "register-net.onnx"}).  The host owns the inference runtime
     * and caches one session per (plugin, asset), so calling this repeatedly
     * with the same name is cheap and returns the same underlying session.
     *
     * <p>Returns {@code null} when the host has no inference runtime, the
     * asset is missing / not a valid model, or the plugin runs on an older
     * host.  Plugins MUST null-check and fall back to a non-ML path — this
     * is what keeps an ML plugin loadable (degraded) on any host.
     */
    default InferenceSession loadModel(String assetName) { return null; }

    /**
     * Sound a continuous reference tone through the host's audio output.
     * For ear-training / interval / pitch-match plugins that need to
     * <em>play</em> a note for the singer to match. {@code freqHz <= 0}
     * silences it; {@code level} is linear amplitude 0..1. The host
     * smooths transitions so switching notes is click-free. Call it every
     * frame from {@code render()} with the note that should be sounding
     * (or {@code (0, 0)} for silence). Default no-op — older hosts that
     * don't support tone output degrade gracefully to silence.
     */
    default void playTone(float freqHz, float level) {}
}
