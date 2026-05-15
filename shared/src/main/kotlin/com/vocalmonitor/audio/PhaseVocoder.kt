package com.vocalmonitor.audio

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Frame-by-frame phase-vocoder pitch shifter, voice-grade.
 *
 * Three industry-standard techniques layered on top of the classic
 * smbPitchShift algorithm:
 *
 *   1. **Identity phase locking** (Laroche & Dolson, 1999). Spectral peaks
 *      are detected per frame; bins inside a peak's region of influence
 *      inherit the peak's accumulated synthesis phase plus their original
 *      phase offset. This kills the "phasey smearing" that makes vanilla
 *      phase-vocoder voices sound watery.
 *
 *   2. **Cepstral formant preservation**. The log-magnitude spectrum is
 *      separated into a smooth envelope (low quefrencies) and an excitation
 *      residual (high quefrencies). The residual is pitch-shifted; the
 *      ORIGINAL envelope is reapplied. Result: pitched syllables move up or
 *      down without the chipmunk / dark-cave coloration.
 *
 *   3. **Per-hop ratio with attack hold**. Ratio changes between hops are
 *      applied at the analysis-magnitude rebin step but the synthesis phase
 *      keeps integrating from one consistent state — no glitchy resets when
 *      auto-tune snaps from one note to the next.
 */
class PhaseVocoder(
    val sampleRate: Int,
    val fftSize: Int = 2048,
    val overlap: Int = 4,
    /** True = preserve the spectral envelope so formants don't shift with pitch. */
    val preserveFormants: Boolean = true,
) {
    private val hop = fftSize / overlap
    private val nBins = fftSize / 2 + 1
    private val window = Fft.hannWindow(fftSize)
    private val fft = Fft(fftSize)
    private val twoPi = (2.0 * PI).toFloat()
    private val expectedHopPhase = twoPi * hop.toFloat() / fftSize
    private val freqPerBin = sampleRate.toFloat() / fftSize

    /** Lifter cutoff (quefrency bins) — lower = smoother envelope. */
    private val lifterCutoff = 32

    fun process(input: FloatArray, ratioForHop: (Int) -> Float): FloatArray {
        val n = input.size
        val out = FloatArray(n)
        val window = this.window
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val analysisMag = FloatArray(nBins)
        val analysisPhase = FloatArray(nBins)
        val analysisFreq = FloatArray(nBins)
        val synthMag = FloatArray(nBins)
        val synthFreq = FloatArray(nBins)
        val lastInPhase = FloatArray(nBins)
        val sumOutPhase = FloatArray(nBins)
        val envelope = FloatArray(nBins)
        val nearestPeak = IntArray(nBins) { -1 }
        // Persistent helpers for cepstrum
        val cepRe = FloatArray(fftSize)
        val cepIm = FloatArray(fftSize)

        // OLA gain compensation: with Hann + 4× overlap the sum is ~1.5×.
        val olaGain = when (overlap) {
            2 -> 0.5f
            4 -> 2f / 3f
            8 -> 1f / 3f
            else -> 1f / overlap
        }

        var t = 0
        var hopIdx = 0
        while (t + fftSize <= n) {
            // Allow ±4 octaves of shift. Wider than the classic ±2-oct
            // limit so users can hit extreme transposes (chipmunk
            // helium / sub-bass demon). The phase vocoder degrades at
            // the extremes — formants smear and artifacts intrude —
            // but the user explicitly opted in via the slider.
            val ratio = ratioForHop(hopIdx).coerceIn(0.0625f, 16f)

            // ── 1. Window the input frame ─────────────────────────────
            for (i in 0 until fftSize) {
                re[i] = input[t + i] * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)

            // ── 2. Analyse: magnitude, phase, true frequency per bin ──
            for (k in 0 until nBins) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                val phase = atan2(im[k], re[k])
                var dPhase = phase - lastInPhase[k]
                lastInPhase[k] = phase
                dPhase -= k * expectedHopPhase
                dPhase = wrapPi(dPhase)
                val deviation = dPhase / expectedHopPhase * freqPerBin
                analysisMag[k] = mag
                analysisPhase[k] = phase
                analysisFreq[k] = k * freqPerBin + deviation
            }

            // ── 3. Spectral envelope via cepstrum (formant preservation)
            if (preserveFormants) {
                computeEnvelope(analysisMag, envelope, cepRe, cepIm)
            }

            // ── 4. Pitch shift: rebin magnitudes/freqs by ratio ───────
            // Preserve formants by whitening with envelope before shifting,
            // then re-apply ORIGINAL envelope after.
            for (k in 0 until nBins) { synthMag[k] = 0f; synthFreq[k] = 0f }
            for (k in 0 until nBins) {
                val src = (k / ratio).roundToInt()
                if (src < 0 || src >= nBins) continue
                // Whiten by source envelope so we shift the residual
                val whitened = if (preserveFormants && envelope[src] > 1e-6f)
                    analysisMag[src] / envelope[src]
                else analysisMag[src]
                if (whitened > synthMag[k] || synthMag[k] == 0f) {
                    synthMag[k] = whitened
                    synthFreq[k] = analysisFreq[src] * ratio
                }
            }
            // Re-apply DESTINATION envelope (= original at THIS bin) so
            // formants stay where they were in the source.
            if (preserveFormants) {
                for (k in 0 until nBins) synthMag[k] *= envelope[k]
            }

            // ── 5. Identity phase locking (Laroche-Dolson) ────────────
            // Find spectral peaks, then bind each non-peak bin's synthesis
            // phase to the nearest peak's accumulated phase.
            assignNearestPeaks(synthMag, nearestPeak)

            // For peaks: integrate true frequency into synthesis phase.
            for (k in 0 until nBins) {
                if (nearestPeak[k] != k) continue  // not a peak — handled later
                val devHz = synthFreq[k] - k * freqPerBin
                val dPhase = devHz / freqPerBin * expectedHopPhase + k * expectedHopPhase
                sumOutPhase[k] = sumOutPhase[k] + dPhase
            }
            // For non-peaks: sumOutPhase = peak's sumOutPhase + original
            // analysis-phase offset from the peak. This is the rigid
            // identity phase-locking from the L-D 1999 paper.
            for (k in 0 until nBins) {
                val pk = nearestPeak[k]
                if (pk == k || pk < 0) continue
                val srcPk = (pk / ratio).roundToInt().coerceIn(0, nBins - 1)
                val srcK = (k / ratio).roundToInt().coerceIn(0, nBins - 1)
                val phaseOffset = analysisPhase[srcK] - analysisPhase[srcPk]
                sumOutPhase[k] = sumOutPhase[pk] + phaseOffset
            }

            // ── 6. Build complex spectrum + IFFT ──────────────────────
            for (k in 0 until nBins) {
                val mag = synthMag[k]
                val ph = sumOutPhase[k]
                re[k] = mag * cos(ph)
                im[k] = mag * sin(ph)
            }
            // Mirror conjugate for negative-frequency half (real signal).
            for (k in 1 until fftSize / 2) {
                re[fftSize - k] = re[k]
                im[fftSize - k] = -im[k]
            }
            re[fftSize / 2] = 0f; im[fftSize / 2] = 0f
            fft.inverse(re, im)

            // ── 7. Window + overlap-add ───────────────────────────────
            for (i in 0 until fftSize) {
                out[t + i] += re[i] * window[i] * olaGain
            }

            t += hop
            hopIdx++
        }
        return out
    }

    /**
     * Build a smoothed magnitude envelope by liftering the cepstrum
     * (zeroing high-quefrency bins). The residual is everything that the
     * envelope can't predict — i.e., the harmonic structure / pitch.
     */
    private fun computeEnvelope(
        mag: FloatArray,
        envelope: FloatArray,
        re: FloatArray,
        im: FloatArray,
    ) {
        // log-magnitude into the spectrum (mirror to full size for IFFT)
        for (i in 0 until fftSize) { re[i] = 0f; im[i] = 0f }
        for (k in 0 until nBins) {
            val l = ln(mag[k].coerceAtLeast(1e-9f))
            re[k] = l
        }
        for (k in 1 until fftSize / 2) {
            re[fftSize - k] = re[k]
        }
        // IFFT → cepstrum (real part)
        fft.inverse(re, im)
        // Lifter: keep only low quefrencies (envelope), zero the rest
        for (i in lifterCutoff until fftSize - lifterCutoff) {
            re[i] = 0f
            im[i] = 0f
        }
        // Force cepstrum to be even-symmetric for a real envelope
        for (i in 1 until lifterCutoff) {
            im[i] = 0f
            im[fftSize - i] = 0f
        }
        // FFT back → log-magnitude envelope
        fft.forward(re, im)
        // Exponentiate to get linear-magnitude envelope
        for (k in 0 until nBins) {
            envelope[k] = exp(re[k])
        }
    }

    /**
     * For every bin, fill [nearestPeakOut] with the index of the closest
     * spectral peak. A peak is a bin whose magnitude exceeds both 2-bin
     * neighbours on either side.
     */
    private fun assignNearestPeaks(mag: FloatArray, nearestPeakOut: IntArray) {
        // Find peaks
        val peaks = ArrayList<Int>(64)
        for (k in 2 until nBins - 2) {
            val m = mag[k]
            if (m > mag[k - 1] && m > mag[k + 1] &&
                m > mag[k - 2] && m > mag[k + 2] && m > 1e-6f
            ) peaks.add(k)
        }
        if (peaks.isEmpty()) {
            // Degenerate frame — every bin is its own "peak"
            for (k in 0 until nBins) nearestPeakOut[k] = k
            return
        }
        // For each bin, find nearest peak via two-finger walk through sorted peaks.
        var pi = 0
        for (k in 0 until nBins) {
            while (pi < peaks.size - 1 && peaks[pi + 1] <= k) pi++
            val left = peaks[pi]
            val right = if (pi + 1 < peaks.size) peaks[pi + 1] else left
            nearestPeakOut[k] = if (k - left <= right - k) left else right
        }
    }

    private fun wrapPi(x: Float): Float {
        var v = x
        while (v > PI) v -= twoPi
        while (v < -PI) v += twoPi
        return v
    }
}
