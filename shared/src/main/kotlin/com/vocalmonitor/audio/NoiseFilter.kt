package com.vocalmonitor.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Single-channel spectral-domain noise reducer.
 *
 * Algorithm: Wiener filter with **decision-directed a-priori SNR estimation**
 * (Ephraim & Malah, simplified). For each frame:
 *
 *   1. Forward FFT of windowed input.
 *   2. Power spectrum |X|² → a-posteriori SNR γ = |X|²/λ_d, where λ_d is the
 *      noise PSD baked from the calibration sample.
 *   3. A-priori SNR ξ via decision-directed:
 *           ξ = α · (G_prev² · γ_prev) + (1−α) · max(γ−1, 0)
 *   4. Wiener gain G = ξ/(ξ+1), floored to keep some "air".
 *   5. Apply G to the complex spectrum, IFFT, window again, overlap-add.
 *
 * Use [calibrate] once with a 5-second silence sample to learn the noise PSD,
 * then [process] on any voice PCM to suppress that noise. Profile can be
 * persisted with [save] / [load] so calibration survives restarts.
 */
class NoiseFilter(
    val sampleRate: Int = 44100,
    val fftSize: Int = 1024,
    val hopSize: Int = 256,
) {
    private val fft = Fft(fftSize)
    private val window = Fft.hannWindow(fftSize)
    private val freqBins = fftSize / 2 + 1

    /** Per-bin noise PSD (averaged from calibration). null until calibrated. */
    private var noisePsd: FloatArray? = null

    /** True iff a profile has been loaded or freshly calibrated. */
    val hasProfile: Boolean get() = noisePsd != null

    /** Decision-directed smoothing factor. Higher = smoother but laggier. */
    var smoothing: Float = 0.96f
    /** Minimum gain (linear amplitude). 0.05 = -26 dB residual leakage. */
    var gainFloor: Float = 0.05f
    /** Oversubtraction factor (>=1). Higher cuts more noise but adds artifacts. */
    var oversubtract: Float = 1.5f

    /**
     * Build a noise PSD from a captured silence sample. The longer the better;
     * 3-5 seconds is the sweet spot.
     */
    fun calibrate(noisePcm: ByteArray) {
        val frames = mutableListOf<FloatArray>()
        val pcmFloat = pcmToFloat(noisePcm, noisePcm.size)
        // Hop through the calibration buffer accumulating |X|² per bin.
        val avgPsd = FloatArray(freqBins)
        var frameCount = 0
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        var pos = 0
        while (pos + fftSize <= pcmFloat.size) {
            for (i in 0 until fftSize) {
                re[i] = pcmFloat[pos + i] * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)
            for (k in 0 until freqBins) {
                avgPsd[k] += re[k] * re[k] + im[k] * im[k]
            }
            frameCount++
            pos += hopSize
        }
        if (frameCount == 0) return
        val inv = 1f / frameCount
        for (k in 0 until freqBins) avgPsd[k] *= inv
        // Prevent zero-PSD bins from causing divide-by-zero.
        val floor = 1e-10f
        for (k in 0 until freqBins) {
            if (avgPsd[k] < floor) avgPsd[k] = floor
        }
        noisePsd = avgPsd
        // Suppress unused warning (we keep `frames` reserved for diagnostics).
        @Suppress("UNUSED_VARIABLE") val unused = frames
    }

    /**
     * Apply the noise filter to [pcm] in-place (a copy is returned for clarity).
     * Caller should pass the entire buffer; processing is offline overlap-add.
     */
    fun process(pcm: ByteArray): ByteArray {
        val noise = noisePsd ?: return pcm
        val pcmFloat = pcmToFloat(pcm, pcm.size)
        val out = FloatArray(pcmFloat.size)

        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        // Decision-directed history
        val gainPrev = FloatArray(freqBins) { 1f }
        val gammaPrev = FloatArray(freqBins) { 1f }

        var pos = 0
        while (pos + fftSize <= pcmFloat.size) {
            for (i in 0 until fftSize) {
                re[i] = pcmFloat[pos + i] * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)

            // Compute gain bin-by-bin and apply to spectrum
            for (k in 0 until freqBins) {
                val powX = re[k] * re[k] + im[k] * im[k]
                val gamma = powX / (noise[k] * oversubtract)
                val gammaSafe = if (gamma > 0f) gamma else 0f
                val xiInst = max(gammaSafe - 1f, 0f)
                val xi = smoothing * (gainPrev[k] * gainPrev[k] * gammaPrev[k]) +
                    (1f - smoothing) * xiInst
                var gain = xi / (xi + 1f)
                if (gain < gainFloor) gain = gainFloor
                gainPrev[k] = gain
                gammaPrev[k] = gammaSafe
                re[k] *= gain
                im[k] *= gain
                // Maintain Hermitian symmetry for real IFFT
                if (k in 1 until freqBins - 1) {
                    re[fftSize - k] = re[k]
                    im[fftSize - k] = -im[k]
                }
            }
            // Nyquist bin's complex pair is itself; nothing to mirror.

            fft.inverse(re, im)
            // Window and overlap-add
            for (i in 0 until fftSize) {
                val outIdx = pos + i
                if (outIdx < out.size) {
                    out[outIdx] += re[i] * window[i]
                }
            }
            pos += hopSize
        }

        // Compensate for COLA gain of windowed overlap-add. With Hann + 75%
        // overlap (hop = fftSize/4) the constant-overlap-add gain is 1.5; we
        // simply scale once at the end.
        val overlapGain = computeOverlapGain()
        if (overlapGain > 0f) {
            val invGain = 1f / overlapGain
            for (i in out.indices) out[i] *= invGain
        }
        return floatToPcm(out, pcm)
    }

    /** Persist the calibrated noise PSD to a binary file. */
    fun save(file: File) {
        val psd = noisePsd ?: return
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream()).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(VERSION)
            d.writeInt(sampleRate)
            d.writeInt(fftSize)
            d.writeInt(psd.size)
            for (v in psd) d.writeFloat(v)
        }
    }

    /** Load a previously-saved profile. Returns false on bad/missing file. */
    fun load(file: File): Boolean {
        if (!file.exists()) return false
        return runCatching {
            DataInputStream(file.inputStream()).use { d ->
                require(d.readInt() == MAGIC) { "bad magic" }
                require(d.readInt() == VERSION) { "bad version" }
                require(d.readInt() == sampleRate) { "sample rate mismatch" }
                require(d.readInt() == fftSize) { "fft size mismatch" }
                val n = d.readInt()
                require(n == freqBins) { "bin count mismatch" }
                val psd = FloatArray(n) { d.readFloat() }
                noisePsd = psd
            }
            true
        }.getOrDefault(false)
    }

    /** RMS of the calibration in dBFS, useful for showing "noise floor" info. */
    fun noiseFloorDb(): Float? {
        val psd = noisePsd ?: return null
        var sum = 0.0
        for (v in psd) sum += v
        val rms = sqrt(sum / psd.size).toFloat()
        return (20f * (ln(rms.coerceAtLeast(1e-10f).toDouble()).toFloat() / 2.302585f))
    }

    private fun computeOverlapGain(): Float {
        // Hann² windowed-overlap-add gain: at 75% overlap (hop = fftSize/4)
        // this is constant 1.5; at 50% overlap it's ~0.5. Compute generically
        // by accumulating squared-window contributions at staggered positions.
        val overlap = fftSize / hopSize
        var maxSum = 0f
        // Probe a few sample positions and take the average — for Hann² this
        // is constant in the steady-state region.
        val probes = intArrayOf(fftSize / 2, fftSize / 2 + hopSize / 4, fftSize / 2 + hopSize / 2)
        for (probe in probes) {
            var sum = 0f
            for (k in 0 until overlap) {
                val idx = ((probe + k * hopSize) % fftSize)
                sum += window[idx] * window[idx]
            }
            maxSum = max(maxSum, sum)
        }
        return maxSum.coerceAtLeast(1e-3f)
    }

    private fun pcmToFloat(pcm: ByteArray, length: Int): FloatArray {
        val n = length / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s16 = (hi shl 8) or lo
            val signed = if (s16 >= 0x8000) s16 - 0x10000 else s16
            out[i] = signed / 32768f
        }
        return out
    }

    private fun floatToPcm(samples: FloatArray, original: ByteArray): ByteArray {
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            val v = (clamped * 32767f).toInt()
            original[i * 2] = (v and 0xFF).toByte()
            original[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return original
    }

    companion object {
        private const val MAGIC = 0x564E4631 // "VNF1"
        private const val VERSION = 1
    }
}
