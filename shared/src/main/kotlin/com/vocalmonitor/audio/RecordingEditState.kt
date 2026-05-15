package com.vocalmonitor.audio

/**
 * Everything we need to re-create the editor view of a saved recording:
 * trim window, FX graph, and (optionally) pitch-segmenter output.
 *
 * Serialised line-by-line so we don't pull in a JSON dep. The format is
 * `key=value` per line; unknown keys are ignored (forward-compat). Values
 * that themselves contain `\n` or `|` (the graph encoding uses both) are
 * escaped via [escape] / [unescape]; we deliberately do NOT use the
 * `;`-delimited single-line layout the chain-preset code uses, because
 * the field list here is short enough that one-key-per-line is cleaner
 * and lets future fields be added without re-parsing old files.
 *
 * Paired with a raw PCM sidecar (untouched mic capture) so a re-save
 * renders fresh — never compounding effects onto already-processed audio.
 */
data class RecordingEditState(
    val trimStartMs: Int,
    val trimEndMs: Int,
    /** Encoded [EffectGraph]; empty string = use the default chain. */
    val effectGraphEncoded: String,
    /**
     * Pitch-segmenter override, or empty if the user never opened the
     * pitch editor on this take. Re-decoded against the original analysis.
     */
    val pitchSegmentsEncoded: String,
    val savedAtMs: Long,
) {
    fun encode(): String = buildString {
        append("trimStartMs=").append(trimStartMs).append('\n')
        append("trimEndMs=").append(trimEndMs).append('\n')
        append("effectGraph=").append(escape(effectGraphEncoded)).append('\n')
        append("pitchSegments=").append(escape(pitchSegmentsEncoded)).append('\n')
        append("savedAtMs=").append(savedAtMs).append('\n')
    }

    companion object {
        fun decode(text: String): RecordingEditState? {
            var trimStart = 0
            var trimEnd = 0
            var graph = ""
            var segs = ""
            var savedAt = 0L
            var anyValid = false
            for (line in text.split('\n')) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val k = line.substring(0, eq)
                val v = line.substring(eq + 1)
                when (k) {
                    "trimStartMs" -> { trimStart = v.toIntOrNull() ?: 0; anyValid = true }
                    "trimEndMs" -> { trimEnd = v.toIntOrNull() ?: 0; anyValid = true }
                    "effectGraph" -> { graph = unescape(v); anyValid = true }
                    "pitchSegments" -> { segs = unescape(v); anyValid = true }
                    "savedAtMs" -> { savedAt = v.toLongOrNull() ?: 0L }
                }
            }
            if (!anyValid) return null
            return RecordingEditState(trimStart, trimEnd, graph, segs, savedAt)
        }

        private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("\n", "\\n")
        private fun unescape(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2; continue }
                        '\\' -> { sb.append('\\'); i += 2; continue }
                    }
                }
                sb.append(c); i++
            }
            return sb.toString()
        }
    }
}
