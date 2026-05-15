package com.vocalmonitor.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Group a per-hop F0 trajectory into note segments. Two heuristics:
 *
 *   - Voicing breaks (detected == 0) split segments.
 *   - A semitone jump > [splitSemitones] also splits — the singer moved to
 *     a different note rather than vibrato-ing on the current one.
 *
 * Each surviving segment is then averaged → mean MIDI → rounded to a target
 * note (the segmenter's auto-suggestion). The user can override that target
 * per segment in [PitchEditorSheet].
 *
 * Segments shorter than [minHops] hops are merged with their neighbour or
 * dropped — they're typically detection noise, not real notes.
 */
object PitchSegmenter {

    data class Segment(
        /** First hop index (inclusive). */
        val startHop: Int,
        /** Last hop index (inclusive). */
        val endHop: Int,
        /** Mean detected MIDI over the segment. */
        val meanMidi: Float,
        /**
         * Target MIDI as a fractional value. The editor's snap mode rounds this
         * for display + render; free-drag mode keeps the cents precision.
         */
        val targetMidi: Float,
        /**
         * Synthesised vibrato amplitude in cents. The renderer adds a sine
         * oscillation of ± this many cents on top of the target. 0 = no
         * added wobble (the natural mic vibrato survives unless the user
         * is in Perfect snap mode, which flattens everything to targetMidi).
         */
        val vibratoDepthCents: Float = 0f,
        /** Vibrato sine rate in Hz. Typical singing vibrato is 4-7 Hz. */
        val vibratoRateHz: Float = 5f,
        /**
         * Glide-in time in milliseconds. The render layer ramps from the
         * previous-segment's end pitch to this segment's target over this
         * window — so 0 ms is a hard step (staccato cut) and 200 ms is a
         * smooth portamento between notes. 50 ms is a tasteful default
         * that feels natural without slurring.
         */
        val glideMs: Float = 0f,
        /**
         * Optional per-hop MIDI override curve drawn by the user in the
         * Section Detail Editor. `null` means "no per-hop edits, use the
         * snap-mode + vibrato + glide formula". When present, the array
         * is exactly [lengthHops] long and EACH index is a fractional
         * MIDI value applied to that hop, replacing the snap-mode output
         * entirely (vibrato is still added on top so depth > 0 still
         * wobbles, but the carrier is the drawn curve, not targetMidi).
         *
         * Stored as a nullable FloatArray for cheap per-hop access; the
         * encoder serialises it as a `|`-separated string inside the
         * segment's comma-separated record.
         */
        val hopOverrides: FloatArray? = null,
        /**
         * When false, the override curve is preserved in the segment data
         * but not applied to playback — the audio falls back to the auto
         * suggestion (Free-mode formula). Lets the user A/B "what I drew"
         * vs "what the algorithm would do" without losing the drawing.
         * Default true (active) so existing recordings keep current
         * behaviour after the upgrade.
         */
        val overrideActive: Boolean = true,
    ) {
        val midHop: Int get() = (startHop + endHop) / 2
        val lengthHops: Int get() = endHop - startHop + 1
        val targetMidiSnapped: Int get() = targetMidi.roundToInt()
        /** True when the segment has an override curve AND it's enabled. */
        val effectiveOverride: FloatArray?
            get() = if (overrideActive) hopOverrides else null
    }

    /**
     * One segment per line, comma-separated. Old format had 4 fields; new
     * format appends vibratoDepthCents + vibratoRateHz. [decodeList]
     * tolerates both, defaulting the new fields to "no extra vibrato".
     */
    fun encodeList(segments: List<Segment>): String =
        segments.joinToString("\n") { s ->
            val overrides = s.hopOverrides?.joinToString("|") { "%.4f".format(it) } ?: ""
            "${s.startHop},${s.endHop},${s.meanMidi},${s.targetMidi}," +
                "${s.vibratoDepthCents},${s.vibratoRateHz},${s.glideMs},$overrides," +
                "${s.overrideActive}"
        }

    fun decodeList(text: String): List<Segment> {
        if (text.isBlank()) return emptyList()
        return text.split('\n').mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size < 4) return@mapNotNull null
            val s = parts[0].toIntOrNull() ?: return@mapNotNull null
            val e = parts[1].toIntOrNull() ?: return@mapNotNull null
            val mean = parts[2].toFloatOrNull() ?: return@mapNotNull null
            val tgt = parts[3].toFloatOrNull() ?: return@mapNotNull null
            val vDepth = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
            val vRate = parts.getOrNull(5)?.toFloatOrNull() ?: 5f
            val glide = parts.getOrNull(6)?.toFloatOrNull() ?: 0f
            val overrideStr = parts.getOrNull(7) ?: ""
            val overrides = if (overrideStr.isBlank()) null
                else overrideStr.split('|').mapNotNull { it.toFloatOrNull() }
                    .toFloatArray().takeIf { it.isNotEmpty() }
            val overrideActive = parts.getOrNull(8)?.toBooleanStrictOrNull() ?: true
            Segment(s, e, mean, tgt, vDepth, vRate, glide, overrides, overrideActive)
        }
    }

    fun segment(
        analysis: PitchCorrector.Analysis,
        settings: PitchCorrector.Settings,
        splitSemitones: Float = 0.6f,
        // Slides between notes often climb < splitSemitones per hop, so the
        // hop-to-hop check alone misses legato transitions and merges two
        // notes into one segment. Also split when the current hop drifts
        // > driftSemitones from the segment's anchor mean. Threshold sits
        // above typical vibrato amplitude (~30-50¢) so wobble is preserved.
        driftSemitones: Float = 0.8f,
        minHops: Int = 3,
    ): List<Segment> {
        val hops = analysis.hops
        if (hops.isEmpty()) return emptyList()

        val raw = ArrayList<MutableList<Int>>()
        var current: MutableList<Int>? = null
        var lastMidi = Float.NaN
        // Anchor = running mean of first [anchorWindow] hops of the segment,
        // then frozen. Once frozen it's a stable reference point that a
        // slide can drift away from even when each hop-to-hop step is tiny.
        val anchorWindow = 5
        var anchorMidi = 0f
        var anchorCount = 0

        for (i in hops.indices) {
            val f = hops[i].detectedHz
            if (f <= 0f) {
                current = null
                lastMidi = Float.NaN
                anchorMidi = 0f
                anchorCount = 0
                continue
            }
            val midi = Notes.freqToMidi(f.toDouble()).toFloat()
            val jump = !lastMidi.isNaN() && abs(midi - lastMidi) > splitSemitones
            val drift = anchorCount > 0 && abs(midi - anchorMidi) > driftSemitones
            if (current == null || lastMidi.isNaN() || jump || drift) {
                current = arrayListOf(i)
                raw.add(current)
                anchorMidi = midi
                anchorCount = 1
            } else {
                current.add(i)
                if (anchorCount < anchorWindow) {
                    anchorMidi = (anchorMidi * anchorCount + midi) / (anchorCount + 1)
                    anchorCount++
                }
            }
            lastMidi = midi
        }

        // Drop too-short segments (detection noise)
        val kept = raw.filter { it.size >= minHops }
        return kept.map { idxList ->
            val midis = idxList.map { Notes.freqToMidi(hops[it].detectedHz.toDouble()).toFloat() }
            val mean = midis.average().toFloat()
            // Snap mean to nearest scale tone (uses corrector's snap logic for parity)
            val snappedHz = PitchCorrector.snap(
                Notes.midiToFreq(mean.toDouble()).toFloat(),
                settings,
            )
            // Default target = detected mean. The editor opens with each
            // note sitting exactly where the singer landed it — no implicit
            // auto-correction. The user opts in to correction via the
            // "Auto-pitch" dropdown or by dragging individual notes.
            Segment(
                startHop = idxList.first(),
                endHop = idxList.last(),
                meanMidi = mean,
                targetMidi = mean,
            )
        }
    }

    /**
     * Build a per-hop override array (one target Hz per analysis hop) from the
     * segment list — feed this to [PitchCorrector.renderWithAnalysis].
     *
     * Hops outside any segment get 0f (= "leave it alone, use the auto snap").
     */
    /**
     * Compute the auto-suggested per-hop MIDI curve for a single segment.
     * Same Free-mode formula as [buildOverride] but returns MIDI directly
     * for visualization, not Hz. Used by the editor canvases to draw the
     * "blue line" — what the algorithm would output if the user installed
     * no manual override. Vibrato + glide bake in here so the line wobbles
     * exactly like the playback will.
     *
     * [prevTarget] anchors the glide ramp at the start of the segment. The
     * detail dialog has no easy access to its sibling segments and falls
     * back to the segment's own target, which means glide visualises as
     * "no ramp" in isolation — correct for a single-note preview.
     */
    fun computeAutoSuggestionMidi(
        analysis: PitchCorrector.Analysis,
        seg: Segment,
        prevTarget: Float = seg.targetMidi,
    ): FloatArray {
        // Suggestion line is the algorithm's "raw" carrier — pitch
        // corrected, glide-shaped, but explicitly WITHOUT vibrato. The
        // detail editor's vibrato slider should only modulate the user's
        // override (yellow); the suggestion (blue) stays as the steady
        // reference the user is drawing against, so the visual contract
        // is "blue = where the engine thinks the note is, yellow = where
        // you've taken it (including any wobble you've dialed in)".
        val hopTimeSec = analysis.hopSamples.toFloat() /
            analysis.sampleRate.coerceAtLeast(1).toFloat()
        val glideHops = (seg.glideMs / 1000f / hopTimeSec.coerceAtLeast(1e-4f))
            .toInt().coerceAtLeast(0)
        val rawOffset = seg.targetMidi - seg.meanMidi
        val prevOffset = prevTarget - seg.meanMidi
        return FloatArray(seg.lengthHops) { i ->
            val hopIdx = seg.startHop + i
            val origHz = analysis.hops.getOrNull(hopIdx)?.detectedHz ?: 0f
            val origMidi = if (origHz > 0f)
                Notes.freqToMidi(origHz.toDouble()).toFloat() else seg.targetMidi
            val gm = if (glideHops <= 0 || i >= glideHops) 1f
                else (i.toFloat() / glideHops).coerceIn(0f, 1f)
            val effOffset = prevOffset + (rawOffset - prevOffset) * gm
            origMidi + effOffset
        }
    }

    /**
     * Build per-hop target Hz array. Contour-preserving: each segment's target
     * is its mean pitch + an offset, applied as a per-hop offset on top of the
     * originally-detected pitch. This keeps natural vibrato/portamento intact
     * — the segment slides as a unit instead of being flattened to one
     * constant pitch (which would sound like classic auto-tune).
     *
     * Hops outside any segment receive 0f → corrector treats those as "leave
     * detected pitch alone".
     */
    fun buildOverride(
        analysis: PitchCorrector.Analysis,
        segments: List<Segment>,
        snap: Boolean = false,
    ): FloatArray = buildOverride(
        analysis, segments,
        if (snap) SnapMode.Drift else SnapMode.Free,
    )

    /**
     * Three semantically distinct ways of mapping a segment's user-chosen
     * [Segment.targetMidi] back onto the detected per-hop pitches:
     *
     *  - [Free]    each hop = origMidi + (targetMidi − meanMidi). Vibrato
     *              and glide preserved exactly; the segment just slides.
     *
     *  - [Drift]   round the offset to an integer semitone before applying.
     *              Vibrato still preserved, but the segment shifts in
     *              musical steps. (Historically "snap=true".)
     *
     *  - [Perfect] every hop in the segment is FORCED to targetMidi —
     *              no vibrato, no glide. Hard-tune / T-Pain style.
     *              "Snap to perfect pitch" UX checkbox.
     */
    enum class SnapMode { Free, Drift, Perfect }

    fun buildOverride(
        analysis: PitchCorrector.Analysis,
        segments: List<Segment>,
        mode: SnapMode,
    ): FloatArray {
        val out = FloatArray(analysis.hops.size)
        val hopTimeSec = analysis.hopSamples.toFloat() /
            analysis.sampleRate.coerceAtLeast(1).toFloat()
        for ((segIdx, seg) in segments.withIndex()) {
            // Glide window — first N hops of the segment ramp from the
            // previous segment's end MIDI to this segment's target. 0 ms
            // = hard step, 200 ms = full portamento.
            val glideHops = (seg.glideMs / 1000f / hopTimeSec.coerceAtLeast(1e-4f)).toInt()
                .coerceAtLeast(0)
            val prevSeg = segments.getOrNull(segIdx - 1)
            val prevTarget = prevSeg?.targetMidi ?: seg.targetMidi

            /** Fraction 0..1 of the way the glide has resolved at hop [i]. */
            fun glideMix(i: Int): Float {
                if (glideHops <= 0) return 1f
                val into = i - seg.startHop
                if (into >= glideHops) return 1f
                return (into.toFloat() / glideHops).coerceIn(0f, 1f)
            }
            // Per-segment vibrato: synthesised sine on top of whatever
            // target the snap mode produces. Depth 0 = no-op (the
            // multiplication folds out). Note that in Perfect mode the
            // detected vibrato is GONE, so this synthesised one is the
            // only motion the segment will carry — useful for hard-tuned
            // takes that you still want to feel human.
            val vibratoSemi = seg.vibratoDepthCents / 100f
            val vibratoOmega = (2.0 * Math.PI * seg.vibratoRateHz).toFloat()
            fun vibratoAtHop(i: Int): Float {
                if (vibratoSemi == 0f) return 0f
                val t = (i - seg.startHop) * hopTimeSec
                return vibratoSemi * kotlin.math.sin(vibratoOmega * t)
            }
            // Free-draw curve from the Section Detail Editor wins outright:
            // each hop's pitch is whatever the user drew, plus the still-
            // active per-segment vibrato (so depth+rate keep working on
            // top of a custom carrier). Honour the override-active flag —
            // when the user toggles the curve off in the detail editor,
            // [Segment.effectiveOverride] returns null and we fall through
            // to the snap-mode formula instead.
            val overrides = seg.effectiveOverride
            if (overrides != null && overrides.isNotEmpty()) {
                for (i in seg.startHop..seg.endHop) {
                    if (i !in out.indices) continue
                    if (analysis.hops[i].detectedHz <= 0f) continue
                    val idx = (i - seg.startHop).coerceAtMost(overrides.size - 1)
                    val midi = overrides[idx] + vibratoAtHop(i)
                    out[i] = Notes.midiToFreq(midi.toDouble()).toFloat()
                }
                continue
            }
            when (mode) {
                SnapMode.Perfect -> {
                    for (i in seg.startHop..seg.endHop) {
                        if (i !in out.indices) continue
                        if (analysis.hops[i].detectedHz <= 0f) continue
                        val gm = glideMix(i)
                        val target = prevTarget + (seg.targetMidi - prevTarget) * gm
                        val midi = target + vibratoAtHop(i)
                        out[i] = Notes.midiToFreq(midi.toDouble()).toFloat()
                    }
                }
                SnapMode.Free, SnapMode.Drift -> {
                    val rawOffset = seg.targetMidi - seg.meanMidi
                    val offset = if (mode == SnapMode.Drift)
                        rawOffset.roundToInt().toFloat() else rawOffset
                    val prevOffset = (prevSeg?.let { it.targetMidi - it.meanMidi } ?: offset).let {
                        if (mode == SnapMode.Drift) it.roundToInt().toFloat() else it
                    }
                    for (i in seg.startHop..seg.endHop) {
                        if (i !in out.indices) continue
                        val origHz = analysis.hops[i].detectedHz
                        if (origHz > 0f) {
                            val origMidi = Notes.freqToMidi(origHz.toDouble())
                            val gm = glideMix(i)
                            val effOffset = prevOffset + (offset - prevOffset) * gm
                            val midi = origMidi + effOffset + vibratoAtHop(i)
                            out[i] = Notes.midiToFreq(midi).toFloat()
                        }
                    }
                }
            }
        }
        return out
    }
}
