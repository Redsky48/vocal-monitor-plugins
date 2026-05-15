package com.vocalmonitor.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 8-tap Feedback Delay Network reverb with cascaded all-pass diffusers,
 * per-tap damping, and subtle LFO modulation on read positions.
 *
 * The architecture is the standard "modern algorithmic" recipe: dry input
 * passes through 4 series all-passes (diffusion → smears transients into a
 * dense input wash), then feeds an 8-line FDN. Each frame the 8 taps run
 * through a Walsh-Hadamard mixing matrix (orthogonal → energy-preserving),
 * then through one-pole low-pass dampers (HF roll-off in the tail), and
 * back into the delay lines. Output is the mean of the 8 taps mixed with
 * the dry signal.
 *
 * Why this is "pro" rather than toy:
 *   - prime-ish delay lengths avoid resonant peaks
 *   - Hadamard matrix is unitary, giving a smooth diffuse tail without
 *     comb filtering
 *   - per-line damping in the feedback path produces natural HF decay
 *   - per-line LFOs at distinct rates remove static-tail "metallic" feel
 *
 * All parameters are safe to write from the UI thread mid-render — values
 * are read fresh per sample (or per chunk) by the audio loop.
 */
class Reverb(val sampleRate: Int = 44100) {

    // ─── Public parameters ──────────────────────────────────────────────

    /** Room size 0..1 — scales delay-line lengths together. */
    @Volatile var size: Float = 0.5f
    /** Decay time in seconds (RT60 approx, before damping). */
    @Volatile var decaySec: Float = 2.0f
    /** Pre-delay in ms (0..200). */
    @Volatile var preDelayMs: Float = 20f
    /** HF damping 0..1 — higher = more bass-heavy tail. */
    @Volatile var damping: Float = 0.5f
    /** Diffusion 0..1 — strength of the input all-pass diffusers. */
    @Volatile var diffusion: Float = 0.7f
    /** Modulation depth 0..1 — LFOs on delay reads. */
    @Volatile var modulation: Float = 0.3f
    /** Wet mix 0..1. */
    @Volatile var mix: Float = 0.3f

    // ─── Internals ──────────────────────────────────────────────────────

    private val baseDelayMs = floatArrayOf(
        29.7f, 37.1f, 41.3f, 43.7f, 47.1f, 53.3f, 59.7f, 67.1f
    )
    private val maxDelaySamples = (sampleRate * 0.18f).toInt()
    private val delays: Array<FloatArray> = Array(8) { FloatArray(maxDelaySamples) }
    private val delayLengths = IntArray(8)
    private val delayPos = IntArray(8)
    private val dampStates = FloatArray(8)

    private val preDelayBuf = FloatArray((sampleRate * 0.25f).toInt())
    private var preDelayWritePos = 0
    private var preDelayLength = (sampleRate * 0.02f).toInt()

    private val diffuserSizes = intArrayOf(
        (sampleRate * 0.0043f).toInt(),
        (sampleRate * 0.0073f).toInt(),
        (sampleRate * 0.0113f).toInt(),
        (sampleRate * 0.0151f).toInt(),
    )
    private val diffuserBufs = Array(4) { FloatArray(diffuserSizes[it]) }
    private val diffuserPos = IntArray(4)

    private val lfoIncrement = FloatArray(8)
    private val lfoPhase = FloatArray(8)

    private val tapBuffer = FloatArray(8)

    init {
        for (i in 0 until 8) {
            // LFO rates 0.3..1.5 Hz — staggered so taps don't beat together
            val freq = 0.3f + 0.15f * i
            lfoIncrement[i] = (2.0 * PI * freq / sampleRate).toFloat()
            lfoPhase[i] = i * 0.785f
        }
        recomputeDelayLengths()
        recomputePreDelay()
    }

    fun reset() {
        for (i in 0 until 8) {
            delays[i].fill(0f)
            delayPos[i] = 0
            dampStates[i] = 0f
        }
        preDelayBuf.fill(0f)
        preDelayWritePos = 0
        for (i in 0 until 4) {
            diffuserBufs[i].fill(0f)
            diffuserPos[i] = 0
        }
    }

    private fun recomputeDelayLengths() {
        // Size 0..1 maps to delay scale 0.5x .. 1.5x base
        val scale = 0.5f + size
        for (i in 0 until 8) {
            val samples = (baseDelayMs[i] * scale * sampleRate / 1000f).toInt()
            delayLengths[i] = samples.coerceIn(64, maxDelaySamples)
        }
    }

    private fun recomputePreDelay() {
        preDelayLength = (preDelayMs * sampleRate / 1000f).toInt()
            .coerceIn(0, preDelayBuf.size - 1)
    }

    /**
     * Process a 16-bit PCM byte buffer in place.
     */
    fun process(pcm: ByteArray, length: Int = pcm.size) {
        // Refresh derived params (cheap)
        recomputeDelayLengths()
        recomputePreDelay()
        val meanDelay = delayLengths.average().toFloat()
        // Feedback gain to hit RT60 ≈ decaySec, before damping
        val feedbackGain = 10f.pow(-3f * meanDelay / (decaySec * sampleRate))
            .coerceIn(0f, 0.999f)
        val diffuserCoef = (0.55f + diffusion * 0.4f).coerceIn(0f, 0.95f)
        val modSamples = modulation * 4f

        val sampleCount = length / 2
        for (n in 0 until sampleCount) {
            val lo = pcm[n * 2].toInt() and 0xFF
            val hi = pcm[n * 2 + 1].toInt()
            val s16 = (hi shl 8) or lo
            val signed = if (s16 >= 0x8000) s16 - 0x10000 else s16
            val dry = signed / 32768f

            // Pre-delay
            val pdOut = if (preDelayLength == 0) dry else {
                val readIdx = (preDelayWritePos - preDelayLength + preDelayBuf.size) % preDelayBuf.size
                preDelayBuf[preDelayWritePos] = dry
                preDelayWritePos = (preDelayWritePos + 1) % preDelayBuf.size
                preDelayBuf[readIdx]
            }

            // 4 cascaded all-pass diffusers
            var diff = pdOut
            for (i in 0 until 4) {
                val buf = diffuserBufs[i]
                val pos = diffuserPos[i]
                val stored = buf[pos]
                val out = -diffuserCoef * diff + stored
                buf[pos] = diff + diffuserCoef * out
                diffuserPos[i] = (pos + 1) % buf.size
                diff = out
            }

            // FDN — read each delay line with LFO-modulated offset
            for (i in 0 until 8) {
                val len = delayLengths[i]
                val rawOffset = lfoSampleOffset(i, modSamples)
                val readIdx = readDelay(i, len, rawOffset)
                tapBuffer[i] = readIdx
            }

            // Wet output is the mean of the taps
            var sum = 0f
            for (i in 0 until 8) sum += tapBuffer[i]
            val wet = sum * 0.125f

            // Apply Hadamard mixing matrix to taps
            walshHadamard8(tapBuffer)

            // Inject diffused input + write back through damping
            for (i in 0 until 8) {
                // One-pole LP — damping
                val mixed = tapBuffer[i] * feedbackGain + diff
                dampStates[i] = (1f - damping) * mixed + damping * dampStates[i]
                delays[i][delayPos[i]] = dampStates[i]
                delayPos[i] = (delayPos[i] + 1) % delays[i].size
            }

            val out = (1f - mix) * dry + mix * wet
            val clamped = out.coerceIn(-1f, 1f)
            val outInt = (clamped * 32767f).toInt()
            pcm[n * 2] = (outInt and 0xFF).toByte()
            pcm[n * 2 + 1] = ((outInt shr 8) and 0xFF).toByte()
        }
    }

    private fun lfoSampleOffset(line: Int, depthSamples: Float): Float {
        lfoPhase[line] += lfoIncrement[line]
        if (lfoPhase[line] > (2 * PI).toFloat()) lfoPhase[line] -= (2 * PI).toFloat()
        return sin(lfoPhase[line]) * depthSamples
    }

    private fun readDelay(line: Int, length: Int, modOffset: Float): Float {
        val buf = delays[line]
        val bufLen = buf.size
        val baseRead = delayPos[line] - length + bufLen
        val readF = baseRead - modOffset
        val readBase = readF.toInt()
        val frac = readF - readBase
        val a = buf[((readBase % bufLen) + bufLen) % bufLen]
        val b = buf[(((readBase + 1) % bufLen) + bufLen) % bufLen]
        return a + frac * (b - a)
    }

    private fun walshHadamard8(x: FloatArray) {
        // 3-stage radix-2 Walsh-Hadamard butterfly
        for (stride in intArrayOf(1, 2, 4)) {
            var i = 0
            while (i < 8) {
                for (j in 0 until stride) {
                    val a = x[i + j]
                    val b = x[i + j + stride]
                    x[i + j] = a + b
                    x[i + j + stride] = a - b
                }
                i += stride * 2
            }
        }
        // Normalize so the matrix is unitary (energy-preserving)
        val scale = 1f / sqrt(8f)
        for (i in 0 until 8) x[i] *= scale
    }

    private fun Float.pow(exponent: Float): Float =
        exp((ln(this.toDouble()) * exponent).toDouble()).toFloat()
}
