package com.vocalmonitor.audio

/**
 * Audio FX graph — replaces the previous fixed PC→EQ→Comp→Reverb chain
 * with a node + edge model so the user can add multiple instances of the
 * same effect, reorder, and route parallel branches.
 *
 * Topology rules:
 *   - Exactly one [NodeKind.Input] source, exactly one [NodeKind.Output] sink
 *   - Effect nodes have one input, one output
 *   - [NodeKind.Sum] nodes have N inputs, one output (for parallel re-merge)
 *   - No cycles (engine assumes DAG)
 *
 * The default graph for new users is a linear chain — visually identical to
 * the old fixed UI, so nothing surprising on first launch.
 */
enum class EffectKind { PitchCorrect, Equalizer, Compressor, Reverb, NoiseGate, JsPlugin }

enum class NodeKind { Input, Output, Effect, Sum }

/** Stable identifier — random short string, stays the same across renames/moves. */
data class NodeId(val value: String) {
    companion object {
        fun random(): NodeId {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            val sb = StringBuilder("n_")
            repeat(8) { sb.append(alphabet.random()) }
            return NodeId(sb.toString())
        }
    }
}

/**
 * One node in the graph. Effect nodes carry an opaque encoded state string
 * — the renderer decodes it via the per-kind state companion. Position is
 * stored for the visual editor; if absent (legacy), auto-layout uses the
 * topo order.
 */
data class GraphNode(
    val id: NodeId,
    val kind: NodeKind,
    val effectKind: EffectKind? = null,
    /** Encoded state for an effect node — schema matches [EqState]/[CompressorState]/etc. */
    val encodedState: String = "",
    /** Display label override; effect nodes default to the effect kind name. */
    val label: String = "",
    /** Bypass flag for effect nodes. Bypass = signal passes straight through. */
    val bypass: Boolean = false,
    /** Position in the visual editor, in grid units (1.0 = one node-width step). */
    val gridX: Float = 0f,
    val gridY: Float = 0f,
    /**
     * Pre-effect gain in dB. Useful for boosting / attenuating the signal
     * arriving at this node — for example the parallel-branch input to a
     * sum point can be ducked here without changing the source effect.
     */
    val inputGainDb: Float = 0f,
    /** Post-effect gain in dB. Applied after the effect (or bypass) runs. */
    val outputGainDb: Float = 0f,
    /**
     * When true, this node renders at full quality even during preview
     * (i.e. ignores the engine's preview flag). Useful for nodes whose
     * preview-quality variant has audible artefacts (e.g. pitch correct
     * sounds duller without formant preservation). Save always renders
     * full quality regardless of this flag.
     */
    val forceFullQuality: Boolean = false,
)

data class GraphEdge(val from: NodeId, val to: NodeId)

data class EffectGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
) {
    fun node(id: NodeId): GraphNode? = nodes.firstOrNull { it.id == id }

    fun input(): GraphNode = nodes.first { it.kind == NodeKind.Input }
    fun output(): GraphNode = nodes.first { it.kind == NodeKind.Output }

    fun upstream(id: NodeId): List<NodeId> = edges.filter { it.to == id }.map { it.from }
    fun downstream(id: NodeId): List<NodeId> = edges.filter { it.from == id }.map { it.to }

    /** Topological order — input first, output last; throws on cycle. */
    fun topoSort(): List<GraphNode> {
        val incoming = nodes.associate { n -> n.id to upstream(n.id).toMutableSet() }.toMutableMap()
        val result = ArrayList<GraphNode>()
        val ready = ArrayDeque(nodes.filter { incoming[it.id]?.isEmpty() == true })
        while (ready.isNotEmpty()) {
            val n = ready.removeFirst()
            result.add(n)
            for (downId in downstream(n.id)) {
                incoming[downId]?.remove(n.id)
                if (incoming[downId]?.isEmpty() == true) {
                    nodes.firstOrNull { it.id == downId }?.let { ready.addLast(it) }
                }
            }
        }
        require(result.size == nodes.size) { "graph has a cycle" }
        return result
    }

    /**
     * The "main path" — longest chain of single-input single-output nodes
     * from input to output. Used by the list view to show effects in their
     * sequence order; nodes off the main path are surfaced as parallel
     * branches.
     */
    fun mainPath(): List<GraphNode> {
        val path = ArrayList<GraphNode>()
        var current: GraphNode? = input()
        val visited = HashSet<NodeId>()
        while (current != null) {
            if (current.id in visited) break
            visited.add(current.id)
            path.add(current)
            if (current.kind == NodeKind.Output) break
            val next = downstream(current.id).firstOrNull { it != current.id }
            current = next?.let { node(it) }
        }
        return path
    }

    fun withNode(node: GraphNode): EffectGraph = copy(nodes = nodes + node)
    fun withoutNode(id: NodeId): EffectGraph =
        copy(nodes = nodes.filter { it.id != id }, edges = edges.filter { it.from != id && it.to != id })
    fun replaceNode(node: GraphNode): EffectGraph =
        copy(nodes = nodes.map { if (it.id == node.id) node else it })
    fun withEdge(edge: GraphEdge): EffectGraph =
        if (edges.contains(edge)) this else copy(edges = edges + edge)
    fun withoutEdge(edge: GraphEdge): EffectGraph =
        copy(edges = edges.filter { it != edge })

    /**
     * Insert [newNode] into the main chain just after [afterId], rewiring
     * existing edges so the chain stays valid.
     */
    fun insertAfter(afterId: NodeId, newNode: GraphNode): EffectGraph {
        val outgoing = edges.filter { it.from == afterId }
        var g = withNode(newNode)
        for (e in outgoing) {
            g = g.withoutEdge(e).withEdge(GraphEdge(newNode.id, e.to))
        }
        g = g.withEdge(GraphEdge(afterId, newNode.id))
        return g
    }

    /**
     * Remove a single-in single-out effect node, splicing its predecessor
     * directly to its successor.
     */
    fun spliceOut(id: NodeId): EffectGraph {
        val ups = upstream(id)
        val downs = downstream(id)
        var g = withoutNode(id)
        for (u in ups) for (d in downs) g = g.withEdge(GraphEdge(u, d))
        return g
    }

    /**
     * Move [id] up or down in the main chain (offset = -1 = up, +1 = down).
     * Only meaningful for nodes on the main path.
     */
    fun reorderMain(id: NodeId, offset: Int): EffectGraph {
        val path = mainPath().filter { it.kind == NodeKind.Effect }
        val idx = path.indexOfFirst { it.id == id }
        val target = idx + offset
        if (idx < 0 || target < 0 || target >= path.size) return this
        // Swap by removing this node from chain, then re-inserting at target position
        val ordered = path.toMutableList()
        val node = ordered.removeAt(idx)
        ordered.add(target, node)
        return rebuildMainChain(ordered)
    }

    /**
     * Set the main-path effect order to exactly the supplied list of
     * node ids. Bypasses the index-arithmetic that
     * [reorderMain] (id, offset) does internally — useful when the
     * caller already has the desired final order in hand (e.g.
     * after a drag-to-reorder gesture that tracked the running
     * visual order as `List<NodeId>` rather than a single +/-
     * offset). Ids that don't currently belong to a main-path
     * effect are silently dropped; effect nodes missing from
     * [orderedIds] are appended at the end so the chain never
     * loses a node accidentally.
     */
    fun reorderEffectsTo(orderedIds: List<NodeId>): EffectGraph {
        val currentEffects = mainPath().filter { it.kind == NodeKind.Effect }
        val byId = currentEffects.associateBy { it.id }
        val seen = HashSet<NodeId>(orderedIds.size)
        val newOrder = ArrayList<GraphNode>(currentEffects.size)
        for (id in orderedIds) {
            val n = byId[id] ?: continue
            if (seen.add(id)) newOrder.add(n)
        }
        for (n in currentEffects) {
            if (n.id !in seen) newOrder.add(n)
        }
        return rebuildMainChain(newOrder)
    }

    /** Rebuild the main chain to match the supplied effect-node order. */
    private fun rebuildMainChain(orderedEffects: List<GraphNode>): EffectGraph {
        val input = input()
        val output = output()
        // Drop edges that lie entirely on the main path
        val mainIds = (listOf(input.id) + orderedEffects.map { it.id } + listOf(output.id)).toHashSet()
        val keptEdges = edges.filter { it.from !in mainIds || it.to !in mainIds }
        // Rewire: input → e1 → e2 → … → output
        val newEdges = ArrayList(keptEdges)
        val seq = listOf(input) + orderedEffects + listOf(output)
        for (i in 0 until seq.size - 1) newEdges.add(GraphEdge(seq[i].id, seq[i + 1].id))
        return copy(edges = newEdges)
    }

    companion object {
        /**
         * Minimal built-in chain used as the seed before any user
         * customization. Input → Equalizer → Compressor → Reverb →
         * Output. PitchCorrect is no longer in the default — it's still
         * a valid effect kind for legacy graphs but the editor's bug
         * surface (formant glitches on noisy takes) means it's no
         * longer a sensible first impression. Users who want auto-tune
         * can drop one in from the Add Effect menu.
         *
         * `pcEncoded` kept in the signature for binary compatibility
         * with restoration paths (ignored here).
         */
        fun default(
            pcEncoded: String,
            eqEncoded: String,
            compEncoded: String,
            reverbEncoded: String,
        ): EffectGraph {
            val step = 1.7f
            val input  = GraphNode(NodeId("input"),  NodeKind.Input,  label = "Input",  gridX = 0f,        gridY = 1f)
            val eq     = GraphNode(NodeId.random(),  NodeKind.Effect, EffectKind.Equalizer,  eqEncoded,     "Equalizer",  gridX = step * 1f, gridY = 1f)
            val cmp    = GraphNode(NodeId.random(),  NodeKind.Effect, EffectKind.Compressor, compEncoded,   "Compressor", gridX = step * 2f, gridY = 1f)
            val rv     = GraphNode(NodeId.random(),  NodeKind.Effect, EffectKind.Reverb,     reverbEncoded, "Reverb",     gridX = step * 3f, gridY = 1f)
            val output = GraphNode(NodeId("output"), NodeKind.Output, label = "Output", gridX = step * 4f, gridY = 1f)
            return EffectGraph(
                nodes = listOf(input, eq, cmp, rv, output),
                edges = listOf(
                    GraphEdge(input.id, eq.id),
                    GraphEdge(eq.id, cmp.id),
                    GraphEdge(cmp.id, rv.id),
                    GraphEdge(rv.id, output.id),
                ),
            )
        }

        /**
         * "Voice Quality" — the full restoration chain matching the
         * mockup. Bundled native plugins handle the heavy lifting
         * (noise gate, declipper, air enhancer, de-rustle) and the
         * built-in EQ / Compressor / Reverb wrap them for shaping.
         *
         *   Input → noise-gate → de-clipper → Compressor →
         *   air-enhancer → de-rustle → Equalizer → Reverb → Output
         *
         * Plugin nodes carry encoded state pointing at the plugin's
         * displayName so [EffectGraphEngine] can find them via
         * `JsPluginEngine.get(name) ?: NativePluginEngine.get(name)`.
         * If a plugin isn't installed yet, its node is harmless
         * (engine bypasses it) — but `installBundled()` should have
         * registered them before this preset runs.
         */
        fun voiceQualityChain(
            // These are PLUGIN IDs (the registry id, e.g. "noise-gate"),
            // not the pretty display name. JsPluginEngine.get /
            // NativePluginEngine.get index by the id under which the
            // plugin was registered, so the encoded state has to use
            // the same id or the engine reports "not loaded".
            //
            // De-rustle was dropped — it muddied the result more than
            // it cleaned. The remaining three restoration plugins
            // (noise-gate, de-clipper, air-enhancer) wrap around the
            // built-in Compressor / EQ / Reverb shaping.
            noiseGateId:   String,
            deClipperId:   String,
            airEnhancerId: String,
            eqEncoded:     String,
            compEncoded:   String,
            reverbEncoded: String,
        ): EffectGraph {
            fun pluginState(id: String): String =
                "$id;1;0;"   // enabled=1, parallel=0, no param overrides
            val step = 1.4f
            val input    = GraphNode(NodeId("input"), NodeKind.Input, label = "Input", gridX = 0f, gridY = 1f)
            val ng       = GraphNode(NodeId.random(), NodeKind.Effect, EffectKind.JsPlugin,   pluginState(noiseGateId),   "Noise Gate",    gridX = step * 1f, gridY = 1f)
            val declip   = GraphNode(NodeId.random(), NodeKind.Effect, EffectKind.JsPlugin,   pluginState(deClipperId),   "De-clipper",    gridX = step * 2f, gridY = 1f)
            val cmp      = GraphNode(NodeId.random(), NodeKind.Effect, EffectKind.Compressor, compEncoded,                "Compressor",    gridX = step * 3f, gridY = 1f)
            val air      = GraphNode(NodeId.random(), NodeKind.Effect, EffectKind.JsPlugin,   pluginState(airEnhancerId), "Air Enhancer",  gridX = step * 4f, gridY = 1f)
            val eq       = GraphNode(NodeId.random(), NodeKind.Effect, EffectKind.Equalizer,  eqEncoded,                  "Equalizer",     gridX = step * 5f, gridY = 1f)
            val rv       = GraphNode(NodeId.random(), NodeKind.Effect, EffectKind.Reverb,     reverbEncoded,              "Reverb",        gridX = step * 6f, gridY = 1f)
            val output   = GraphNode(NodeId("output"), NodeKind.Output, label = "Output", gridX = step * 7f, gridY = 1f)
            val chain = listOf(input, ng, declip, cmp, air, eq, rv, output)
            return EffectGraph(
                nodes = chain,
                edges = chain.zipWithNext { from, to -> GraphEdge(from.id, to.id) },
            )
        }
    }
}
