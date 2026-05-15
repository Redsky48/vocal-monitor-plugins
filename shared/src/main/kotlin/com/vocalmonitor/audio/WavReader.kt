package com.vocalmonitor.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Permissive WAV parser: reads RIFF header, finds the `fmt ` and `data`
 * chunks, returns sample rate / channels / bits + raw PCM payload.
 *
 * Only PCM (format == 1) is supported.
 */
object WavReader {

    data class WavData(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val pcm: ByteArray,
    )

    fun read(input: InputStream): WavData {
        val all = input.readBytes()
        require(all.size >= 44) { "WAV too small: ${all.size} bytes" }
        val buf = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF / WAVE
        require(String(all, 0, 4) == "RIFF") { "not RIFF" }
        require(String(all, 8, 4) == "WAVE") { "not WAVE" }

        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bps = 0
        var pcm: ByteArray? = null

        while (pos + 8 <= all.size) {
            val id = String(all, pos, 4)
            val len = buf.getInt(pos + 4)
            val dataStart = pos + 8
            when (id) {
                "fmt " -> {
                    val format = buf.getShort(dataStart).toInt()
                    require(format == 1) { "non-PCM WAV (format=$format)" }
                    channels = buf.getShort(dataStart + 2).toInt()
                    sampleRate = buf.getInt(dataStart + 4)
                    bps = buf.getShort(dataStart + 14).toInt()
                }
                "data" -> {
                    pcm = all.copyOfRange(dataStart, (dataStart + len).coerceAtMost(all.size))
                }
            }
            pos = dataStart + len
            if (len % 2 == 1) pos++ // chunks are word-aligned
        }
        require(sampleRate > 0 && channels > 0 && bps > 0) { "missing fmt chunk" }
        require(pcm != null) { "missing data chunk" }
        return WavData(sampleRate, channels, bps, pcm)
    }
}
