package com.vocalmonitor.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Snap-to-scale auto pitch correction with industry-grade post-processing.
 *
 * Pipeline:
 *   1. YIN F0 per analysis hop.
 *   2. **Median-of-5 octave-error filter** — kills the classic YIN "drop an
 *      octave" glitch by replacing single-frame outliers with the local
 *      median.
 *   3. **Confidence gating** — frames whose neighbourhood pitch standard
 *      deviation exceeds a threshold are treated as unvoiced (no shift).
 *   4. **Constant-power ratio smoothing** — geometric (log) low-pass on
 *      the ratio so a portamento between snapped notes glides smoothly
 *      instead of clicking through octave boundaries.
 *   5. Phase-vocoder render with phase locking + formant preservation
 *      (see [PhaseVocoder]).
 *
 * "Strength" lerps the snap target back toward the detected pitch in the
 * geometric domain — so 60% strength always pulls the same proportion of
 * the cents-deviation out, regardless of pitch.
 */
object PitchCorrector {

    enum class Scale(val mask: Int) {
        Chromatic(0b1111_1111_1111),
        Major(   0b1010_1101_0101),
        Minor(   0b1010_1101_0011),
        Pentatonic(0b1010_0101_0101),
        Blues   (0b0100_1101_0101),
    }

    data class Settings(
        val enabled: Boolean = false,
        val strength: Float = 0.6f,
        val speedMs: Float = 35f,
        val transposeSemitones: Int = 0,
        val retuneCents: Int = 0,
        val scaleRoot: Int = 0,
        val scale: Scale = Scale.Chromatic,
        val mix: Float = 1f,
    ) {
        fun isActive(): Boolean = enabled && (mix > 0.001f)
    }

    data class HopInfo(
        val timeMs: Int,
        val detectedHz: Float,
        val snappedHz: Float,
    )

    data class Analysis(
        val hops: List<HopInfo>,
        val sampleRate: Int,
        val hopSamples: Int,
    )

    private const val FRAME_SIZE = 2048
    private const val HOP_DIV = 4
    private const val MIN_VOICED_HZ = 60f
    private const val MAX_VOICED_HZ = 1200f
    /** Median-filter window radius. Total window = 2 * RADIUS + 1 hops. */
    private const val MEDIAN_RADIUS = 2
    /**
     * Maximum acceptable inter-frame change in semitones for a "stable"
     * pitch. Values above this are treated as detection noise and flagged
     * as unvoiced for the gating stage.
     */
    private const val STABILITY_SEMI = 4f

    fun analyse(input: FloatArray, sampleRate: Int, settings: Settings): Analysis {
        val hop = FRAME_SIZE / HOP_DIV
        val detector = PitchDetector(sampleRate, FRAME_SIZE, threshold = 0.13f)
        val frame = FloatArray(FRAME_SIZE)
        val raw = ArrayList<Float>()
        val timeMsList = ArrayList<Int>()
        var t = 0
        while (t + FRAME_SIZE <= input.size) {
            System.arraycopy(input, t, frame, 0, FRAME_SIZE)
            val detected = detector.detect(frame) ?: 0f
            raw.add(if (detected in MIN_VOICED_HZ..MAX_VOICED_HZ) detected else 0f)
            timeMsList.add(((t.toLong() * 1000L) / sampleRate).toInt())
            t += hop
        }

        // Post-process raw F0 trajectory with a median filter to kill octave
        // glitches, then confidence-gate any frame whose surrounding window
        // is too erratic to trust.
        val filtered = medianFilterHz(raw)
        val gated = confidenceGate(filtered)

        val hops = ArrayList<HopInfo>(gated.size)
        for (i in gated.indices) {
            val det = gated[i]
            val snap = if (det > 0f) snap(det, settings) else 0f
            hops.add(HopInfo(timeMsList[i], det, snap))
        }
        return Analysis(hops, sampleRate, hop)
    }

    /** Median filter that ignores zeros (unvoiced). Output zero stays zero. */
    private fun medianFilterHz(raw: List<Float>): FloatArray {
        val n = raw.size
        val out = FloatArray(n)
        val window = FloatArray(MEDIAN_RADIUS * 2 + 1)
        for (i in 0 until n) {
            if (raw[i] <= 0f) { out[i] = 0f; continue }
            var count = 0
            for (j in (i - MEDIAN_RADIUS)..(i + MEDIAN_RADIUS)) {
                if (j < 0 || j >= n) continue
                val v = raw[j]
                if (v > 0f) { window[count] = v; count++ }
            }
            if (count == 0) { out[i] = 0f; continue }
            // Simple insertion sort over the small window
            for (a in 1 until count) {
                val v = window[a]
                var b = a
                while (b > 0 && window[b - 1] > v) { window[b] = window[b - 1]; b-- }
                window[b] = v
            }
            out[i] = window[count / 2]
        }
        return out
    }

    /**
     * Mark frames as unvoiced when the local pitch standard deviation
     * exceeds [STABILITY_SEMI] semitones — those are typically transients,
     * consonants, or YIN second-guessing itself.
     */
    private fun confidenceGate(filtered: FloatArray): FloatArray {
        val out = FloatArray(filtered.size)
        for (i in filtered.indices) {
            val v = filtered[i]
            if (v <= 0f) { out[i] = 0f; continue }
            // Compute std-dev of voiced neighbours
            var sum = 0f
            var sumSq = 0f
            var count = 0
            for (j in (i - MEDIAN_RADIUS)..(i + MEDIAN_RADIUS)) {
                if (j < 0 || j >= filtered.size) continue
                val n = filtered[j]
                if (n <= 0f) continue
                val midi = Notes.freqToMidi(n.toDouble()).toFloat()
                sum += midi
                sumSq += midi * midi
                count++
            }
            if (count < 2) { out[i] = v; continue }
            val mean = sum / count
            val variance = (sumSq / count - mean * mean).coerceAtLeast(0f)
            val sd = sqrt(variance)
            out[i] = if (sd > STABILITY_SEMI) 0f else v
        }
        return out
    }

    fun snap(freqHz: Float, settings: Settings): Float {
        if (freqHz <= 0f) return 0f
        val midi = Notes.freqToMidi(freqHz.toDouble())
        val rounded = midi.roundToInt()
        var best = rounded
        var bestDist = Int.MAX_VALUE
        for (d in 0..6) {
            for (sign in intArrayOf(0, -1, 1)) {
                if (sign == 0 && d != 0) continue
                val cand = rounded + sign * d
                val pc = ((cand - settings.scaleRoot).mod(12) + 12).mod(12)
                if (((settings.scale.mask shr pc) and 1) == 1) {
                    val dist = abs(cand - rounded)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = cand
                    }
                }
            }
            if (bestDist != Int.MAX_VALUE) break
        }
        val targetMidi = best + settings.transposeSemitones + settings.retuneCents / 100.0
        return Notes.midiToFreq(targetMidi).toFloat()
    }

    fun render(
        input: FloatArray,
        sampleRate: Int,
        settings: Settings,
        preview: Boolean = false,
    ): FloatArray {
        if (!settings.isActive()) return input
        val analysis = analyse(input, sampleRate, settings)
        return renderWithAnalysis(input, sampleRate, analysis, settings, override = null, preview = preview)
    }

    /**
     * @param preview When true, drops cepstral formant preservation. Saves
     * the two extra FFTs per hop that the envelope computation requires —
     * roughly 1.6× faster — with mild upper-formant smear (voice gets a hair
     * darker / brighter when transposed) as the only artefact.
     *
     * We DON'T touch the FFT overlap here. Earlier versions halved overlap
     * 4→2 for extra speed, but Hann's constant-overlap-add property only
     * holds at overlap≥4 — at 2× you get amplitude modulation at the
     * synthesis hop rate (~46 Hz @ 44.1 kHz) that's audible as a tremolo /
     * vibrato wobble. Save always runs with preview=false.
     */
    fun renderWithAnalysis(
        input: FloatArray,
        sampleRate: Int,
        analysis: Analysis,
        settings: Settings,
        override: FloatArray?,
        preview: Boolean = false,
    ): FloatArray {
        if (!settings.isActive()) return input
        // Phase vocoder with formant preservation — the voice keeps its
        // character (no chipmunk on up-shifts, no dark cave on down-shifts).
        val pv = PhaseVocoder(
            sampleRate,
            FRAME_SIZE,
            overlap = HOP_DIV,
            preserveFormants = !preview,
        )
        val hops = analysis.hops
        val hopTime = analysis.hopSamples.toFloat() / sampleRate
        // Geometric (log-domain) one-pole — equal pull-toward-target for
        // octave shifts and semitone tweaks alike. tau > 0 keeps audio glide
        // smooth; tau == 0 means snap instantly = T-Pain.
        val tau = (settings.speedMs / 1000f).coerceAtLeast(0.001f)
        val alpha = (1f - exp(-hopTime / tau).toDouble()).toFloat()

        // Compute target per hop in log-pitch space
        val logRatios = FloatArray(hops.size)
        for (i in hops.indices) {
            val h = hops[i]
            val target = if (override != null && i < override.size && override[i] > 0f) override[i]
                else h.snappedHz
            logRatios[i] = if (h.detectedHz > 0f && target > 0f) {
                // Apply strength as a fraction of the cents-deviation
                val targetWithStrength = h.detectedHz *
                    (target / h.detectedHz).pow(settings.strength)
                ln(targetWithStrength / h.detectedHz)
            } else 0f
        }

        // Smooth in log domain so big jumps don't pop. Two-pass (forward+
        // backward) for zero phase delay.
        smoothInPlaceForward(logRatios, alpha)
        smoothInPlaceBackward(logRatios, alpha)

        val ratioPerHop = FloatArray(hops.size + 4) { 1f }
        for (i in logRatios.indices) ratioPerHop[i] = exp(logRatios[i].toDouble()).toFloat()

        val shifted = pv.process(input) { idx ->
            if (idx < ratioPerHop.size) ratioPerHop[idx] else 1f
        }

        val dry = settings.mix.coerceIn(0f, 1f)
        if (dry >= 0.999f) return shifted
        val out = FloatArray(input.size)
        for (i in input.indices) {
            out[i] = shifted[i] * dry + input[i] * (1f - dry)
        }
        return out
    }

    private fun smoothInPlaceForward(arr: FloatArray, alpha: Float) {
        if (arr.isEmpty()) return
        var v = arr[0]
        for (i in arr.indices) {
            v += alpha * (arr[i] - v)
            arr[i] = v
        }
    }

    private fun smoothInPlaceBackward(arr: FloatArray, alpha: Float) {
        if (arr.isEmpty()) return
        var v = arr.last()
        for (i in arr.indices.reversed()) {
            v += alpha * (arr[i] - v)
            arr[i] = v
        }
    }

    fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val n = pcm.size / 2
        val out = FloatArray(n)
        var p = 0
        for (i in 0 until n) {
            val lo = pcm[p].toInt() and 0xFF
            val hi = pcm[p + 1].toInt()
            val s = (hi shl 8) or lo
            val signed = if (s >= 0x8000) s - 0x10000 else s
            out[i] = signed / 32768f
            p += 2
        }
        return out
    }

    fun floatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var p = 0
        for (i in samples.indices) {
            val v = (samples[i] * 32767f).coerceIn(-32768f, 32767f).toInt()
            out[p] = (v and 0xFF).toByte()
            out[p + 1] = ((v shr 8) and 0xFF).toByte()
            p += 2
        }
        return out
    }
}
