package com.vocalmonitor.audio

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-speed DSP primitives callable from plugin JS via the `host.*` global.
 *
 * Plugins that route per-sample math through these primitives run **inside
 * Kotlin/JVM** for the inner loops — typically 50-200× faster than doing
 * the same arithmetic inside Rhino-compiled JS, because each primitive's
 * processBlock() runs entirely as JIT-compiled Java with no JS function
 * dispatch per sample.
 *
 * The host exposes opaque integer handles instead of JS-side objects, so
 * the bridge stays cheap: a handle is just a number, no Scriptable round-
 * trips. Plugins create a primitive in their constructor and process a
 * whole block per call to `host.<primitive>Process(handle, in, out, …)`.
 *
 * Example plugin:
 * ```js
 * function FastEqProcessor() {
 *   this.lp = host.createBiquad('lowpass');
 * }
 * FastEqProcessor.parameterDescriptors = [
 *   { name: 'cutoff', defaultValue: 1500, minValue: 50, maxValue: 8000 }
 * ];
 * FastEqProcessor.prototype.process = function(inputs, outputs, parameters) {
 *   var input = inputs[0][0], output = outputs[0][0];
 *   if (!input || !output) return true;
 *   host.biquadSetLowpass(this.lp, parameters.cutoff[0], 0.707);
 *   host.biquadProcess(this.lp, input, output);
 *   return true;
 * };
 * registerProcessor('fast-eq', FastEqProcessor);
 * ```
 */
object NativeDspHost {

    private var sampleRate: Int = 44100
    private val biquads = ArrayList<Biquad>()
    private val delays = ArrayList<DelayLine>()
    private val lfos = ArrayList<Lfo>()

    fun setSampleRate(rate: Int) {
        sampleRate = rate.coerceAtLeast(8000)
    }

    /** Wire `host.*` functions onto the JS scope. Called once when the
     *  engine's base scope is built. */
    fun install(scope: ScriptableObject) {
        val host = scope.context().newObject(scope)

        host.put("createBiquad", host, jsFn { args ->
            val type = (args.getOrNull(0) as? String) ?: "lowpass"
            val bq = Biquad().apply { setType(type, 1000f, 0.707f, sampleRate) }
            biquads.add(bq)
            (biquads.size - 1).toDouble()
        })
        host.put("biquadSetLowpass", host, jsFn { args ->
            val h = (args.getOrNull(0) as? Number)?.toInt() ?: return@jsFn Unit
            val f = (args.getOrNull(1) as? Number)?.toFloat() ?: 1000f
            val q = (args.getOrNull(2) as? Number)?.toFloat() ?: 0.707f
            biquads.getOrNull(h)?.setType("lowpass", f, q, sampleRate)
            Unit
        })
        host.put("biquadSetHighpass", host, jsFn { args ->
            val h = (args.getOrNull(0) as? Number)?.toInt() ?: return@jsFn Unit
            val f = (args.getOrNull(1) as? Number)?.toFloat() ?: 200f
            val q = (args.getOrNull(2) as? Number)?.toFloat() ?: 0.707f
            biquads.getOrNull(h)?.setType("highpass", f, q, sampleRate)
            Unit
        })
        host.put("biquadSetBandpass", host, jsFn { args ->
            val h = (args.getOrNull(0) as? Number)?.toInt() ?: return@jsFn Unit
            val f = (args.getOrNull(1) as? Number)?.toFloat() ?: 1000f
            val q = (args.getOrNull(2) as? Number)?.toFloat() ?: 2f
            biquads.getOrNull(h)?.setType("bandpass", f, q, sampleRate)
            Unit
        })
        host.put("biquadProcess", host, jsFn { args ->
            val h = (args.getOrNull(0) as? Number)?.toInt() ?: return@jsFn Unit
            val ina = args.getOrNull(1) as? Scriptable ?: return@jsFn Unit
            val out = args.getOrNull(2) as? Scriptable ?: return@jsFn Unit
            biquads.getOrNull(h)?.processBlock(ina, out)
            Unit
        })

        host.put("createDelayLine", host, jsFn { args ->
            val maxMs = (args.getOrNull(0) as? Number)?.toFloat() ?: 1000f
            val maxSamples = (sampleRate * maxMs / 1000f).toInt().coerceAtLeast(64)
            delays.add(DelayLine(maxSamples))
            (delays.size - 1).toDouble()
        })
        host.put("delayProcess", host, jsFn { args ->
            val h = (args.getOrNull(0) as? Number)?.toInt() ?: return@jsFn Unit
            val ina = args.getOrNull(1) as? Scriptable ?: return@jsFn Unit
            val out = args.getOrNull(2) as? Scriptable ?: return@jsFn Unit
            val timeMs = (args.getOrNull(3) as? Number)?.toFloat() ?: 250f
            val feedback = (args.getOrNull(4) as? Number)?.toFloat() ?: 0.3f
            val mix = (args.getOrNull(5) as? Number)?.toFloat() ?: 0.4f
            val delaySamp = (sampleRate * timeMs / 1000f).toInt().coerceAtLeast(1)
            delays.getOrNull(h)?.processBlock(ina, out, delaySamp, feedback, mix)
            Unit
        })

        host.put("createLfo", host, jsFn { args ->
            val type = (args.getOrNull(0) as? String) ?: "sine"
            val rate = (args.getOrNull(1) as? Number)?.toFloat() ?: 1f
            lfos.add(Lfo(type, rate))
            (lfos.size - 1).toDouble()
        })
        host.put("lfoSetRate", host, jsFn { args ->
            val h = (args.getOrNull(0) as? Number)?.toInt() ?: return@jsFn Unit
            val r = (args.getOrNull(1) as? Number)?.toFloat() ?: 1f
            lfos.getOrNull(h)?.rate = r
            Unit
        })
        /** Advance the LFO by [length] samples and write its samples into [out]. */
        host.put("lfoBlock", host, jsFn { args ->
            val h = (args.getOrNull(0) as? Number)?.toInt() ?: return@jsFn Unit
            val out = args.getOrNull(1) as? Scriptable ?: return@jsFn Unit
            val length = (args.getOrNull(2) as? Number)?.toInt() ?: lengthOf(out)
            lfos.getOrNull(h)?.processBlock(out, length, sampleRate)
            Unit
        })

        ScriptableObject.putProperty(scope, "host", host)
    }

    private fun Scriptable.context(): Context = Context.getCurrentContext()
        ?: error("NativeDspHost.install must be called inside Context.enter()")

    private inline fun jsFn(crossinline impl: (Array<out Any?>) -> Any): BaseFunction =
        object : BaseFunction() {
            override fun call(
                cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>,
            ): Any = impl(args)
        }

    /** Best-effort element count for a JS array — reads the `length` property. */
    private fun lengthOf(s: Scriptable): Int =
        (ScriptableObject.getProperty(s, "length") as? Number)?.toInt() ?: 0

    /** Pull a JS array (NativeArray or any indexed Scriptable) into a Kotlin
     *  FloatArray. One getProperty per element, but the inner DSP loop that
     *  consumes the FloatArray runs at full Kotlin/JVM speed afterwards. */
    private fun readBlock(src: Scriptable, n: Int): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = (ScriptableObject.getProperty(src, i) as? Number)?.toFloat() ?: 0f
        }
        return out
    }

    /** Inverse of [readBlock] — pushes a Kotlin FloatArray back to JS. */
    private fun writeBlock(dst: Scriptable, samples: FloatArray) {
        for (i in samples.indices) {
            ScriptableObject.putProperty(dst, i, samples[i].toDouble())
        }
    }

    // ── Primitive: biquad ────────────────────────────────────────────────
    /**
     * RBJ-cookbook biquad — type, frequency and Q are reconfigurable per
     * block. State (z⁻¹, z⁻²) persists across blocks so a slow modulation
     * of `freq` doesn't click.
     */
    private class Biquad {
        var b0 = 1f; var b1 = 0f; var b2 = 0f
        var a1 = 0f; var a2 = 0f
        var x1 = 0f; var x2 = 0f
        var y1 = 0f; var y2 = 0f

        fun setType(type: String, freqHz: Float, q: Float, sr: Int) {
            val w0 = 2.0 * PI * freqHz / sr
            val cosW = cos(w0)
            val sinW = sin(w0)
            val alpha = sinW / (2.0 * q.coerceAtLeast(0.001f))
            val a0: Double
            when (type) {
                "highpass" -> {
                    b0 = ((1 + cosW) / 2.0).toFloat()
                    b1 = (-(1 + cosW)).toFloat()
                    b2 = ((1 + cosW) / 2.0).toFloat()
                    a0 = 1 + alpha
                    a1 = (-2.0 * cosW / a0).toFloat()
                    a2 = ((1 - alpha) / a0).toFloat()
                }
                "bandpass" -> {
                    b0 = (alpha / (1 + alpha)).toFloat()
                    b1 = 0f
                    b2 = (-alpha / (1 + alpha)).toFloat()
                    a0 = 1 + alpha
                    a1 = (-2.0 * cosW / a0).toFloat()
                    a2 = ((1 - alpha) / a0).toFloat()
                }
                else -> { // lowpass (default)
                    b0 = ((1 - cosW) / 2.0).toFloat()
                    b1 = (1 - cosW).toFloat()
                    b2 = ((1 - cosW) / 2.0).toFloat()
                    a0 = 1 + alpha
                    a1 = (-2.0 * cosW / a0).toFloat()
                    a2 = ((1 - alpha) / a0).toFloat()
                }
            }
            val inv = 1f / a0.toFloat()
            b0 *= inv; b1 *= inv; b2 *= inv
        }

        fun processBlock(input: Scriptable, output: Scriptable) {
            val n = lengthOf(input)
            val src = readBlock(input, n)
            val dst = FloatArray(n)
            for (i in 0 until n) {
                val x = src[i]
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x
                y2 = y1; y1 = y
                dst[i] = y
            }
            writeBlock(output, dst)
        }
    }

    // ── Primitive: delay line ────────────────────────────────────────────
    /**
     * Feedback delay with linear-interp read tap. Block-level config of
     * time / feedback / mix means a slow modulating LFO (via `lfoBlock`
     * mixing into the time value) still sounds smooth because the
     * underlying buffer keeps rolling.
     */
    private class DelayLine(maxSamples: Int) {
        private val buf = FloatArray(maxSamples)
        private val bufLen = buf.size
        private var writeIdx = 0

        fun processBlock(
            input: Scriptable,
            output: Scriptable,
            delaySamples: Int,
            feedback: Float,
            mix: Float,
        ) {
            val n = lengthOf(input)
            val src = readBlock(input, n)
            val dst = FloatArray(n)
            val d = delaySamples.coerceIn(1, bufLen - 1)
            for (i in 0 until n) {
                val x = src[i]
                var readIdx = writeIdx - d
                if (readIdx < 0) readIdx += bufLen
                val delayed = buf[readIdx]
                buf[writeIdx] = x + delayed * feedback
                writeIdx = (writeIdx + 1) % bufLen
                dst[i] = x * (1f - mix) + delayed * mix
            }
            writeBlock(output, dst)
        }
    }

    // ── Primitive: LFO ───────────────────────────────────────────────────
    /**
     * Polyphase LFO emitting one float per output sample. Plugins read the
     * generated block to modulate other params per-sample without paying JS
     * per-sample math.
     */
    private class Lfo(val type: String, var rate: Float) {
        private var phase = 0f

        fun processBlock(output: Scriptable, n: Int, sr: Int) {
            val dst = FloatArray(n)
            val phaseInc = (2.0 * PI * rate / sr).toFloat()
            val twoPi = (2.0 * PI).toFloat()
            for (i in 0 until n) {
                dst[i] = when (type) {
                    "triangle" -> {
                        val t = phase / twoPi
                        (if (t < 0.5f) 4f * t - 1f else 3f - 4f * t)
                    }
                    "saw" -> 2f * (phase / twoPi) - 1f
                    "square" -> if (phase < PI.toFloat()) 1f else -1f
                    else -> sin(phase)
                }
                phase += phaseInc
                if (phase > twoPi) phase -= twoPi
            }
            writeBlock(output, dst)
        }
    }
}
