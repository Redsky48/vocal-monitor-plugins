package com.vocalmonitor.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 in-place complex FFT (Cooley-Tukey). Reusable across frames so we
 * keep one instance with pre-computed twiddles and bit-reversal indices.
 *
 * [size] must be a power of two.
 */
class Fft(val size: Int) {

    init {
        require(size > 0 && (size and (size - 1)) == 0) {
            "FFT size must be power of 2, got $size"
        }
    }

    private val cosTable: FloatArray
    private val sinTable: FloatArray
    private val bitRev: IntArray

    init {
        val half = size / 2
        cosTable = FloatArray(half)
        sinTable = FloatArray(half)
        for (k in 0 until half) {
            val a = -2.0 * PI * k / size
            cosTable[k] = cos(a).toFloat()
            sinTable[k] = sin(a).toFloat()
        }
        bitRev = IntArray(size)
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            bitRev[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    /** Forward in-place FFT. [re] and [im] must be size [size]. */
    fun forward(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size)
        // Bit-reverse permutation
        for (i in 0 until size) {
            val j = bitRev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // Butterflies
        var stride = 2
        while (stride <= size) {
            val half = stride / 2
            val twiddleStep = size / stride
            for (start in 0 until size step stride) {
                var k = 0
                for (j in 0 until half) {
                    val tr = cosTable[k] * re[start + j + half] - sinTable[k] * im[start + j + half]
                    val ti = cosTable[k] * im[start + j + half] + sinTable[k] * re[start + j + half]
                    re[start + j + half] = re[start + j] - tr
                    im[start + j + half] = im[start + j] - ti
                    re[start + j] += tr
                    im[start + j] += ti
                    k += twiddleStep
                }
            }
            stride *= 2
        }
    }

    /** Inverse FFT: forward on conjugate, then conjugate + scale by 1/N. */
    fun inverse(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) im[i] = -im[i]
        forward(re, im)
        val invN = 1f / size
        for (i in 0 until size) {
            re[i] *= invN
            im[i] = -im[i] * invN
        }
    }

    companion object {
        /** Hann window of [size] samples (sym = true is what overlap-add wants). */
        fun hannWindow(size: Int): FloatArray {
            val w = FloatArray(size)
            for (i in 0 until size) {
                w[i] = (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
            }
            return w
        }
    }
}
