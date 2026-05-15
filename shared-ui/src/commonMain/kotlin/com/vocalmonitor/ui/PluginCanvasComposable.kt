package com.vocalmonitor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin

/**
 * Live render of a `VocalMonitorVisualPlugin` instance — same role
 * slim's `PluginPanel` plays inside expanded NodeEffectCards.  The
 * commonMain declares the contract, the platform's actual provides
 * the canvas adapter:
 *
 *   - jvmMain   → wraps Compose Desktop's Skia native canvas in
 *                 `SkiaPluginCanvas` so the plugin's `render()`
 *                 method runs against the abstract `PluginCanvas`
 *                 API it was written for.
 *   - androidMain (future) → wraps android.graphics.Canvas via
 *                 slim's existing `ComposePluginCanvas`.
 *
 * Same plugin code, two backends, one Compose surface here for the
 * UI to embed.  Plugins are passed in by the host that already
 * minted them via its platform `NativePluginEngine` /
 * `DesktopPluginEngine`.
 */
@Composable
expect fun PluginVisualSurface(
    plugin: VocalMonitorVisualPlugin,
    params: Map<String, Float> = emptyMap(),
    streams: Map<String, FloatArray> = emptyMap(),
    modifier: Modifier = Modifier,
)
