package com.vocalmonitor.audio

/**
 * Saved snapshot of an entire FX chain — graph topology + every node's
 * encoded state. Loading one drops it in atomically, replacing whatever
 * the user had.
 *
 * Stored as `name|escapedGraphBlob` in DataStore; multiple presets are
 * joined by `\n`. Within a preset we escape `|` as `\p` and `\n` as `\n`
 * (literal backslash+n) so neither byte conflicts with the outer
 * separators.
 */
data class ChainPreset(
    val name: String,
    val encodedGraph: String,
    /**
     * Node IDs the user had expanded in the audio-graph view when this
     * preset was saved. Loading the preset restores the same expansion
     * pattern. Empty for legacy presets (pre-v98) — those default to
     * "all expanded" on load so the user sees the full plugin panels.
     */
    val expandedNodeIds: Set<String> = emptySet(),
) {
    fun encodeLine(): String {
        val base = "${name.replace("|", "_")}|${escape(encodedGraph)}"
        return if (expandedNodeIds.isEmpty()) base
        else "$base|${expandedNodeIds.joinToString(",")}"
    }

    companion object {
        fun decodeLine(line: String): ChainPreset? {
            val firstPipe = line.indexOf('|')
            if (firstPipe <= 0) return null
            val name = line.substring(0, firstPipe)
            // Graph payload has all `|` escaped to `\p`, so the next
            // raw `|` (if any) marks the start of the expanded-id list.
            val rest = line.substring(firstPipe + 1)
            val parts = rest.split('|', limit = 2)
            val graph = unescape(parts[0])
            val expanded = if (parts.size == 2) {
                parts[1].split(',').filter { it.isNotBlank() }.toSet()
            } else emptySet()
            return ChainPreset(name, graph, expanded)
        }

        fun encodeList(presets: List<ChainPreset>): String =
            presets.joinToString("\n") { it.encodeLine() }

        fun decodeList(encoded: String): List<ChainPreset> {
            if (encoded.isBlank()) return emptyList()
            return encoded.split("\n").mapNotNull { decodeLine(it) }
        }

        private fun escape(s: String): String =
            s.replace("\\", "\\\\")
                .replace("|", "\\p")
                .replace("\n", "\\n")
                .replace("\r", "")

        private fun unescape(s: String): String {
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
    }
}

/**
 * Built-in chain presets. Each composes the existing per-effect FACTORY
 * states into a complete linear chain — guarantees the user always has a
 * sensible starting point even before they save anything of their own.
 */
object FactoryChainPresets {

    /**
     * The "Voice Quality" chain — Input → noise-gate → de-clipper →
     * Compressor → air-enhancer → de-rustle → Equalizer → Reverb →
     * Output. Encoded with the canonical plugin display names matching
     * the bundled-plugins manifests, so it Just Works on a fresh
     * install where [JsPluginLibrary.installBundled] has registered
     * the native plugins.
     */
    val voiceQuality: ChainPreset = run {
        // Plugin IDs match the manifest.json `id` field. The
        // NativePluginEngine registers under that id, and the encoded
        // state's pluginName is looked up by id. Display labels on the
        // graph nodes can still read "Noise Gate" etc.
        //
        // EQ defaults to "Voice Clarity": rumble down + boxy mids out
        // + air shelf up. That's the explicit shape the user asked for
        // in v92 testing — "tīrās skaņas paceļas, trokšnainais vidus
        // mazinas, rumble utt."
        val graph = EffectGraph.voiceQualityChain(
            noiseGateId   = "noise-gate",
            deClipperId   = "de-clipper",
            airEnhancerId = "air-enhancer",
            eqEncoded     = (preset(EqState.FACTORY, "Voice Clarity") ?: EqState.FLAT).encode(),
            compEncoded   = (preset(CompressorState.FACTORY, "Vocals Mild") ?: CompressorState.DEFAULT).encode(),
            reverbEncoded = (preset(ReverbState.FACTORY, "Subtle Air") ?: ReverbState.DEFAULT).encode(),
        )
        ChainPreset("Voice Quality", EffectGraphCodec.encode(graph))
    }

    val presets: List<ChainPreset> = listOf(
        voiceQuality,
        build(
            name = "Clean Vocals",
            pc = null,                                         // no auto-tune
            eq = preset(EqState.FACTORY, "Vocal Lift"),
            comp = preset(CompressorState.FACTORY, "Vocals Mild"),
            reverb = preset(ReverbState.FACTORY, "Subtle Air"),
        ),
        build(
            name = "Auto-tuned Pop",
            pc = preset(PitchCorrectState.FACTORY, "Studio"),
            eq = preset(EqState.FACTORY, "Bright"),
            comp = preset(CompressorState.FACTORY, "Vocals Tight"),
            reverb = preset(ReverbState.FACTORY, "Vocal Plate"),
        ),
        build(
            name = "Cher Effect",
            pc = preset(PitchCorrectState.FACTORY, "Cher Effect"),
            eq = preset(EqState.FACTORY, "Bright"),
            comp = preset(CompressorState.FACTORY, "Vocals Tight"),
            reverb = preset(ReverbState.FACTORY, "Vocal Plate"),
        ),
        build(
            name = "Telephone",
            pc = null,
            eq = preset(EqState.FACTORY, "Telephone"),
            comp = preset(CompressorState.FACTORY, "Heavy"),
            reverb = null,
        ),
        build(
            name = "Cathedral",
            pc = null,
            eq = preset(EqState.FACTORY, "Vocal Lift"),
            comp = preset(CompressorState.FACTORY, "Light Glue"),
            reverb = preset(ReverbState.FACTORY, "Cathedral"),
        ),
        build(
            name = "Dry & Direct",
            pc = null,
            eq = null,
            comp = null,
            reverb = null,
        ),
    )

    private fun <S> preset(list: List<NamedPreset<S>>, name: String): S? =
        list.firstOrNull { it.name == name }?.state

    private fun build(
        name: String,
        pc: PitchCorrectState?,
        eq: EqState?,
        comp: CompressorState?,
        reverb: ReverbState?,
    ): ChainPreset {
        val graph = EffectGraph.default(
            pcEncoded = (pc ?: PitchCorrectState.DEFAULT).encode(),
            eqEncoded = (eq ?: EqState.FLAT).encode(),
            compEncoded = (comp ?: CompressorState.DEFAULT).encode(),
            reverbEncoded = (reverb ?: ReverbState.DEFAULT).encode(),
        )
        return ChainPreset(name, EffectGraphCodec.encode(graph))
    }
}
