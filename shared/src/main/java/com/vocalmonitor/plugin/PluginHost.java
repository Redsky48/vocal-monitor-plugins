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
}
