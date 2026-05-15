package com.vocalmonitor.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Pure-DSP analyzer that suggests compressor + 10-band EQ + reverb settings
 * for the captured vocal calibration sample. No ML — heuristics tuned for
 * "voice clarity, presence and fullness without over-processing".
 *
 * The smart path runs **iteratively**: it APPLIES the suggested chain to
 * the sample, measures the result, and nudges parameters back toward the
 * target. After ≤3 passes the suggestion is what the user actually wants
 * to hear, not just a first-guess.
 */
object AutoCalibrate {

    data class CompressorSuggestion(
        val thresholdDb: Float,
        val ratio: Float,
        val attackMs: Float,
        val releaseMs: Float,
        val kneeDb: Float,
        val makeupDb: Float,
    )

    data class Stats(
        val peakDb: Float,
        val medianDb: Float,
        val crestFactorDb: Float,
        val bandLevelsDb: List<Float>,
    )

    data class Result(
        val compressor: CompressorSuggestion,
        val eqGainsDb: List<Float>,
        val reverb: ReverbSuggestion,
        val stats: Stats,
        val iterations: Int,
    )

    data class ReverbSuggestion(
        val size: Float,
        val decaySec: Float,
        val preDelayMs: Float,
        val damping: Float,
        val diffusion: Float,
        val modulation: Float,
        val mix: Float,
    )

    private data class Metrics(
        val rmsDb: Float,
        val peakDb: Float,
        val crestDb: Float,
        val mudIndex: Float,
        val presenceIndex: Float,
        val airIndex: Float,
    )

    fun analyze(vocalPcm: ByteArray, sampleRate: Int): Result {
        // 1. Initial guess from raw distribution + spectrum
        val initial = initialSuggestion(vocalPcm, sampleRate)
        var eq = initial.eqGainsDb.toMutableList()
        var comp = initial.compressor
        val reverb = SUBTLE_VOCAL_REVERB

        // 2. Iterative refinement
        var iterations = 0
        for (iter in 0..2) {
            iterations = iter + 1
            val processed = applyChain(vocalPcm, eq, comp, reverb, sampleRate)
            val metrics = computeMetrics(processed, sampleRate)

            val mudHigh = metrics.mudIndex > 0.22f
            val presenceLow = metrics.presenceIndex < 0.10f
            val airLow = metrics.airIndex < 0.05f
            val crestHigh = metrics.crestDb > 14f
            val crestLow = metrics.crestDb < 7f
            val rmsLow = metrics.rmsDb < -18f
            val rmsHigh = metrics.rmsDb > -10f

            if (!mudHigh && !presenceLow && !airLow &&
                !crestHigh && !crestLow && !rmsLow && !rmsHigh
            ) break

            // Gentle nudges, capped per iteration so we don't overshoot
            if (mudHigh) {
                eq[3] = (eq[3] - 1.0f).coerceAtLeast(-6f)
                eq[4] = (eq[4] - 0.5f).coerceAtLeast(-6f)
            }
            if (presenceLow) {
                eq[6] = (eq[6] + 1.0f).coerceAtMost(4f)
                eq[7] = (eq[7] + 0.5f).coerceAtMost(4f)
            }
            if (airLow) {
                eq[8] = (eq[8] + 1.0f).coerceAtMost(4f)
            }
            if (crestHigh) {
                comp = comp.copy(
                    thresholdDb = (comp.thresholdDb - 1f).coerceAtLeast(-32f),
                    ratio = (comp.ratio + 0.5f).coerceAtMost(8f),
                )
            }
            if (crestLow) {
                comp = comp.copy(
                    ratio = (comp.ratio - 0.5f).coerceAtLeast(2f),
                )
            }
            if (rmsLow) {
                comp = comp.copy(
                    makeupDb = (comp.makeupDb + 1.5f).coerceAtMost(12f),
                )
            }
            if (rmsHigh) {
                comp = comp.copy(
                    makeupDb = (comp.makeupDb - 1f).coerceAtLeast(0f),
                )
            }
        }

        val finalLevels = computeBandLevelsDb(vocalPcm, sampleRate)
        val sortedAbsDb = absSamplesDb(vocalPcm).sortedArray()
        val n = sortedAbsDb.size.coerceAtLeast(1)
        val p50 = sortedAbsDb[n / 2]
        val p99 = sortedAbsDb[(n * 99 / 100).coerceAtMost(n - 1)]

        return Result(
            compressor = comp,
            eqGainsDb = eq,
            reverb = reverb,
            stats = Stats(p99, p50, (p99 - p50).coerceIn(0f, 60f), finalLevels),
            iterations = iterations,
        )
    }

    // ─── Initial suggestion (single-pass heuristic) ─────────────────────

    private fun initialSuggestion(pcm: ByteArray, sampleRate: Int): Result {
        val absDb = absSamplesDb(pcm).sortedArray()
        val n = absDb.size.coerceAtLeast(1)
        val p50 = absDb[n / 2]
        val p99 = absDb[(n * 99 / 100).coerceAtMost(n - 1)]
        val crest = (p99 - p50).coerceIn(0f, 60f)

        val ratio = when {
            crest > 22f -> 6f
            crest > 16f -> 4f
            crest > 10f -> 3f
            else -> 2f
        }
        val threshold = (p99 - 6f).coerceIn(-40f, -3f)
        val grAtPeak = (p99 - threshold) * (1f - 1f / ratio)
        val makeup = (grAtPeak * 0.5f).coerceIn(0f, 12f)

        val comp = CompressorSuggestion(
            thresholdDb = threshold,
            ratio = ratio,
            attackMs = 5f,
            releaseMs = 120f,
            kneeDb = 6f,
            makeupDb = makeup,
        )
        val bandLevelsDb = computeBandLevelsDb(pcm, sampleRate)
        val eqGainsDb = suggestEqFromLevels(bandLevelsDb)
        return Result(
            compressor = comp,
            eqGainsDb = eqGainsDb,
            reverb = SUBTLE_VOCAL_REVERB,
            stats = Stats(p99, p50, crest, bandLevelsDb),
            iterations = 0,
        )
    }

    // ─── Chain simulation ───────────────────────────────────────────────

    private fun applyChain(
        pcm: ByteArray,
        eqGains: List<Float>,
        comp: CompressorSuggestion,
        reverb: ReverbSuggestion,
        sampleRate: Int,
    ): ByteArray {
        val out = pcm.copyOf()
        val biquad = BiquadEqualizer(sampleRate).apply {
            gainsDb = eqGains.toFloatArray()
        }
        biquad.process(out)
        val c = Compressor(sampleRate).apply {
            thresholdDb = comp.thresholdDb
            ratio = comp.ratio
            attackMs = comp.attackMs
            releaseMs = comp.releaseMs
            kneeWidthDb = comp.kneeDb
            makeupGainDb = comp.makeupDb
        }
        c.process(out)
        val r = Reverb(sampleRate).apply {
            size = reverb.size
            decaySec = reverb.decaySec
            preDelayMs = reverb.preDelayMs
            damping = reverb.damping
            diffusion = reverb.diffusion
            modulation = reverb.modulation
            mix = reverb.mix
        }
        r.process(out)
        return out
    }

    // ─── Metrics ────────────────────────────────────────────────────────

    private fun computeMetrics(pcm: ByteArray, sampleRate: Int): Metrics {
        val abs = absSamplesDb(pcm)
        val sorted = abs.sortedArray()
        val n = sorted.size.coerceAtLeast(1)
        val rms = sorted.average().toFloat()
        val peak = sorted[(n * 99 / 100).coerceAtMost(n - 1)]
        val median = sorted[n / 2]
        val crest = (peak - median).coerceIn(0f, 60f)

        val bandLevels = computeBandLevelsDb(pcm, sampleRate)
        // Convert bandLevels (dB) to linear power, normalize, take ratios
        val linPower = bandLevels.map { 10.0.pow(it / 10.0) }
        val total = linPower.sum().coerceAtLeast(1e-12)
        val mud = (linPower[3] + linPower[4]) / total
        val presence = (linPower[6] + linPower[7]) / total
        val air = (linPower[8] + linPower[9]) / total
        return Metrics(
            rmsDb = rms,
            peakDb = peak,
            crestDb = crest,
            mudIndex = mud.toFloat(),
            presenceIndex = presence.toFloat(),
            airIndex = air.toFloat(),
        )
    }

    // ─── Spectrum + helpers ─────────────────────────────────────────────

    private fun absSamplesDb(pcm: ByteArray): FloatArray {
        val n = pcm.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s16 = (hi shl 8) or lo
            val signed = if (s16 >= 0x8000) s16 - 0x10000 else s16
            val absSf = abs(signed / 32768f).coerceAtLeast(1e-6f)
            out[i] = 20f * (ln(absSf) / LN10).toFloat()
        }
        return out
    }

    private fun computeBandLevelsDb(pcm: ByteArray, sampleRate: Int): List<Float> {
        val fftSize = 1024
        val hopSize = 256
        val fft = Fft(fftSize)
        val window = Fft.hannWindow(fftSize)
        val pcmFloat = pcmToFloat(pcm)
        val freqBins = fftSize / 2 + 1
        val avgPower = FloatArray(freqBins)
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
                avgPower[k] += re[k] * re[k] + im[k] * im[k]
            }
            frameCount++
            pos += hopSize
        }
        if (frameCount == 0) return List(10) { 0f }
        val invF = 1f / frameCount
        for (k in 0 until freqBins) avgPower[k] *= invF

        val bandsDb = MutableList(10) { 0f }
        val freqs = BiquadEqualizer.BAND_FREQUENCIES
        for (b in 0 until 10) {
            val center = freqs[b].toFloat()
            val low = center / 1.414f
            val high = center * 1.414f
            val lowBin = ((low * fftSize / sampleRate).toInt()).coerceAtLeast(0)
            val highBin = ((high * fftSize / sampleRate).toInt()).coerceAtMost(freqBins - 1)
            var sum = 0f
            var count = 0
            for (k in lowBin..highBin) { sum += avgPower[k]; count++ }
            val avg = if (count > 0) sum / count else 1e-12f
            bandsDb[b] = 10f * (ln(avg.coerceAtLeast(1e-12f).toDouble()).toFloat() / LN10)
        }
        return bandsDb
    }

    private fun suggestEqFromLevels(bandLevelsDb: List<Float>): List<Float> {
        // "Pro-but-not-overcooked" base: light bass cut, presence lift, gentle air
        val basePreset = floatArrayOf(0f, -3f, 0f, -1.5f, 0f, 0f, 1.5f, 2.5f, 1.5f, 0f)
        val mean = bandLevelsDb.average().toFloat()
        val gains = FloatArray(10)
        for (b in 0 until 10) {
            val delta = bandLevelsDb[b] - mean
            // Cut bands that are >+6 dB above mean (resonant peaks)
            val dominanceCut = if (delta > 6f) -((delta - 6f) * 0.4f).coerceAtMost(4f) else 0f
            gains[b] = (basePreset[b] + dominanceCut).coerceIn(-8f, 4f)
        }
        return gains.toList()
    }

    private fun pcmToFloat(pcm: ByteArray): FloatArray {
        val n = pcm.size / 2
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

    private val SUBTLE_VOCAL_REVERB = ReverbSuggestion(
        size = 0.35f,
        decaySec = 1.4f,
        preDelayMs = 12f,
        damping = 0.55f,
        diffusion = 0.75f,
        modulation = 0.2f,
        mix = 0.18f,
    )

    private const val LN10 = 2.302585092994046f

    private fun Double.pow(other: Double): Double = Math.pow(this, other)
}
