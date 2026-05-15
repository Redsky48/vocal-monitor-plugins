package com.vocalmonitor.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Single-band biquad notch (RBJ cookbook). Designed for surgical removal
 * of a narrow tone — paired with the metronome's Pure-tone sound, the
 * notch sits exactly on the tone frequency so the click never makes it
 * into the recording while voice on either side passes through untouched.
 *
 * Q values of 20–40 give a notch a few Hz wide at audio frequencies,
 * with effectively zero passband ripple. Coefficients are recomputed
 * lazily whenever [freqHz], [q], or [sampleRate] change.
 */
class BiquadNotch(
    var sampleRate: Int = 44100,
    freqHz: Float = 6000f,
    q: Float = 15f,
) {
    var freqHz: Float = freqHz
        set(value) { if (field != value) { field = value; dirty = true } }
    var q: Float = q
        set(value) { if (field != value) { field = value; dirty = true } }

    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f
    private var dirty = true

    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }

    /** Mono in-place (Direct Form I). Recomputes coefficients if dirty. */
    fun processInPlace(buf: ShortArray, count: Int) {
        if (dirty) recompute()
        for (i in 0 until count) {
            val x = buf[i].toFloat()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            buf[i] = y.toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun recompute() {
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * q.coerceAtLeast(0.5f))).toFloat()
        val a0 = 1f + alpha
        b0 = 1f / a0
        b1 = -2f * cosW / a0
        b2 = 1f / a0
        a1 = -2f * cosW / a0
        a2 = (1f - alpha) / a0
        dirty = false
        reset()
    }
}
