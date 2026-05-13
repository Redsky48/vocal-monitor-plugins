package com.vocalmonitor.plugin;

/**
 * How a {@link PluginPaint} fills primitives drawn through
 * {@link PluginCanvas}. Mirrors Android's `Paint.Style` so the adapter
 * can hand each value straight through to Skia without translation —
 * but keep this enum stable; the abstract API contract is the source
 * of truth, not Android's enum.
 */
public enum PluginStyle {
    FILL,
    STROKE,
    FILL_AND_STROKE,
}
