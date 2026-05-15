package com.vocalmonitor.audio

/**
 * In-memory audio payload: raw PCM. Persisted as WAV in user-visible storage.
 *
 * Pitch is not stored — it's recomputed live from the mic. [PitchPoint] is
 * still here as the pitch-graph data type used by the UI (rolling preview
 * buffer + auto-follow logic).
 */
data class RecordingFile(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcm: ByteArray,
) {
    val durationMs: Int
        get() {
            val bytesPerSample = bitsPerSample / 8
            val totalSamples = pcm.size / (bytesPerSample * channels)
            return ((totalSamples.toLong() * 1000L) / sampleRate).toInt()
        }

    data class PitchPoint(val timestampMs: Int, val freqHz: Float)
}
