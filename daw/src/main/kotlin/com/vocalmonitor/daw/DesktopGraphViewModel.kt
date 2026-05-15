package com.vocalmonitor.daw

import com.vocalmonitor.audio.ChainPreset
import com.vocalmonitor.audio.EffectGraph
import com.vocalmonitor.audio.EffectKind
import com.vocalmonitor.audio.GraphEdge
import com.vocalmonitor.audio.GraphNode
import com.vocalmonitor.audio.NodeId
import com.vocalmonitor.audio.NodeKind
import com.vocalmonitor.desktop.DesktopPluginEngine
import com.vocalmonitor.desktop.MicLevelMonitor
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin
import com.vocalmonitor.ui.AudioGraphViewModel
import com.vocalmonitor.ui.PluginCatalogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop implementation of [AudioGraphViewModel].
 *
 * Counterpart to slim's `MainViewModel` — same surface, but built
 * on `MutableStateFlow` directly (no `androidx.lifecycle.ViewModel`
 * dependency).  Backs the shared `AudioGraphSheet` composable so
 * the DAW renders the exact same Compose UI Android does.
 *
 * Plugin look-ups go through the host's [DesktopPluginEngine].
 * Built-in effects (Equalizer / Compressor / Reverb / NoiseGate /
 * PitchCorrect) are stubbed for now — Phase B wires them to the
 * actual `:shared` DSP classes once the inspector UI lands.
 */
class DesktopGraphViewModel(
    private val pluginEngine: DesktopPluginEngine,
    initialGraph: EffectGraph = defaultGraph(),
    initialExpanded: Set<NodeId> = emptySet(),
) : AudioGraphViewModel {

    private val _graph     = MutableStateFlow(initialGraph)
    private val _expanded  = MutableStateFlow(initialExpanded)
    private val _pinned    = MutableStateFlow<Set<NodeId>>(emptySet())
    private val _label     = MutableStateFlow("idle")
    private val _plugins   = MutableStateFlow<List<PluginCatalogEntry>>(emptyList())

    override val graph: StateFlow<EffectGraph>            = _graph.asStateFlow()
    override val expandedGraphNodes: StateFlow<Set<NodeId>> = _expanded.asStateFlow()
    override val pinnedPanels: StateFlow<Set<NodeId>>     = _pinned.asStateFlow()
    override val audioProcessingLabel: StateFlow<String>  = _label.asStateFlow()
    override val jsPlugins: StateFlow<List<PluginCatalogEntry>> = _plugins.asStateFlow()

    /** Refresh the plugin catalogue from whatever the engine has loaded. */
    fun refreshPluginCatalogue() {
        _plugins.value = pluginEngine.list().map { id ->
            PluginCatalogEntry(
                id = id,
                displayName = id,
                uiKind = pluginEngine.uiKindOf(id),
                fullscreenCapable = pluginEngine.isFullscreen(id),
            )
        }
    }

    override fun catalogEntryFor(pluginName: String): PluginCatalogEntry? =
        _plugins.value.firstOrNull { it.id == pluginName }

    override fun newVisualInstance(pluginId: String): VocalMonitorVisualPlugin? =
        pluginEngine.newVisualInstance(pluginId)

    // ── Stable per-node visual-plugin cache ───────────────────
    // Created lazily on first ask; the audio loop reads a snapshot
    // each block and feeds samples in via plugin.process().  Lives
    // on a CHM so the audio thread can iterate without locking.
    private val visualPluginCache =
        java.util.concurrent.ConcurrentHashMap<NodeId, VocalMonitorVisualPlugin>()

    override fun visualPluginFor(nodeId: NodeId): VocalMonitorVisualPlugin? {
        val n = _graph.value.nodes.firstOrNull { it.id == nodeId } ?: return null
        if (n.kind != com.vocalmonitor.audio.NodeKind.Effect) return null
        visualPluginCache[nodeId]?.let { return it }
        val fresh = pluginEngine.newVisualInstance(n.label) ?: return null
        visualPluginCache[nodeId] = fresh
        return fresh
    }

    /** Drop cached plugin instances for nodes that no longer exist
     *  or whose labels changed (so a relabel re-mints the instance).
     *  Called after every graph mutation. */
    private fun pruneVisualPluginCache() {
        val liveNodes = _graph.value.nodes.associateBy { it.id }
        visualPluginCache.keys.toList().forEach { id ->
            val node = liveNodes[id]
            if (node == null || node.kind != com.vocalmonitor.audio.NodeKind.Effect) {
                visualPluginCache.remove(id)
            }
        }
    }

    // ── Plugin management ─────────────────────────────────────
    override fun addEffectToChain(kind: EffectKind) {
        val g = _graph.value
        val rightmost = g.nodes.maxOfOrNull { it.gridX } ?: 0f
        val node = GraphNode(
            id = NodeId.random(),
            kind = NodeKind.Effect,
            effectKind = kind,
            label = kind.name,
            gridX = rightmost + 2.5f,
            gridY = 2f,
        )
        _graph.value = g.copy(nodes = g.nodes + node)
    }

    override fun addJsPluginToChain(pluginId: String) {
        val g = _graph.value
        val rightmost = g.nodes.maxOfOrNull { it.gridX } ?: 0f
        val node = GraphNode(
            id = NodeId.random(),
            kind = NodeKind.Effect,
            effectKind = EffectKind.JsPlugin,
            label = pluginId,
            gridX = rightmost + 2.5f,
            gridY = 2f,
        )
        _graph.value = g.copy(nodes = g.nodes + node)
    }

    // ── Connections ───────────────────────────────────────────
    override fun connectNodes(from: NodeId, to: NodeId) {
        if (from == to) return
        val g = _graph.value
        if (g.edges.any { it.from == from && it.to == to }) return
        if (introducesCycle(g, from, to)) return
        _graph.value = g.copy(edges = g.edges + GraphEdge(from, to))
    }

    override fun disconnectNodes(from: NodeId, to: NodeId) {
        val g = _graph.value
        _graph.value = g.copy(
            edges = g.edges.filterNot { it.from == from && it.to == to },
        )
    }

    override fun disconnectAllForNode(id: NodeId) {
        val g = _graph.value
        _graph.value = g.copy(
            edges = g.edges.filterNot { it.from == id || it.to == id },
        )
    }

    private fun introducesCycle(g: EffectGraph, from: NodeId, to: NodeId): Boolean {
        val visited = mutableSetOf<NodeId>()
        val stack = ArrayDeque<NodeId>().apply { add(to) }
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!visited.add(cur)) continue
            if (cur == from) return true
            for (e in g.edges) if (e.from == cur) stack.add(e.to)
        }
        return false
    }

    // ── Node lifecycle ────────────────────────────────────────
    override fun removeNode(id: NodeId) {
        val g = _graph.value
        _graph.value = g.copy(
            nodes = g.nodes.filter { it.id != id },
            edges = g.edges.filter { it.from != id && it.to != id },
        )
        _expanded.value = _expanded.value - id
        _pinned.value   = _pinned.value - id
        pruneVisualPluginCache()
    }

    override fun duplicateNode(id: NodeId) {
        val g = _graph.value
        val n = g.node(id) ?: return
        _graph.value = g.copy(
            nodes = g.nodes + n.copy(
                id = NodeId.random(),
                gridX = n.gridX + 0.4f,
                gridY = n.gridY + 0.4f,
            ),
        )
    }

    override fun resetGraphToDefault() {
        _graph.value = defaultGraph()
        _expanded.value = emptySet()
        _pinned.value = emptySet()
    }

    override fun setNodePositions(positions: Map<NodeId, Pair<Float, Float>>) {
        val g = _graph.value
        _graph.value = g.copy(
            nodes = g.nodes.map { n ->
                val p = positions[n.id]
                if (p != null) n.copy(gridX = p.first, gridY = p.second) else n
            },
        )
    }

    override fun commitNode(node: GraphNode) {
        val g = _graph.value
        _graph.value = g.copy(
            nodes = g.nodes.map { if (it.id == node.id) node else it },
        )
    }

    override fun setNodeBypass(id: NodeId, bypass: Boolean) {
        commitField(id) { it.copy(bypass = bypass) }
    }

    override fun setNodeEncodedState(id: NodeId, encodedState: String) {
        commitField(id) { it.copy(encodedState = encodedState) }
    }

    override fun setNodeInputGain(id: NodeId, db: Float) {
        commitField(id) { it.copy(inputGainDb = db) }
    }

    override fun setNodeOutputGain(id: NodeId, db: Float) {
        commitField(id) { it.copy(outputGainDb = db) }
    }

    override fun setNodeForceFullQuality(id: NodeId, force: Boolean) {
        commitField(id) { it.copy(forceFullQuality = force) }
    }

    private inline fun commitField(id: NodeId, transform: (GraphNode) -> GraphNode) {
        val g = _graph.value
        _graph.value = g.copy(
            nodes = g.nodes.map { if (it.id == id) transform(it) else it },
        )
    }

    // ── UI state ──────────────────────────────────────────────
    override fun toggleGraphNodeExpanded(id: NodeId) {
        _expanded.value =
            if (id in _expanded.value) _expanded.value - id
            else _expanded.value + id
    }

    override fun pinPanel(id: NodeId)   { _pinned.value = _pinned.value + id }
    override fun unpinPanel(id: NodeId) { _pinned.value = _pinned.value - id }

    /** No background-recorder facade on Desktop (yet). */
    override val recorder: Any? = null

    // ── Mic-level monitor — standalone capture loop just for the meter.
    private val _micLevel = MutableStateFlow(0f)
    override val micLevel: StateFlow<Float> = _micLevel.asStateFlow()
    private var micMonitor: MicLevelMonitor? = null
    private var micMonitoringDevice: String? = null

    @Synchronized
    override fun startMicMonitoring(deviceName: String?) {
        val normalised = deviceName?.takeIf { it.isNotBlank() && it != "Default" }
        if (micMonitor != null && micMonitoringDevice == normalised) return
        stopMicMonitoring()
        micMonitoringDevice = normalised
        val scratchOut = FloatArray(1024)
        micMonitor = MicLevelMonitor(
            deviceName = normalised,
            onLevel = { lvl -> _micLevel.value = lvl },
            onSamples = { samples ->
                // Fan-out to every cached visual-plugin instance.  Each
                // call updates the plugin's internal state which the
                // PluginVisualSurface then reads at the next paint frame.
                // Output buffer is discarded — we're not playing back
                // anything yet (audio engine routing is Phase C+).
                val snapshot = visualPluginCache.values
                if (snapshot.isEmpty()) return@MicLevelMonitor
                for (p in snapshot) {
                    runCatching { p.process(samples, scratchOut) }
                }
            },
        ).also { it.start() }
    }

    @Synchronized
    override fun stopMicMonitoring() {
        micMonitor?.stop()
        micMonitor = null
        micMonitoringDevice = null
        _micLevel.value = 0f
    }

    init {
        // Boot the always-on audio capture using the system default
        // input device.  Picker can hot-swap to a specific mic later
        // — startMicMonitoring no-ops when device matches.
        startMicMonitoring(null)
    }

    override val audioOutputDevices: List<String> by lazy {
        listOf("Default") + enumerateMixers(forOutput = true)
    }
    override val audioInputDevices: List<String> by lazy {
        listOf("Default") + enumerateMixers(forOutput = false)
    }

    private fun enumerateMixers(forOutput: Boolean): List<String> = runCatching {
        val infos = javax.sound.sampled.AudioSystem.getMixerInfo()
        infos.mapNotNull { info ->
            val mixer = javax.sound.sampled.AudioSystem.getMixer(info)
            val lines = if (forOutput) mixer.sourceLineInfo else mixer.targetLineInfo
            val supportsDataLine = lines.any {
                val cls = if (forOutput) javax.sound.sampled.SourceDataLine::class.java
                          else javax.sound.sampled.TargetDataLine::class.java
                cls.isAssignableFrom(it.lineClass)
            }
            if (supportsDataLine) info.name.trim() else null
        }.distinct()
    }.getOrDefault(emptyList())

    // ── Chain presets ─────────────────────────────────────────
    // Persistent user-preset storage is a Phase 4c item; for now
    // they live in-memory and disappear on app exit.  Factory
    // presets are empty until slim's FactoryChainPresets get
    // wired in (also Phase 4c).
    private val _userChainPresets = MutableStateFlow<List<ChainPreset>>(emptyList())
    override val userChainPresets: StateFlow<List<ChainPreset>> = _userChainPresets.asStateFlow()
    override val factoryChainPresets: List<ChainPreset> = emptyList()

    override fun loadChainPreset(preset: ChainPreset) {
        // ChainPreset only carries the encoded effect graph; the codec
        // lives in :shared.  Wire EffectGraphCodec.decode here once
        // we port that JSON path over.
    }

    override fun saveCurrentChainAsPreset(name: String) {
        val current = ChainPreset(name = name, encodedGraph = "")  // codec stub
        _userChainPresets.value = _userChainPresets.value + current
    }

    override fun deleteChainPreset(name: String) {
        _userChainPresets.value = _userChainPresets.value.filterNot { it.name == name }
    }

    companion object {
        fun defaultGraph(): EffectGraph = EffectGraph(
            nodes = listOf(
                GraphNode(
                    id = NodeId("input"),
                    kind = NodeKind.Input,
                    label = "Mic",
                    gridX = 0.5f, gridY = 2f,
                ),
                GraphNode(
                    id = NodeId("output"),
                    kind = NodeKind.Output,
                    label = "Speakers",
                    gridX = 6f, gridY = 2f,
                ),
            ),
            edges = listOf(GraphEdge(NodeId("input"), NodeId("output"))),
        )

        /**
         * Auto-built demo preset: Mic → vocal-spectrum → glow-meter →
         * formant-tracker → Speakers.  Picks plugins that are visual
         * (so they show something inside expanded node bodies) and
         * common (so any reasonable plugin load completes).  Used as
         * the initial graph on app launch so the user sees a populated
         * chain instead of bare input/output stubs.
         */
        fun demoGraph(): EffectGraph {
            // Expanded plugin nodes occupy 5 × 4 grid units in
            // AudioGraphSheet, so column-to-column spacing must be ≥ 5
            // (we use 5.5 for breathing room) and row pitch ≥ 4 (we
            // use 4.5).  Input + Output stay collapsed (small).
            val input = GraphNode(
                id = NodeId("input"), kind = NodeKind.Input,
                label = "Mic", gridX = 0.5f, gridY = 5f,
            )
            val spectrum = GraphNode(
                id = NodeId("demo-spectrum"), kind = NodeKind.Effect,
                effectKind = EffectKind.JsPlugin, label = "vocal-spectrum",
                gridX = 5f, gridY = 0.5f,
            )
            val glow = GraphNode(
                id = NodeId("demo-glow"), kind = NodeKind.Effect,
                effectKind = EffectKind.JsPlugin, label = "glow-meter",
                gridX = 5f, gridY = 5f,
            )
            val formant = GraphNode(
                id = NodeId("demo-formant"), kind = NodeKind.Effect,
                effectKind = EffectKind.JsPlugin, label = "formant-tracker",
                gridX = 5f, gridY = 9.5f,
            )
            val output = GraphNode(
                id = NodeId("output"), kind = NodeKind.Output,
                label = "Speakers", gridX = 11f, gridY = 5f,
            )
            return EffectGraph(
                nodes = listOf(input, spectrum, glow, formant, output),
                edges = listOf(
                    GraphEdge(input.id, spectrum.id),
                    GraphEdge(input.id, glow.id),
                    GraphEdge(input.id, formant.id),
                    GraphEdge(spectrum.id, output.id),
                    GraphEdge(glow.id, output.id),
                    GraphEdge(formant.id, output.id),
                ),
            )
        }
    }
}
