package com.vocalmonitor.audio

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detect the most likely musical key + mode (major / minor) of a take using
 * the Krumhansl-Schmuckler key-finding algorithm.
 *
 * 1. Build a pitch-class histogram from the detected F0 trajectory — each
 *    voiced hop contributes 1 to its pitch class (so longer notes naturally
 *    weigh more, no extra duration weighting needed).
 * 2. For each of 12 roots × 2 modes, compute Pearson correlation between
 *    the histogram and the corresponding rotated key profile.
 * 3. Pick the highest-scoring (root, mode). Confidence is the margin
 *    between the top score and the runner-up.
 *
 * Returns `null` if the take has too few voiced hops to be reliable —
 * caller should keep whatever key the user already had.
 */
object KeyDetector {

    // Krumhansl-Kessler tonal profiles (1990). Root (index 0) is the tonic.
    private val MAJOR = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f,
        2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f,
    )
    private val MINOR = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f,
        2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f,
    )

    data class Result(
        val root: Int,                  // 0=C … 11=B
        val isMajor: Boolean,
        val confidence: Float,          // 0..1 — margin over runner-up
        val voicedHops: Int,
    ) {
        val rootName: String
            get() = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")[root]
        val displayName: String
            get() = "$rootName ${if (isMajor) "Major" else "Minor"}"
        /** Maps onto [PitchCorrector.Scale.Major] / [PitchCorrector.Scale.Minor]. */
        val scaleOrdinal: Int
            get() = if (isMajor) PitchCorrector.Scale.Major.ordinal
                else PitchCorrector.Scale.Minor.ordinal
    }

    fun detect(analysis: PitchCorrector.Analysis): Result? {
        val hist = FloatArray(12)
        var voiced = 0
        for (h in analysis.hops) {
            if (h.detectedHz <= 0f) continue
            val midi = Notes.freqToMidi(h.detectedHz.toDouble())
            val pc = ((midi.roundToInt() % 12) + 12) % 12
            hist[pc] += 1f
            voiced++
        }
        if (voiced < 16) return null    // ~0.4 s of voice at 256-sample hop / 11 kHz — too thin

        var bestScore = -Float.MAX_VALUE
        var bestRoot = 0
        var bestMajor = true
        var secondScore = -Float.MAX_VALUE

        for (root in 0..11) {
            for (mode in 0..1) {
                val profile = if (mode == 0) MAJOR else MINOR
                val score = correlate(hist, profile, root)
                when {
                    score > bestScore -> {
                        secondScore = bestScore
                        bestScore = score
                        bestRoot = root
                        bestMajor = (mode == 0)
                    }
                    score > secondScore -> secondScore = score
                }
            }
        }

        // Margin → confidence. Pearson is in [-1, 1]; map (best-second) to [0, 1].
        val margin = (bestScore - secondScore).coerceAtLeast(0f)
        val confidence = (margin * 5f).coerceIn(0f, 1f)
        return Result(bestRoot, bestMajor, confidence, voiced)
    }

    /** Pearson correlation between [hist] and [profile] rotated so its tonic sits at [root]. */
    private fun correlate(hist: FloatArray, profile: FloatArray, root: Int): Float {
        var sumH = 0f; var sumP = 0f
        for (i in 0 until 12) {
            sumH += hist[i]
            sumP += profile[i]
        }
        val meanH = sumH / 12f
        val meanP = sumP / 12f
        var num = 0f; var denH = 0f; var denP = 0f
        for (i in 0 until 12) {
            val rot = ((i - root) % 12 + 12) % 12
            val dh = hist[i] - meanH
            val dp = profile[rot] - meanP
            num += dh * dp
            denH += dh * dh
            denP += dp * dp
        }
        val den = sqrt(denH * denP)
        return if (den > 0f) num / den else 0f
    }
}
