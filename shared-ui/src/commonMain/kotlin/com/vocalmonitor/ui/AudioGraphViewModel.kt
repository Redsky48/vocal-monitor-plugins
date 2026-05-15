package com.vocalmonitor.ui

import com.vocalmonitor.audio.ChainPreset
import com.vocalmonitor.audio.EffectGraph
import com.vocalmonitor.audio.EffectKind
import com.vocalmonitor.audio.GraphNode
import com.vocalmonitor.audio.NodeId
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin
import kotlinx.coroutines.flow.StateFlow

/**
 * Lean interface the [AudioGraphSheet] composable talks to.  Pulled
 * out of slim's 5,400-line `MainViewModel` so the same Compose UI
 * can drive either backend:
 *
 *   - on Android, slim's existing MainViewModel implements this by
 *     forwarding to its established graph / preset / recorder state
 *   - on Desktop, [com.vocalmonitor.daw] provides a small focused
 *     implementation that talks to DesktopPluginEngine + JavaSoundIO
 *
 * Methods are exactly the set AudioGraphSheet calls — nothing more.
 * Adding new UI features here means adding methods here too, both
 * implementations have to satisfy them.
 *
 * For host-supplied UI state that doesn't fit the "method call"
 * model (audioProcessingLabel, jsPlugins list), the interface
 * exposes [StateFlow]s the composable can `collectAsState()`.
 */
interface AudioGraphViewModel {

    // ── Graph state ───────────────────────────────────────────
    val graph: StateFlow<EffectGraph>
    val expandedGraphNodes: StateFlow<Set<NodeId>>
    val pinnedPanels: StateFlow<Set<NodeId>>
    val audioProcessingLabel: StateFlow<String>

    // ── Plugin catalogue ──────────────────────────────────────
    /** All JS / native plugins the host has loaded, in registration order. */
    val jsPlugins: StateFlow<List<PluginCatalogEntry>>

    /** Metadata for a specific plugin id (used by the node-card body to
     *  pick canvas / spec UI, aspect ratio, min-height etc.). */
    fun catalogEntryFor(pluginName: String): PluginCatalogEntry?

    /** Mint a live visual-plugin instance the host can embed inside
     *  the expanded node body via [PluginVisualSurface].  Returns
     *  null when the plugin class doesn't implement the visual
     *  contract (most pure audio plugins don't). */
    fun newVisualInstance(pluginId: String): VocalMonitorVisualPlugin?

    /** Stable per-node visual-plugin handle.  The host caches one
     *  instance per [nodeId] (created lazily) so the audio engine
     *  and the UI both see the SAME instance — without this, the
     *  audio loop would feed samples into one copy and the UI would
     *  render a different blank one.  Returns null for non-plugin
     *  nodes or when the plugin class isn't visual. */
    fun visualPluginFor(nodeId: NodeId): VocalMonitorVisualPlugin?

    // ── Graph mutations ───────────────────────────────────────
    fun addEffectToChain(kind: EffectKind)
    fun addJsPluginToChain(pluginId: String)
    fun connectNodes(from: NodeId, to: NodeId)
    fun disconnectNodes(from: NodeId, to: NodeId)
    fun disconnectAllForNode(id: NodeId)
    fun removeNode(id: NodeId)
    fun duplicateNode(id: NodeId)
    fun resetGraphToDefault()
    fun setNodePositions(positions: Map<NodeId, Pair<Float, Float>>)
    fun commitNode(node: GraphNode)
    fun setNodeBypass(id: NodeId, bypass: Boolean)
    fun setNodeEncodedState(id: NodeId, encodedState: String)
    fun setNodeInputGain(id: NodeId, db: Float)
    fun setNodeOutputGain(id: NodeId, db: Float)
    fun setNodeForceFullQuality(id: NodeId, force: Boolean)
    fun toggleGraphNodeExpanded(id: NodeId)

    // ── Side panels ───────────────────────────────────────────
    fun pinPanel(id: NodeId)
    fun unpinPanel(id: NodeId)

    // ── Audio device enumeration ──────────────────────────────
    /** Friendly names of available audio output devices, in the
     *  order the platform reports them.  Always includes "Default"
     *  as the first entry.  Empty (or just "Default") on Android. */
    val audioOutputDevices: List<String> get() = listOf("Default")

    /** Friendly names of available audio input devices (mics).
     *  Always includes "Default".  Empty / Default-only on Android. */
    val audioInputDevices: List<String> get() = listOf("Default")

    // ── Live mic-level metering ───────────────────────────────
    /** Normalised RMS of the mic, 0..1.  Updated ~30-60Hz while
     *  monitoring is active; 0 when stopped.  Always-zero stub on
     *  platforms that don't have a meter implementation. */
    val micLevel: StateFlow<Float>

    /** Open the input device (or the OS default if [deviceName] is
     *  null / "Default") and start streaming level data into
     *  [micLevel].  Idempotent — calling twice with the same device
     *  is a no-op; with a different device it restarts cleanly. */
    fun startMicMonitoring(deviceName: String?) {}

    /** Tear down the level-monitor capture line.  Idempotent. */
    fun stopMicMonitoring() {}

    // ── Recorder bridge ───────────────────────────────────────
    /** Recorder facade — null on platforms with no recorder
     *  (gives the inspector UI a graceful "recording unavailable" path). */
    val recorder: Any?

    // ── Chain presets ─────────────────────────────────────────
    /** User-saved chain presets, live-updated as they edit / delete. */
    val userChainPresets: StateFlow<List<ChainPreset>>

    /** Factory chains the app ships with — read-only list. */
    val factoryChainPresets: List<ChainPreset>

    fun loadChainPreset(preset: ChainPreset)
    fun saveCurrentChainAsPreset(name: String)
    fun deleteChainPreset(name: String)
}

/**
 * Slim-side `JsPlugin` mirrored as a host-neutral catalogue entry so
 * the shared UI doesn't need to know about slim's JsPlugin /
 * NativePluginEngine class hierarchy.  The host (slim or DAW) maps
 * its own plugin descriptor to this on construction.
 */
data class PluginCatalogEntry(
    val id: String,
    val displayName: String,
    val uiKind: String,                // "canvas" | "spec" | null
    val uiAspect: Float = 0f,
    val uiMinHeightDp: Int = 0,
    val parameterIds: List<String> = emptyList(),
    /** Plugin opts into being shown as the only thing on the
     *  window — set via `"fullscreen": true` in plugin.json.
     *  DAW renders these inside [FullScreenDialog] when the user
     *  hits the Fullscreen action.  Useful for voice-driven games
     *  or any plugin where a tiny node body wastes the experience. */
    val fullscreenCapable: Boolean = false,
)
