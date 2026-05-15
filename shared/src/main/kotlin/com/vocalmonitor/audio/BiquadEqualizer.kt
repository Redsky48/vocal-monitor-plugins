package com.vocalmonitor.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 10-band biquad peak EQ. Each band is a constant-Q peaking filter at a fixed
 * frequency; the gain (dB) per band is read from [BAND_FREQUENCIES].
 *
 * Use [process] to filter a buffer of 16-bit signed PCM samples in place.
 */
class BiquadEqualizer(
    private val sampleRate: Int = 44100,
    /** Q factor — 1.4 = roughly 2/3-octave bands, common for 10-band EQs. */
    private val q: Double = 1.4,
) {
    /** Gains per band, in dB. Length must equal [BAND_COUNT]. */
    var gainsDb: FloatArray = FloatArray(BAND_COUNT)
        set(value) {
            require(value.size == BAND_COUNT) { "expected $BAND_COUNT bands" }
            field = value
            recompute()
            reset()
        }

    private val a1 = DoubleArray(BAND_COUNT)
    private val a2 = DoubleArray(BAND_COUNT)
    private val b0 = DoubleArray(BAND_COUNT)
    private val b1 = DoubleArray(BAND_COUNT)
    private val b2 = DoubleArray(BAND_COUNT)
    private val z1 = DoubleArray(BAND_COUNT)
    private val z2 = DoubleArray(BAND_COUNT)

    init {
        recompute()
    }

    /** Re-derive coefficients from the current [gainsDb]. */
    private fun recompute() {
        for (i in 0 until BAND_COUNT) {
            val freq = BAND_FREQUENCIES[i].toDouble()
            val gain = gainsDb[i].toDouble()
            // Avoid spending CPU on near-flat bands
            if (kotlin.math.abs(gain) < 0.05) {
                b0[i] = 1.0; b1[i] = 0.0; b2[i] = 0.0
                a1[i] = 0.0; a2[i] = 0.0
                continue
            }
            // Robert Bristow-Johnson "audio EQ cookbook" peaking EQ
            val a = 10.0.pow(gain / 40.0)
            val w0 = 2.0 * PI * freq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)

            val a0c = 1.0 + alpha / a
            b0[i] = (1.0 + alpha * a) / a0c
            b1[i] = (-2.0 * cosW0) / a0c
            b2[i] = (1.0 - alpha * a) / a0c
            a1[i] = (-2.0 * cosW0) / a0c
            a2[i] = (1.0 - alpha / a) / a0c
        }
    }

    /** Clear filter state (call before processing a new audio source). */
    fun reset() {
        for (i in 0 until BAND_COUNT) { z1[i] = 0.0; z2[i] = 0.0 }
    }

    /**
     * Filter one sample through all bands in series. Public so callers that
     * already have float samples can avoid the per-call int conversion in
     * [process].
     */
    fun processSample(input: Float): Float {
        var x = input.toDouble()
        for (i in 0 until BAND_COUNT) {
            val y = b0[i] * x + z1[i]
            z1[i] = b1[i] * x - a1[i] * y + z2[i]
            z2[i] = b2[i] * x - a2[i] * y
            x = y
        }
        return x.toFloat()
    }

    /**
     * Filter a 16-bit signed PCM byte buffer (little-endian) in place. Returns
     * the same array for convenience. [length] limits processing to the first
     * N bytes — useful when streaming with a reused chunk buffer.
     */
    fun process(pcm: ByteArray, length: Int = pcm.size): ByteArray {
        val sampleCount = length / 2
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s16 = (hi shl 8) or lo
            val signed = if (s16 >= 0x8000) s16 - 0x10000 else s16
            val sf = signed / 32768f

            val out = processSample(sf)
            // Soft clip to avoid hard distortion when EQ pushes a band over 0dBFS
            val clamped = out.coerceIn(-1f, 1f)
            val outInt = (clamped * 32767f).toInt()
            pcm[i * 2] = (outInt and 0xFF).toByte()
            pcm[i * 2 + 1] = ((outInt shr 8) and 0xFF).toByte()
        }
        return pcm
    }

    companion object {
        const val BAND_COUNT = 10
        /** ISO octave-band centre frequencies in Hz. */
        val BAND_FREQUENCIES = intArrayOf(
            31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
        )
        val BAND_LABELS = arrayOf(
            "31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"
        )
    }
}
