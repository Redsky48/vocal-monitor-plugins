package com.vocalmonitor.audio

/**
 * Snapshot data for each effect — what we put in the undo/redo history and
 * what gets stored as a preset. Plain `data class` so equality + history
 * comparison Just Work.
 */

data class EqState(
    val enabled: Boolean,
    val parallel: Boolean = false,
    val gainsDb: List<Float>,
) {
    fun encode(): String =
        "${if (enabled) 1 else 0};${gainsDb.joinToString(",") { "%.2f".format(it) }};${if (parallel) 1 else 0}"

    companion object {
        val FLAT = EqState(enabled = true, gainsDb = List(10) { 0f })

        val FACTORY: List<NamedPreset<EqState>> = listOf(
            NamedPreset("Flat", FLAT),
            NamedPreset("Vocal Lift", EqState(true, false, listOf(-2f, -1f, 0f, -1f, 0f, 0f, 1.5f, 2.5f, 1.5f, 0f))),
            // Voice Clarity — rumble down + boxy mids out, air shelf up.
            // Bands run 31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k.
            // Negative cuts at 31-500 clear mic rumble and chest mud; small
            // scoop through 1-2k drops nasal honk; the 4k-16k shelf lifts
            // intelligibility + sparkle without poking sibilants.
            NamedPreset("Voice Clarity",
                EqState(true, false, listOf(-3f, -2f, -2f, -3f, -2f, -1f, 0f, 1f, 3f, 3f))),
            NamedPreset("Bright", EqState(true, false, listOf(-1f, -1f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 3f))),
            NamedPreset("Warm", EqState(true, false, listOf(0f, 1f, 2f, 1f, 0f, 0f, -1f, -2f, -2f, -1f))),
            NamedPreset("Telephone", EqState(true, false, listOf(-8f, -8f, -4f, 2f, 4f, 4f, 2f, -4f, -8f, -8f))),
            NamedPreset("Cut Mud", EqState(true, false, listOf(0f, -2f, -3f, -3f, -1f, 0f, 0f, 1f, 1f, 0f))),
        )

        fun decode(s: String?): EqState? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(";")
            if (parts.size < 2) return null
            val enabled = parts[0] == "1"
            val gains = parts[1].split(",").mapNotNull { it.toFloatOrNull() }
            if (gains.size != 10) return null
            val parallel = parts.getOrNull(2) == "1"
            return EqState(enabled, parallel, gains)
        }
    }
}

data class CompressorState(
    val enabled: Boolean,
    val parallel: Boolean = false,
    val thresholdDb: Float,
    val ratio: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val kneeDb: Float,
    val makeupDb: Float,
) {
    fun encode(): String = "${if (enabled) 1 else 0};" +
        "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f".format(
            thresholdDb, ratio, attackMs, releaseMs, kneeDb, makeupDb
        ) + ";${if (parallel) 1 else 0}"

    companion object {
        val DEFAULT = CompressorState(true, false, -18f, 4f, 5f, 120f, 6f, 4f)

        val FACTORY: List<NamedPreset<CompressorState>> = listOf(
            NamedPreset("Vocals Mild", CompressorState(true, false, -18f, 3f, 8f, 150f, 8f, 3f)),
            NamedPreset("Vocals Tight", CompressorState(true, false, -16f, 5f, 3f, 100f, 4f, 5f)),
            NamedPreset("Light Glue", CompressorState(true, false, -22f, 2f, 15f, 200f, 12f, 2f)),
            NamedPreset("Heavy", CompressorState(true, false, -14f, 8f, 1f, 80f, 2f, 8f)),
            NamedPreset("Limiter", CompressorState(true, false, -3f, 20f, 0.5f, 50f, 0f, 0f)),
            NamedPreset("Parallel NY", CompressorState(true, true, -28f, 8f, 1f, 80f, 0f, 0f)),
        )

        fun decode(s: String?): CompressorState? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(";")
            if (parts.size < 2) return null
            val enabled = parts[0] == "1"
            val v = parts[1].split(",").mapNotNull { it.toFloatOrNull() }
            if (v.size != 6) return null
            val parallel = parts.getOrNull(2) == "1"
            return CompressorState(enabled, parallel, v[0], v[1], v[2], v[3], v[4], v[5])
        }
    }
}

data class NoiseGateState(
    val enabled: Boolean,
    val parallel: Boolean = false,
    /** Open threshold in dBFS. */
    val thresholdDb: Float,
    /** Hysteresis (close = threshold - hysteresis). */
    val hysteresisDb: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val holdMs: Float,
    /** Floor attenuation when closed (-60 dB ≈ mute). */
    val rangeDb: Float,
) {
    fun encode(): String = "${if (enabled) 1 else 0};" +
        "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f".format(
            thresholdDb, hysteresisDb, attackMs, releaseMs, holdMs, rangeDb
        ) + ";${if (parallel) 1 else 0}"

    companion object {
        val DEFAULT = NoiseGateState(true, false, -40f, 3f, 5f, 120f, 30f, -60f)

        val FACTORY: List<NamedPreset<NoiseGateState>> = listOf(
            NamedPreset("Vocals — kill room", NoiseGateState(true, false, -38f, 4f, 4f, 100f, 20f, -60f)),
            NamedPreset("Whisper-safe", NoiseGateState(true, false, -52f, 6f, 8f, 250f, 60f, -45f)),
            NamedPreset("Mute breathy", NoiseGateState(true, false, -34f, 3f, 2f, 80f, 15f, -90f)),
            NamedPreset("Gentle duck", NoiseGateState(true, false, -42f, 5f, 10f, 180f, 40f, -18f)),
        )

        fun decode(s: String?): NoiseGateState? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(";")
            if (parts.size < 2) return null
            val enabled = parts[0] == "1"
            val v = parts[1].split(",").mapNotNull { it.toFloatOrNull() }
            if (v.size != 6) return null
            val parallel = parts.getOrNull(2) == "1"
            return NoiseGateState(enabled, parallel, v[0], v[1], v[2], v[3], v[4], v[5])
        }
    }
}

data class ReverbState(
    val enabled: Boolean,
    val parallel: Boolean = false,
    val size: Float,
    val decaySec: Float,
    val preDelayMs: Float,
    val damping: Float,
    val diffusion: Float,
    val modulation: Float,
    val mix: Float,
) {
    fun encode(): String = "${if (enabled) 1 else 0};" +
        "%.3f,%.3f,%.2f,%.3f,%.3f,%.3f,%.3f".format(
            size, decaySec, preDelayMs, damping, diffusion, modulation, mix
        ) + ";${if (parallel) 1 else 0}"

    companion object {
        val DEFAULT = ReverbState(true, false, 0.5f, 2f, 20f, 0.5f, 0.7f, 0.3f, 0.25f)

        val FACTORY: List<NamedPreset<ReverbState>> = listOf(
            NamedPreset("Vocal Plate", ReverbState(true, false, 0.4f, 1.8f, 12f, 0.4f, 0.8f, 0.25f, 0.22f)),
            NamedPreset("Tight Room", ReverbState(true, false, 0.25f, 0.9f, 5f, 0.55f, 0.65f, 0.15f, 0.18f)),
            NamedPreset("Hall", ReverbState(true, false, 0.7f, 4.5f, 35f, 0.45f, 0.85f, 0.4f, 0.3f)),
            NamedPreset("Cathedral", ReverbState(true, false, 0.95f, 9f, 60f, 0.6f, 0.9f, 0.5f, 0.35f)),
            NamedPreset("Warm Studio", ReverbState(true, false, 0.35f, 1.4f, 15f, 0.65f, 0.7f, 0.2f, 0.18f)),
            NamedPreset("Subtle Air", ReverbState(true, false, 0.5f, 1.2f, 8f, 0.35f, 0.75f, 0.35f, 0.12f)),
            // Parallel-routed examples — adds wet on top of full dry signal
            NamedPreset("Parallel Plate", ReverbState(true, true, 0.4f, 1.8f, 12f, 0.4f, 0.8f, 0.25f, 0.5f)),
        )

        fun decode(s: String?): ReverbState? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(";")
            if (parts.size < 2) return null
            val enabled = parts[0] == "1"
            val v = parts[1].split(",").mapNotNull { it.toFloatOrNull() }
            if (v.size != 7) return null
            val parallel = parts.getOrNull(2) == "1"
            return ReverbState(enabled, parallel, v[0], v[1], v[2], v[3], v[4], v[5], v[6])
        }
    }
}

data class PitchCorrectState(
    val enabled: Boolean,
    val strength: Float,
    val speedMs: Float,
    val transposeSemitones: Int,
    val retuneCents: Int,
    val scaleRoot: Int,
    val scaleOrdinal: Int,
    val mix: Float,
) {
    fun toSettings(): PitchCorrector.Settings = PitchCorrector.Settings(
        enabled = enabled,
        strength = strength,
        speedMs = speedMs,
        transposeSemitones = transposeSemitones,
        retuneCents = retuneCents,
        scaleRoot = scaleRoot,
        scale = PitchCorrector.Scale.values().getOrNull(scaleOrdinal) ?: PitchCorrector.Scale.Chromatic,
        mix = mix,
    )

    fun encode(): String = "${if (enabled) 1 else 0};" +
        "%.3f,%.2f,%d,%d,%d,%d,%.3f".format(
            strength, speedMs, transposeSemitones, retuneCents,
            scaleRoot, scaleOrdinal, mix,
        )

    companion object {
        val DEFAULT = PitchCorrectState(
            enabled = false,
            strength = 0.6f,
            speedMs = 35f,
            transposeSemitones = 0,
            retuneCents = 0,
            scaleRoot = 0,
            scaleOrdinal = PitchCorrector.Scale.Chromatic.ordinal,
            mix = 1f,
        )

        val FACTORY: List<NamedPreset<PitchCorrectState>> = listOf(
            NamedPreset("Off", DEFAULT),
            NamedPreset("Subtle Tune", DEFAULT.copy(enabled = true, strength = 0.4f, speedMs = 60f)),
            NamedPreset("Studio", DEFAULT.copy(enabled = true, strength = 0.7f, speedMs = 30f)),
            NamedPreset("Cher Effect", DEFAULT.copy(enabled = true, strength = 1f, speedMs = 5f)),
            NamedPreset("Major Pop", DEFAULT.copy(
                enabled = true, strength = 0.65f, speedMs = 40f,
                scaleOrdinal = PitchCorrector.Scale.Major.ordinal,
            )),
            NamedPreset("Minor Ballad", DEFAULT.copy(
                enabled = true, strength = 0.55f, speedMs = 50f,
                scaleOrdinal = PitchCorrector.Scale.Minor.ordinal,
            )),
        )

        fun decode(s: String?): PitchCorrectState? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(";")
            if (parts.size < 2) return null
            val enabled = parts[0] == "1"
            val v = parts[1].split(",")
            if (v.size < 7) return null
            return PitchCorrectState(
                enabled = enabled,
                strength = v[0].toFloatOrNull() ?: 0.6f,
                speedMs = v[1].toFloatOrNull() ?: 35f,
                transposeSemitones = v[2].toIntOrNull() ?: 0,
                retuneCents = v[3].toIntOrNull() ?: 0,
                scaleRoot = v[4].toIntOrNull() ?: 0,
                scaleOrdinal = v[5].toIntOrNull() ?: PitchCorrector.Scale.Chromatic.ordinal,
                mix = v[6].toFloatOrNull() ?: 1f,
            )
        }
    }
}

/** Named preset for any state type — used for both factory + user lists. */
data class NamedPreset<S>(val name: String, val state: S) {
    fun encodeWithName(encodeState: (S) -> String): String =
        "${name.replace("|", "_")}|${encodeState(state)}"
}

/** Parse a `name|encodedState` line back into a NamedPreset. */
fun <S> decodeNamedPreset(line: String, decode: (String) -> S?): NamedPreset<S>? {
    val pipe = line.indexOf('|')
    if (pipe <= 0) return null
    val name = line.substring(0, pipe)
    val state = decode(line.substring(pipe + 1)) ?: return null
    return NamedPreset(name, state)
}
