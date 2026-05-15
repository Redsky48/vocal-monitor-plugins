package com.vocalmonitor.audio

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.typedarrays.NativeArrayBuffer
import org.mozilla.javascript.typedarrays.NativeFloat32Array
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioWorklet-compatible JavaScript audio plugin host.
 *
 * Plugins are written against (a subset of) the browser
 * [AudioWorkletProcessor] contract — `static get parameterDescriptors()`,
 * `process(inputs, outputs, parameters)`, `registerProcessor(name, cls)`
 * — so devs can move existing web-audio plugins over with minimal
 * adaptation. We use Mozilla Rhino in interpreter mode (no bytecode gen
 * — keeps the dex tool happy on Android) and stand up a minimal global
 * scope that:
 *
 *  - defines a stub `AudioWorkletProcessor` class the user code extends
 *  - exposes `registerProcessor` to capture each registered class
 *  - publishes `sampleRate` so plugins can compute filter coefficients
 *
 * Each plugin processor is instantiated per graph-node; calling
 * [process] then feeds one block at a time through the user's process()
 * method. Audio buffers cross the bridge as plain JS arrays — slow vs.
 * typed arrays but simpler to wire and fast enough for monophonic vocal
 * blocks (1024 samples ≈ 23 ms at 44.1 kHz, JS budget plenty even on
 * mid-range Android).
 */
object JsPluginEngine {

    /** Captured constructor of a `registerProcessor`'d class, plus metadata. */
    private data class RegisteredProcessor(
        val plugin: JsPlugin,
        val classRef: BaseFunction,
    )

    private val registered: MutableMap<String, RegisteredProcessor> = mutableMapOf()
    /**
     * Names handed to [registerProcessor] during the current [load] call.
     * Used by [load]'s "did this eval actually register anything?" check —
     * a plain before/after diff fails for re-installs because the name
     * was already in [registered], so the new copy looks like a no-op.
     */
    private var lastLoadRegistered: MutableList<String> = mutableListOf()
    private var sampleRate: Int = 44_100

    @Volatile private var baseScope: ScriptableObject? = null

    /**
     * Update the host sample rate. New instances see the new value via
     * the `sampleRate` global immediately; long-running instances must
     * be torn down and recreated to pick it up (matches browser
     * AudioWorklet semantics).
     */
    fun setSampleRate(rate: Int) {
        sampleRate = rate.coerceAtLeast(8000)
        NativeDspHost.setSampleRate(sampleRate)
        baseScope?.let { scope ->
            withContext { ctx ->
                ScriptableObject.putProperty(scope, "sampleRate", sampleRate.toDouble())
            }
        }
    }

    /** Plugins currently registered, in registration order. */
    fun list(): List<JsPlugin> = registered.values.map { it.plugin }

    fun get(name: String): JsPlugin? = registered[name]?.plugin

    /**
     * Evaluate a plugin source. Any `registerProcessor` calls it makes
     * are captured into the registry. Re-registering an existing name
     * replaces the previous entry — supports hot-reload.
     */
    fun load(source: String, sourceName: String = "plugin.js"): Result<List<JsPlugin>> = runCatching {
        val scope = ensureScope()
        lastLoadRegistered = mutableListOf()
        withContext { ctx ->
            ctx.evaluateString(scope, source, sourceName, 1, null)
        }
        lastLoadRegistered.mapNotNull { registered[it]?.plugin }
    }

    /**
     * Spin up a fresh processor instance ready for [process] calls.
     * Returns null if [pluginName] isn't registered or the JS
     * constructor threw. The returned Scriptable is opaque — pass it
     * back to [process] as-is.
     */
    fun newInstance(pluginName: String): Scriptable? {
        val r = registered[pluginName] ?: return null
        return try {
            withContext { ctx ->
                r.classRef.construct(ctx, ensureScope(), emptyArray())
            }
        } catch (_: Throwable) { null }
    }

    /**
     * Run [instance].process for one block. [input] is read, [output]
     * is filled in-place. [params] are pushed in as `parameters` —
     * each one wrapped as a length-1 array to match AudioWorklet's
     * a-rate / k-rate signature (we always supply k-rate i.e. a single
     * value per block).
     *
     * Returns true on success, false if the user code threw (in which
     * case [output] may have been partially written — caller should
     * treat as silence).
     */
    fun process(
        instance: Scriptable,
        input: FloatArray,
        output: FloatArray,
        params: Map<String, Float>,
    ): Boolean = withContext { ctx ->
        val scope = ensureScope()
        val processFn = ScriptableObject.getProperty(instance, "process") as? Function
            ?: return@withContext false
        try {
            // Plain NativeArray bridge — we tried Float32Array sharing the
            // underlying byte[] via NativeArrayBuffer for speed, but Rhino's
            // typed-array endianness convention didn't match the round-trip
            // we built around it and every plugin produced noise. Reverting
            // to per-element putProperty/getProperty until the typed-array
            // path is debugged. The 4096-sample block (vs the old 1024)
            // still amortises this overhead 4× over the original setup.
            val inputCh = toJsArray(ctx, scope, input)
            val inputArr = ctx.newArray(
                scope,
                arrayOf<Any>(ctx.newArray(scope, arrayOf<Any>(inputCh))),
            )
            val outputJs = toJsArray(ctx, scope, FloatArray(output.size))
            val outputArr = ctx.newArray(
                scope,
                arrayOf<Any>(ctx.newArray(scope, arrayOf<Any>(outputJs))),
            )
            val paramsObj = ctx.newObject(scope).apply {
                for ((k, v) in params) {
                    ScriptableObject.putProperty(
                        this, k,
                        ctx.newArray(scope, arrayOf<Any>(v.toDouble())),
                    )
                }
            }
            processFn.call(ctx, scope, instance, arrayOf<Any>(inputArr, outputArr, paramsObj))
            for (i in output.indices) {
                output[i] = (ScriptableObject.getProperty(outputJs, i) as? Number)?.toFloat() ?: 0f
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun toJsArray(ctx: Context, scope: Scriptable, src: FloatArray): NativeArray {
        val arr = ctx.newArray(scope, src.size) as NativeArray
        for (i in src.indices) {
            ScriptableObject.putProperty(arr, i, src[i].toDouble())
        }
        return arr
    }

    private fun ensureScope(): ScriptableObject {
        baseScope?.let { return it }
        return withContext { ctx ->
            val scope = ctx.initStandardObjects()
            // Plugin-host globals
            ScriptableObject.putProperty(scope, "sampleRate", sampleRate.toDouble())
            // Rhino's interpreter (the only mode that works on Android —
            // compiler emits JVM bytecode which Dalvik / ART can't run)
            // doesn't parse ES6 `class` syntax. We define the stub as a
            // plain function instead; plugins use ES5 prototype style.
            ctx.evaluateString(
                scope,
                "function AudioWorkletProcessor() {}",
                "[host]",
                1, null,
            )
            // registerProcessor(name, classRef) captures into the
            // Kotlin map. Pulls displayName + parameterDescriptors off
            // the class itself, mirroring how the AudioWorklet spec
            // expects them as `static get` properties.
            ScriptableObject.putProperty(
                scope, "registerProcessor",
                object : BaseFunction() {
                    override fun call(
                        cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>,
                    ): Any {
                        val name = args.getOrNull(0)?.toString() ?: return Any()
                        val cls = args.getOrNull(1) as? BaseFunction ?: return Any()
                        val descriptors = readParameterDescriptors(cx, scope, cls)
                        val displayName =
                            (ScriptableObject.getProperty(cls, "displayName") as? String)
                                ?: name
                        registered[name] = RegisteredProcessor(
                            JsPlugin(
                                name = name,
                                displayName = displayName,
                                parameters = descriptors,
                            ),
                            cls,
                        )
                        lastLoadRegistered.add(name)
                        return cls
                    }
                },
            )
            // Native DSP primitives — `host.createBiquad`, `host.delayProcess`, …
            // Plugins that route per-sample math through these get JVM-native
            // speed for the inner loops while keeping the AudioWorklet shape.
            NativeDspHost.install(scope)
            baseScope = scope
            scope
        }
    }

    private fun readParameterDescriptors(
        cx: Context, scope: Scriptable, cls: BaseFunction,
    ): List<JsPluginParam> {
        // AudioWorklet spec: `static get parameterDescriptors()` on the
        // class. Rhino's ES6 class support exposes static getters as
        // a Function on the class — read the property, and if it came
        // back as something callable rather than the array, invoke it
        // manually. Covers both `static get foo()` and plain
        // `MyClass.foo = [...]` assignment styles.
        val raw = ScriptableObject.getProperty(cls, "parameterDescriptors") ?: return emptyList()
        val list = when (raw) {
            is NativeArray -> raw
            is BaseFunction -> raw.call(cx, scope, cls, emptyArray()) as? NativeArray
            is Function -> (raw as Function).call(cx, scope, cls, emptyArray()) as? NativeArray
            else -> null
        } ?: return emptyList()
        val out = ArrayList<JsPluginParam>(list.size)
        for (i in 0 until list.size) {
            val item = ScriptableObject.getProperty(list, i.toInt()) as? NativeObject ?: continue
            val pid = (ScriptableObject.getProperty(item, "name") as? String) ?: continue
            val label = (ScriptableObject.getProperty(item, "label") as? String) ?: pid
            val def = (ScriptableObject.getProperty(item, "defaultValue") as? Number)?.toFloat() ?: 0f
            val lo = (ScriptableObject.getProperty(item, "minValue") as? Number)?.toFloat() ?: 0f
            val hi = (ScriptableObject.getProperty(item, "maxValue") as? Number)?.toFloat() ?: 1f
            out.add(JsPluginParam(pid, label, lo, hi, def))
        }
        return out
    }

    private inline fun <R> withContext(block: (Context) -> R): R {
        val ctx = Context.enter()
        return try {
            // Interpreter mode (-1). We tried compiled mode (=9) via
            // rhino-android's runtime-DEX-conversion path, but ART on
            // current Android versions rejects the generated classes with
            // "can't load this type of class file" — plugin install fails.
            // Sticking with the interpreter keeps every plugin loadable;
            // the speed gap is recovered by the Float32Array bridge, the
            // 4096-sample block size, and the host.* native primitives
            // that route per-sample math through pre-compiled Kotlin.
            ctx.optimizationLevel = -1
            ctx.languageVersion = Context.VERSION_ES6
            block(ctx)
        } finally {
            Context.exit()
        }
    }
}
