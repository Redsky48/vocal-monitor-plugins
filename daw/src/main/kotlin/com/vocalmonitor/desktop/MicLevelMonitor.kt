package com.vocalmonitor.desktop

import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt

/**
 * Stand-alone mic-level reader.  Opens ONE `TargetDataLine` on the
 * named (or default) input device, reads ~10ms chunks on a daemon
 * thread, and pushes a normalised RMS [0,1] into [onLevel].
 *
 * Deliberately decoupled from `JavaSoundIO` — that one is the full
 * audio-engine I/O loop (mic + speakers + callback).  This is just
 * a meter, so the user can see "yes my mic is being heard" before
 * the actual audio graph is wired up to play through it.
 *
 * Sample-format: mono 16-bit LE @ 44.1 kHz.  Block: 1024 samples
 * (~23 ms) — chosen for a smooth-looking meter, not for low-latency
 * processing.
 */
class MicLevelMonitor(
    private val deviceName: String?,
    private val onLevel: (Float) -> Unit,
    /** Receives every decoded block as floats in [-1,1].  Runs on the
     *  capture thread — must not block.  Callers tap this to feed the
     *  audio into plugin `process()` calls. */
    private val onSamples: ((FloatArray) -> Unit)? = null,
) {
    private val format = AudioFormat(44_100f, 16, 1, true, false)
    private val running = AtomicBoolean(false)
    private var line: TargetDataLine? = null
    private var thread: Thread? = null

    fun start() {
        if (running.get()) return
        line = openLine() ?: return
        line!!.start()
        running.set(true)
        thread = Thread({
            val bytes = ByteArray(2048)   // 1024 samples × 2 B
            val floats = FloatArray(1024)
            try {
                while (running.get()) {
                    var read = 0
                    while (read < bytes.size && running.get()) {
                        val n = line!!.read(bytes, read, bytes.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read >= 2) {
                        bytesToFloat(bytes, floats, read / 2)
                        onLevel(rmsOf(bytes, read))
                        onSamples?.invoke(floats)
                    }
                }
            } catch (_: Throwable) { /* shutdown */ }
        }, "mic-level-monitor").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt(); thread = null
        try { line?.stop(); line?.close() } catch (_: Throwable) {}
        line = null
        onLevel(0f)
    }

    private fun openLine(): TargetDataLine? = runCatching {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val mixerInfo = if (deviceName.isNullOrBlank() || deviceName == "Default") null
                       else AudioSystem.getMixerInfo().firstOrNull {
                           it.name.trim() == deviceName.trim()
                       }
        val raw = if (mixerInfo != null) {
            AudioSystem.getMixer(mixerInfo).getLine(info)
        } else {
            AudioSystem.getLine(info)
        }
        (raw as TargetDataLine).apply { open(format, 4096) }
    }.getOrNull()

    private fun bytesToFloat(src: ByteArray, dst: FloatArray, samples: Int) {
        val n = if (samples < dst.size) samples else dst.size
        for (i in 0 until n) {
            val lo = src[2 * i].toInt() and 0xFF
            val hi = src[2 * i + 1].toInt()
            val s = (hi shl 8) or lo
            dst[i] = s.toShort() / 32768f
        }
        // Zero out the tail when an underread happens.
        for (i in n until dst.size) dst[i] = 0f
    }

    private fun rmsOf(bytes: ByteArray, n: Int): Float {
        var sumSq = 0.0
        var i = 0
        val samples = n / 2
        while (i < n - 1) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()        // sign-extended
            val s = (hi shl 8) or lo
            val sShort = s.toShort().toInt()
            sumSq += sShort.toDouble() * sShort.toDouble()
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSq / samples) / 32768.0
        return rms.toFloat().coerceIn(0f, 1f)
    }
}
