package com.vocalmonitor.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Single-channel feed-forward noise gate.
 *
 * When the signal envelope drops below [thresholdDb] for longer than the
 * [holdMs] grace period, the gate ducks the output by [rangeDb] (use a
 * large negative number — e.g. -60 dB — to mute completely). Attack and
 * release smooth the transitions so a fast plosive or "p" doesn't slice
 * cleanly into silence.
 *
 * The threshold uses **hysteresis**: the gate opens above [thresholdDb]
 * and stays open until the signal drops [hysteresisDb] below it. Without
 * hysteresis a signal sitting right at the threshold chatters open/closed
 * at every sample.
 */
class NoiseGate(val sampleRate: Int = 44100) {

    /** Open threshold in dBFS. Signal below this gets attenuated. */
    var thresholdDb: Float = -40f
        set(value) { field = value.coerceIn(-80f, 0f) }

    /** How far below [thresholdDb] the close threshold sits. */
    var hysteresisDb: Float = 3f
        set(value) { field = value.coerceIn(0f, 24f) }

    /** Attack time in milliseconds — how fast the gate opens. */
    var attackMs: Float = 5f
        set(value) {
            field = value.coerceIn(0.1f, 500f)
            attackCoef = computeCoef(field)
        }

    /** Release time in milliseconds — how fast the gate closes. */
    var releaseMs: Float = 120f
        set(value) {
            field = value.coerceIn(5f, 3000f)
            releaseCoef = computeCoef(field)
        }

    /** Minimum hold time in ms — gate stays open at least this long after opening. */
    var holdMs: Float = 30f
        set(value) {
            field = value.coerceIn(0f, 1000f)
            holdSamples = (sampleRate * field / 1000f).toInt()
        }

    /**
     * Amount of attenuation when the gate is closed, in dB. -60 dB ≈
     * silent; -10 dB ducks the noise without fully muting (useful when
     * you want room tone preserved).
     */
    var rangeDb: Float = -60f
        set(value) { field = value.coerceIn(-90f, 0f) }

    private var attackCoef: Float = computeCoef(attackMs)
    private var releaseCoef: Float = computeCoef(releaseMs)
    private var holdSamples: Int = (sampleRate * holdMs / 1000f).toInt()

    private var gainDb: Float = 0f   // 0 = open, rangeDb = closed
    private var holdCounter: Int = 0
    private var open: Boolean = false

    /** Most recent gate gain in dB — exposed for UI meters. */
    @Volatile var lastGainDb: Float = 0f
        private set

    private fun computeCoef(timeMs: Float): Float {
        return exp(-1f / (sampleRate * (timeMs / 1000f).coerceAtLeast(1e-6f))).toFloat()
    }

    fun reset() {
        gainDb = rangeDb
        holdCounter = 0
        open = false
        lastGainDb = rangeDb
    }

    fun processSample(input: Float): Float {
        val absSample = abs(input).coerceAtLeast(1e-6f)
        val inputDb = 20f * ln(absSample) / LN10

        val openThr = thresholdDb
        val closeThr = thresholdDb - hysteresisDb
        if (inputDb > openThr) {
            open = true
            holdCounter = holdSamples
        } else if (inputDb < closeThr) {
            if (holdCounter > 0) holdCounter--
            if (holdCounter == 0) open = false
        }

        // Target: 0 dB when open, rangeDb when closed. Smooth via attack
        // (open direction) or release (close direction) coefficients.
        val targetDb = if (open) 0f else rangeDb
        val coef = if (open) attackCoef else releaseCoef
        gainDb = targetDb + (gainDb - targetDb) * coef
        lastGainDb = gainDb

        val gain = 10.0.pow(gainDb / 20.0).toFloat()
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
    }
}
