package com.vocalmonitor.audio

/**
 * Plain-text codec for [EffectGraph] — no JSON dep, just newline-separated
 * `kind|...` records. Intended for DataStore persistence.
 *
 * Format:
 *   v1
 *   N|<id>|<kind>|<effectKind?>|<bypass>|<gridX>|<gridY>|<labelEsc>|<stateEsc>
 *   E|<from>|<to>
 *   …
 *
 * Pipe `|` is forbidden in IDs (alphabet is a-z + 0-9 + `_`); state strings
 * use ';' and ',' separators that don't clash. Labels and state are
 * \-escaped to be pipe-safe.
 */
object EffectGraphCodec {

    private const val VERSION = "v1"

    fun encode(g: EffectGraph): String {
        val sb = StringBuilder()
        sb.append(VERSION).append('\n')
        for (n in g.nodes) {
            sb.append("N|")
                .append(n.id.value).append('|')
                .append(n.kind.name).append('|')
                .append(n.effectKind?.name ?: "").append('|')
                .append(if (n.bypass) "1" else "0").append('|')
                .append("%.3f".format(n.gridX)).append('|')
                .append("%.3f".format(n.gridY)).append('|')
                .append(esc(n.label)).append('|')
                .append(esc(n.encodedState)).append('|')
                .append("%.3f".format(n.inputGainDb)).append('|')
                .append("%.3f".format(n.outputGainDb)).append('|')
                .append(if (n.forceFullQuality) "1" else "0")
                .append('\n')
        }
        for (e in g.edges) {
            sb.append("E|").append(e.from.value).append('|').append(e.to.value).append('\n')
        }
        return sb.toString()
    }

    fun decode(text: String): EffectGraph? {
        if (text.isBlank()) return null
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != VERSION) return null
        val nodes = ArrayList<GraphNode>()
        val edges = ArrayList<GraphEdge>()
        for (line in lines.drop(1)) {
            val parts = splitPipe(line)
            when (parts.firstOrNull()) {
                "N" -> {
                    if (parts.size < 9) return null
                    val kind = NodeKind.values().firstOrNull { it.name == parts[2] } ?: return null
                    val effectKind = EffectKind.values().firstOrNull { it.name == parts[3] }
                    nodes.add(GraphNode(
                        id = NodeId(parts[1]),
                        kind = kind,
                        effectKind = effectKind,
                        bypass = parts[4] == "1",
                        gridX = parts[5].toFloatOrNull() ?: 0f,
                        gridY = parts[6].toFloatOrNull() ?: 0f,
                        label = unesc(parts[7]),
                        encodedState = unesc(parts[8]),
                        inputGainDb = parts.getOrNull(9)?.toFloatOrNull() ?: 0f,
                        outputGainDb = parts.getOrNull(10)?.toFloatOrNull() ?: 0f,
                        forceFullQuality = parts.getOrNull(11) == "1",
                    ))
                }
                "E" -> {
                    if (parts.size < 3) return null
                    edges.add(GraphEdge(NodeId(parts[1]), NodeId(parts[2])))
                }
            }
        }
        if (nodes.none { it.kind == NodeKind.Input }) return null
        if (nodes.none { it.kind == NodeKind.Output }) return null
        return EffectGraph(nodes, edges)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> sb.append('\\')
                    'p' -> sb.append('|')
                    'n' -> sb.append('\n')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Split on raw `|`, respecting `\\|` escapes. */
    private fun splitPipe(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                cur.append(c).append(line[i + 1]); i += 2
            } else if (c == '|') {
                out.add(cur.toString()); cur.clear(); i++
            } else {
                cur.append(c); i++
            }
        }
        out.add(cur.toString())
        return out
    }
}
