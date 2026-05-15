package com.vocalmonitor.desktop

import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Desktop audio I/O — wraps Java Sound (`javax.sound.sampled`) with
 * the same callback-style interface the slim Android app gets from
 * its `Recorder` / `BackgroundTrackPlayer` pair, so the cross-
 * platform graph engine can drive either backend without caring
 * which.
 *
 * Mono PCM 16-bit, mic in / speakers out, configurable sample rate
 * and block size.  Internal helper thread reads / writes blocks at
 * audio rate; the graph engine's process callback runs on that thread.
 */
class JavaSoundIO(
    val sampleRate: Int = 44_100,
    val blockSize: Int = 1024,
) {
    private val format = AudioFormat(
        sampleRate.toFloat(), 16, 1, true, false,
    )

    private var micLine: TargetDataLine? = null
    private var spkLine: SourceDataLine? = null
    private val running = AtomicBoolean(false)
    private var ioThread: Thread? = null

    /**
     * Open the default mic + default speakers and start the audio
     * loop.  The [callback] receives a float[] of `blockSize` samples
     * (mic input in [-1, 1]) and must fill the SAME-shaped output
     * buffer with the audio to play back.
     *
     * `callback` runs on the audio thread — must be allocation-free
     * and fast (under 10 ms for a 1024-sample block at 44.1 kHz).
     */
    fun start(callback: (input: FloatArray, output: FloatArray) -> Unit) {
        if (running.get()) return
        micLine = AudioSystem.getTargetDataLine(format).apply {
            open(format, blockSize * 2 * 2)            // 2 blocks of int16 = 4× bytes
            start()
        }
        spkLine = AudioSystem.getSourceDataLine(format).apply {
            open(format, blockSize * 2 * 2)
            start()
        }
        running.set(true)
        ioThread = Thread({
            val byteBuf = ByteArray(blockSize * 2)
            val inFloat = FloatArray(blockSize)
            val outFloat = FloatArray(blockSize)
            try {
                while (running.get()) {
                    var read = 0
                    while (read < byteBuf.size && running.get()) {
                        val n = micLine!!.read(byteBuf, read, byteBuf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    bytesToFloat(byteBuf, inFloat)
                    callback(inFloat, outFloat)
                    floatToBytes(outFloat, byteBuf)
                    spkLine!!.write(byteBuf, 0, byteBuf.size)
                }
            } catch (_: Throwable) { /* shutdown */ }
        }, "daw-audio-io").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        ioThread?.interrupt()
        ioThread = null
        try { micLine?.stop(); micLine?.close() } catch (_: Throwable) {}
        try { spkLine?.drain(); spkLine?.stop(); spkLine?.close() } catch (_: Throwable) {}
        micLine = null
        spkLine = null
    }

    fun isRunning(): Boolean = running.get()

    // ── int16 LE  ↔  float [-1, 1] ──
    private fun bytesToFloat(src: ByteArray, dst: FloatArray) {
        val n = dst.size
        for (i in 0 until n) {
            val lo = src[2 * i].toInt() and 0xFF
            val hi = src[2 * i + 1].toInt()
            val sample = (hi shl 8) or lo
            dst[i] = sample / 32768f
        }
    }
    private fun floatToBytes(src: FloatArray, dst: ByteArray) {
        val n = src.size
        for (i in 0 until n) {
            var v = src[i]
            if (v > 1f) v = 1f
            if (v < -1f) v = -1f
            val s = (v * 32767f).toInt()
            dst[2 * i]     = (s and 0xFF).toByte()
            dst[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
    }
}
