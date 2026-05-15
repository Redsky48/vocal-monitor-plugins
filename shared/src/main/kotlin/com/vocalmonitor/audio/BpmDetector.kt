package com.vocalmonitor.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Tempo estimator. Builds an onset-strength envelope (energy difference
 * across short windows), then autocorrelates it over the 40–240 BPM lag
 * range and picks the peak.
 *
 * This is the simplest published approach that still works on real
 * music — accurate to ±2 BPM on most percussive tracks. Not gated by
 * beat tracking; we only want a tempo number, not a phase.
 */
object BpmDetector {
    private const val WIN_MS = 23      // ≈1024 samples at 44.1 kHz
    private const val HOP_MS = 11

    /**
     * @param bpm                tempo in beats per minute.
     * @param firstBeatOffsetMs  ms from buffer start to the first detected
     *                           downbeat — used to phase-align an external
     *                           clock (like the metronome) with the analysed
     *                           audio when it later plays back from position 0.
     */
    data class Result(val bpm: Float, val firstBeatOffsetMs: Long)

    /**
     * @param mono   mono PCM in [-1f, 1f].
     * @param sr     sample rate of [mono].
     * @return estimated tempo + first-beat offset, or null if too short / silent.
     */
    fun detect(mono: FloatArray, sr: Int): Result? {
        if (mono.size < sr * 3) return null   // need ≥ 3 seconds
        val win = max(64, sr * WIN_MS / 1000)
        val hop = max(32, sr * HOP_MS / 1000)
        val frames = (mono.size - win) / hop
        if (frames < 64) return null

        val rms = FloatArray(frames)
        for (f in 0 until frames) {
            val off = f * hop
            var s = 0.0
            for (i in 0 until win) {
                val x = mono[off + i]
                s += x * x
            }
            rms[f] = sqrt(s / win).toFloat()
        }

        val onset = FloatArray(frames)
        for (i in 1 until frames) {
            val d = rms[i] - rms[i - 1]
            if (d > 0f) onset[i] = d
        }
        var mean = 0f
        for (v in onset) mean += v
        mean /= frames
        for (i in onset.indices) onset[i] = max(0f, onset[i] - mean)

        val frameRate = sr.toFloat() / hop
        val minLag = (frameRate * 60f / 240f).toInt().coerceAtLeast(2)
        val maxLag = (frameRate * 60f / 40f).toInt().coerceAtMost(frames - 1)
        if (maxLag <= minLag) return null

        var bestLag = minLag
        var bestVal = 0f
        for (lag in minLag..maxLag) {
            var sum = 0f
            for (i in lag until frames) sum += onset[i] * onset[i - lag]
            if (sum > bestVal) {
                bestVal = sum
                bestLag = lag
            }
        }
        if (bestVal <= 0f) return null

        var bpm = 60f * frameRate / bestLag
        var period = bestLag
        while (bpm < 70f) { bpm *= 2f; period /= 2 }
        while (bpm > 180f) { bpm /= 2f; period *= 2 }
        if (period < 2) period = 2

        // Phase search — slide an integer-period comb across the onset
        // envelope and pick the offset whose taps sit on the strongest peaks.
        var bestPhase = 0
        var bestPhaseEnergy = -1f
        for (phi in 0 until period) {
            var sum = 0f
            var i = phi
            while (i < frames) { sum += onset[i]; i += period }
            if (sum > bestPhaseEnergy) {
                bestPhaseEnergy = sum
                bestPhase = phi
            }
        }
        val firstBeatMs = (bestPhase / frameRate * 1000f).toLong()
        return Result(bpm, firstBeatMs)
    }
}
