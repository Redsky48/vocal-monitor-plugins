package com.vocalmonitor.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Single-channel feed-forward peak compressor with soft knee, smoothed gain
 * envelope (attack/release), and makeup gain — i.e. the parameters every
 * studio dynamics processor exposes.
 *
 * Use [process] to filter a 16-bit PCM byte buffer in place. Coefficients are
 * recomputed automatically when parameters are mutated, so it's safe to write
 * gainReductionDb / threshold / etc. live from another thread (each chunk
 * sees the latest values; the previous envelope is carried across).
 */
class Compressor(val sampleRate: Int = 44100) {

    /** Threshold in dBFS. Signal above this gets attenuated. */
    var thresholdDb: Float = -18f
        set(value) { field = value.coerceIn(-60f, 0f) }

    /** Compression ratio. 1.0 = no compression, ∞ = limiter. */
    var ratio: Float = 4f
        set(value) { field = value.coerceIn(1f, 40f) }

    /** Attack time in milliseconds. */
    var attackMs: Float = 5f
        set(value) {
            field = value.coerceIn(0.1f, 500f)
            attackCoef = computeCoef(field)
        }

    /** Release time in milliseconds. */
    var releaseMs: Float = 120f
        set(value) {
            field = value.coerceIn(5f, 3000f)
            releaseCoef = computeCoef(field)
        }

    /** Knee width in dB (soft transition around the threshold). */
    var kneeWidthDb: Float = 6f
        set(value) { field = value.coerceIn(0f, 24f) }

    /** Makeup gain in dB applied after compression. */
    var makeupGainDb: Float = 4f
        set(value) { field = value.coerceIn(0f, 24f) }

    private var attackCoef: Float = computeCoef(attackMs)
    private var releaseCoef: Float = computeCoef(releaseMs)
    private var envelopeDb: Float = 0f

    /** Last computed gain reduction in dB — exposed for UI meters. */
    @Volatile var lastGrDb: Float = 0f
        private set

    private fun computeCoef(timeMs: Float): Float {
        return exp(-1f / (sampleRate * (timeMs / 1000f).coerceAtLeast(1e-6f))).toFloat()
    }

    fun reset() {
        envelopeDb = 0f
        lastGrDb = 0f
    }

    /** Process a single float sample (-1..1) — useful for streaming pipelines. */
    fun processSample(input: Float): Float {
        val absSample = abs(input).coerceAtLeast(1e-6f)
        val inputDb = 20f * ln(absSample) / LN10

        val excess = inputDb - thresholdDb
        val halfKnee = kneeWidthDb / 2f
        val reductionFactor = (1f - 1f / ratio).coerceAtLeast(0f)
        val instantGr: Float = when {
            excess <= -halfKnee -> 0f
            excess >= halfKnee -> excess * reductionFactor
            else -> {
                val x = excess + halfKnee
                if (kneeWidthDb > 0f) x * x / (2f * kneeWidthDb) * reductionFactor else 0f
            }
        }

        envelopeDb = if (instantGr > envelopeDb) {
            instantGr + (envelopeDb - instantGr) * attackCoef
        } else {
            instantGr + (envelopeDb - instantGr) * releaseCoef
        }
        lastGrDb = envelopeDb

        val finalGainDb = -envelopeDb + makeupGainDb
        val gain = 10.0.pow(finalGainDb / 20.0).toFloat()
        return input * gain
    }

    /** Process a 16-bit signed PCM byte buffer in place. */
    fun process(pcm: ByteArray, length: Int = pcm.size): ByteArray {
        val sampleCount = length / 2
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s16 = (hi shl 8) or lo
            val signed = if (s16 >= 0x8000) s16 - 0x10000 else s16
            val sf = signed / 32768f

            val out = processSample(sf).coerceIn(-1f, 1f)
            val outInt = (out * 32767f).toInt()
            pcm[i * 2] = (outInt and 0xFF).toByte()
            pcm[i * 2 + 1] = ((outInt shr 8) and 0xFF).toByte()
        }
        return pcm
    }

    companion object {
        private const val LN10 = 2.302585092994046f

        /**
         * Pure helper for UI: compute the static transfer curve point for a
         * given input dB (no envelope smoothing).
         */
        fun staticOutputDb(
            inputDb: Float,
            thresholdDb: Float,
            ratio: Float,
            kneeWidthDb: Float,
            makeupGainDb: Float,
        ): Float {
            val excess = inputDb - thresholdDb
            val halfKnee = kneeWidthDb / 2f
            val reductionFactor = (1f - 1f / ratio).coerceAtLeast(0f)
            val gr: Float = when {
                excess <= -halfKnee -> 0f
                excess >= halfKnee -> excess * reductionFactor
                else -> {
                    val x = excess + halfKnee
                    if (kneeWidthDb > 0f) x * x / (2f * kneeWidthDb) * reductionFactor else 0f
                }
            }
            return inputDb - gr + makeupGainDb
        }
    }
}
