package com.vocalmonitor.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One-shot bleed-frequency analyzer. Caller feeds in windowed mic frames
 * from a known silence period and a known click period; the analyzer
 * compares the averaged magnitude spectra and returns the frequencies
 * where click energy exceeds the silence baseline by [SNR_THRESHOLD_DB].
 *
 * Those frequencies are the device's individual bleed footprint —
 * speaker fundamental + harmonics + cabinet resonances + whatever the
 * room adds. Once captured we hand them to the Recorder, which builds
 * one biquad notch per frequency and applies them while the metronome
 * is running.
 *
 * Stateful: instantiate once per calibration, add frames, read peaks,
 * discard. Not thread-safe (each calibration runs on a single coroutine).
 */
class BleedAnalyzer(private val sampleRate: Int) {

    private val winSize = 2048
    private val halfBins = winSize / 2
    private val hann = FloatArray(winSize) { i ->
        0.5f * (1f - cos(2.0 * Math.PI * i / (winSize - 1)).toFloat())
    }

    private val silenceSum = FloatArray(halfBins)
    private var silenceFrames = 0
    private val clickSum = FloatArray(halfBins)
    private var clickFrames = 0

    private val re = FloatArray(winSize)
    private val im = FloatArray(winSize)

    fun addSilenceFrame(buf: ShortArray, offset: Int) {
        val mag = magSpectrum(buf, offset) ?: return
        for (i in 0 until halfBins) silenceSum[i] += mag[i]
        silenceFrames++
    }

    fun addClickFrame(buf: ShortArray, offset: Int) {
        val mag = magSpectrum(buf, offset) ?: return
        for (i in 0 until halfBins) clickSum[i] += mag[i]
        clickFrames++
    }

    /**
     * Returns up to [maxPeaks] frequencies (Hz, sorted ascending) where
     * the click spectrum exceeds the silence baseline by at least
     * [SNR_THRESHOLD_DB] decibels. Peaks within [PEAK_CLUSTER_HZ] of an
     * already-selected peak are dropped so we don't waste notches on
     * neighbouring bins of the same physical resonance.
     */
    fun findBleedPeaks(
        maxPeaks: Int = 6,
        snrDb: Float = SNR_THRESHOLD_DB,
    ): List<Float> {
        if (silenceFrames == 0 || clickFrames == 0) return emptyList()
        val silenceAvg = FloatArray(halfBins) { silenceSum[it] / silenceFrames + 1e-6f }
        val clickAvg = FloatArray(halfBins) { clickSum[it] / clickFrames + 1e-6f }

        val ratioThreshold = 10.0.pow((snrDb / 20.0).toDouble()).toFloat()
        val minBin = (MIN_PEAK_HZ * winSize / sampleRate).toInt().coerceAtLeast(2)
        val maxBin = (MAX_PEAK_HZ * winSize / sampleRate).toInt().coerceAtMost(halfBins - 3)
        if (maxBin <= minBin) return emptyList()

        // Collect local-maximum candidates above the SNR threshold.
        val candidates = mutableListOf<Pair<Float, Float>>()  // (freqHz, mag)
        for (b in minBin..maxBin) {
            val r = clickAvg[b] / silenceAvg[b]
            if (r < ratioThreshold) continue
            val isLocalMax = clickAvg[b] >= clickAvg[b - 1] &&
                clickAvg[b] >= clickAvg[b + 1] &&
                clickAvg[b] >= clickAvg[b - 2] &&
                clickAvg[b] >= clickAvg[b + 2]
            if (!isLocalMax) continue
            val freq = b * sampleRate.toFloat() / winSize
            candidates.add(freq to clickAvg[b])
        }

        // Greedy top-N with cluster suppression.
        val sorted = candidates.sortedByDescending { it.second }
        val accepted = mutableListOf<Float>()
        for ((freq, _) in sorted) {
            if (accepted.size >= maxPeaks) break
            if (accepted.any { abs(it - freq) < PEAK_CLUSTER_HZ }) continue
            accepted.add(freq)
        }
        return accepted.sorted()
    }

    private fun magSpectrum(buf: ShortArray, offset: Int): FloatArray? {
        if (offset < 0 || offset + winSize > buf.size) return null
        for (i in 0 until winSize) {
            re[i] = (buf[offset + i] / 32768f) * hann[i]
            im[i] = 0f
        }
        fft(re, im)
        return FloatArray(halfBins) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }

    /** In-place radix-2 FFT (length must be a power of two). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while (j and bit != 0) { j = j xor bit; bit = bit ushr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val newCurRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newCurRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        /** Below this we discard the SNR comparison — mostly DC + sub-bass mic noise. */
        private const val MIN_PEAK_HZ = 80f
        /** Above this is mostly speaker roll-off and irrelevant for vocals. */
        private const val MAX_PEAK_HZ = 6000f
        /** Two peaks closer than this are treated as the same resonance. */
        private const val PEAK_CLUSTER_HZ = 80f
        /** Default SNR (dB) the click spectrum must exceed silence by to count as bleed. */
        const val SNR_THRESHOLD_DB = 8f
    }
}
