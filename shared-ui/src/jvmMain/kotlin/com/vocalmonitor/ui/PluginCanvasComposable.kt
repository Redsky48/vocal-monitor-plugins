package com.vocalmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin
import kotlinx.coroutines.delay

@Composable
actual fun PluginVisualSurface(
    plugin: VocalMonitorVisualPlugin,
    params: Map<String, Float>,
    streams: Map<String, FloatArray>,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val startMs = remember { System.currentTimeMillis() }
    var frameMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(plugin) {
        // ~60 fps redraw — mutating frameMs invalidates the Canvas
        // below.  Cheap because the plugin's own render() already
        // reads wall-clock time; this tick is just the recomposition
        // trigger.
        while (true) {
            frameMs = System.currentTimeMillis() - startMs
            delay(16)
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE")
        val tick = frameMs
        val w = size.width / density.density
        val h = size.height / density.density
        val pluginCanvas = SkiaPluginCanvas(this, density)
        try {
            plugin.render(
                pluginCanvas,
                w.toInt(), h.toInt(),
                System.currentTimeMillis() - startMs,
                params, streams,
            )
        } catch (_: Throwable) {
            // Leave the previous frame on screen instead of crashing
            // the host on a plugin draw error.
        }
    }
}
