package com.vocalmonitor.audio

import kotlin.math.sqrt

/**
 * YIN pitch detection (de Cheveigné & Kawahara, 2002).
 *
 * Step 1: difference function
 * Step 2: cumulative mean normalised difference function (CMNDF)
 * Step 3: absolute threshold to pick first dip
 * Step 4: parabolic interpolation around the chosen dip
 *
 * Tuned for vocal range (~70 Hz – 1100 Hz). Returns frequency in Hz, or
 * `null` if the buffer is too noisy or the signal is silent.
 */
class PitchDetector(
    private val sampleRate: Int,
    private val bufferSize: Int,
    private val threshold: Float = 0.15f,
    private val minFreq: Float = 70f,
    private val maxFreq: Float = 1100f,
    /** Below this RMS the buffer is considered silent. */
    private val silenceRms: Float = 0.0025f,
) {
    private val halfBuffer = bufferSize / 2
    private val diff = FloatArray(halfBuffer)
    private val cmndf = FloatArray(halfBuffer)

    private val tauMin = (sampleRate / maxFreq).toInt().coerceAtLeast(2)
    private val tauMax = (sampleRate / minFreq).toInt().coerceAtMost(halfBuffer - 1)

    /** @return detected frequency in Hz, or null if no clean pitch. */
    fun detect(samples: FloatArray): Float? {
        if (samples.size < bufferSize) return null

        var sumSq = 0.0
        for (i in 0 until bufferSize) sumSq += samples[i] * samples[i]
        val rms = sqrt(sumSq / bufferSize).toFloat()
        if (rms < silenceRms) return null

        // Difference function
        for (tau in 0 until halfBuffer) {
            var sum = 0f
            for (i in 0 until halfBuffer) {
                val d = samples[i] - samples[i + tau]
                sum += d * d
            }
            diff[tau] = sum
        }

        // CMNDF
        cmndf[0] = 1f
        var running = 0f
        for (tau in 1 until halfBuffer) {
            running += diff[tau]
            cmndf[tau] = if (running == 0f) 1f else diff[tau] * tau / running
        }

        // Absolute threshold within tau range
        var tauEstimate = -1
        var t = tauMin
        while (t < tauMax) {
            if (cmndf[t] < threshold) {
                while (t + 1 < tauMax && cmndf[t + 1] < cmndf[t]) t++
                tauEstimate = t
                break
            }
            t++
        }
        if (tauEstimate < 0) return null

        // Parabolic interpolation
        val betterTau: Float = if (tauEstimate > 0 && tauEstimate < halfBuffer - 1) {
            val s0 = cmndf[tauEstimate - 1]
            val s1 = cmndf[tauEstimate]
            val s2 = cmndf[tauEstimate + 1]
            val denom = 2f * (2f * s1 - s2 - s0)
            if (denom == 0f) tauEstimate.toFloat()
            else tauEstimate + (s2 - s0) / denom
        } else tauEstimate.toFloat()

        return sampleRate / betterTau
    }
}
