package com.vocalmonitor.audio

/**
 * Returns a copy of [file] with the PCM payload trimmed to [startMs]..[endMs].
 * Sample-aligned to the recording's bit depth and channel count.
 */
object PcmTrim {
    fun trim(file: RecordingFile, startMs: Int, endMs: Int): RecordingFile {
        val safeStart = startMs.coerceIn(0, file.durationMs)
        val safeEnd = endMs.coerceIn(safeStart, file.durationMs)
        if (safeStart == 0 && safeEnd == file.durationMs) return file

        val bytesPerSample = file.bitsPerSample / 8
        val frameSize = bytesPerSample * file.channels
        val startByte = ((safeStart.toLong() * file.sampleRate / 1000L) * frameSize).toInt()
            .coerceAtMost(file.pcm.size)
        val endByte = ((safeEnd.toLong() * file.sampleRate / 1000L) * frameSize).toInt()
            .coerceAtMost(file.pcm.size)
        if (endByte <= startByte) {
            return file.copy(pcm = ByteArray(0))
        }
        val trimmed = file.pcm.copyOfRange(startByte, endByte)
        return file.copy(pcm = trimmed)
    }
}
