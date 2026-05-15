package com.vocalmonitor.audio

/**
 * Metadata for a single user-loaded JS audio plugin. Modelled after the
 * Web Audio API's `AudioWorkletProcessor` so plugins authored for browser
 * DAWs port over with minimal adaptation.
 *
 * The actual JS class reference lives inside [JsPluginEngine] keyed by
 * [name]; this object is the engine-independent description we store in
 * the graph, share with the UI, and persist in [JsPluginState].
 */
data class JsPlugin(
    /** Stable id used in graph nodes (matches the JS `registerProcessor` name). */
    val name: String,
    /** Human-readable display name. Defaults to [name] if the plugin didn't set one. */
    val displayName: String,
    /** Parameter descriptors mirroring AudioWorklet's `parameterDescriptors` array. */
    val parameters: List<JsPluginParam>,
    /**
     * Factory presets declared by the plugin author in `plugin.json`.
     * Each preset is a named bundle of param overrides; un-listed params
     * keep the current value when the preset is applied (so a preset
     * that only tweaks "depth" doesn't reset the user's "rate" choice).
     * Mirrors the [PitchCorrectState.FACTORY] pattern for built-in effects.
     */
    val presets: List<PluginPreset> = emptyList(),
)

/** One slider / knob in a JS plugin. */
data class JsPluginParam(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
)

/**
 * Author-shipped preset for a JS / native plugin. Loaded from the
 * `presets` array in `plugin.json` and forwarded through the master
 * `manifest.json` so the app can render preset chips alongside the
 * plugin's parameter sliders.
 *
 * Schema in `plugin.json`:
 * ```json
 * "presets": [
 *   {
 *     "name": "Subtle",
 *     "description": "barely-there warmth",
 *     "params": { "depth": 0.2, "drive": 0.3 }
 *   },
 *   { "name": "Crush", "params": { "depth": 1, "drive": 0.95 } }
 * ]
 * ```
 *
 * `description` is optional. `params` keys must match parameter ids the
 * plugin actually declares — unknown ids are ignored at load time.
 */
data class PluginPreset(
    val name: String,
    val description: String,
    val params: Map<String, Float>,
)

/**
 * Per-instance state for a JS plugin sitting on a graph node. Encoded
 * format is `pluginName;enabled;parallel;paramId=value,paramId=value...`
 * so it round-trips through the existing `GraphNode.encodedState`
 * String. Unknown params are tolerated for forward-compat — if a plugin
 * gains a new param the old saved state still loads with defaults.
 */
data class JsPluginState(
    val pluginName: String,
    val enabled: Boolean,
    val parallel: Boolean,
    val params: Map<String, Float>,
) {
    fun encode(): String = buildString {
        append(pluginName.replace(";", "_"))
        append(';')
        append(if (enabled) "1" else "0")
        append(';')
        append(if (parallel) "1" else "0")
        append(';')
        append(params.entries.joinToString(",") { (k, v) -> "${k.replace("=", "_")}=$v" })
    }

    companion object {
        fun decode(s: String?): JsPluginState? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(";")
            if (parts.size < 3) return null
            val name = parts[0]
            val enabled = parts[1] == "1"
            val parallel = parts[2] == "1"
            val params = if (parts.size > 3) {
                parts[3].split(",")
                    .mapNotNull { kv ->
                        val eq = kv.indexOf('=')
                        if (eq <= 0) null
                        else {
                            val k = kv.substring(0, eq)
                            val v = kv.substring(eq + 1).toFloatOrNull() ?: return@mapNotNull null
                            k to v
                        }
                    }.toMap()
            } else emptyMap()
            return JsPluginState(name, enabled, parallel, params)
        }

        fun initial(plugin: JsPlugin): JsPluginState = JsPluginState(
            pluginName = plugin.name,
            enabled = true,
            parallel = false,
            params = plugin.parameters.associate { it.id to it.default },
        )
    }
}
