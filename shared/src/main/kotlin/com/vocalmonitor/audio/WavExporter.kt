package com.vocalmonitor.audio

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps PCM samples in a 44-byte RIFF/WAV header. Bit-perfect round-trip
 * with whatever was captured.
 */
object WavExporter {

    fun export(file: RecordingFile, out: File) {
        out.parentFile?.mkdirs()
        out.outputStream().use { writeTo(it, file) }
    }

    fun writeTo(out: OutputStream, file: RecordingFile) {
        out.write(buildHeader(file))
        out.write(file.pcm)
    }

    private fun buildHeader(file: RecordingFile): ByteArray {
        val byteRate = file.sampleRate * file.channels * (file.bitsPerSample / 8)
        val blockAlign = file.channels * (file.bitsPerSample / 8)
        val dataLen = file.pcm.size
        val riffLen = 36 + dataLen

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(riffLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(file.channels.toShort())
        header.putInt(file.sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(file.bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen)
        return header.array()
    }
}
