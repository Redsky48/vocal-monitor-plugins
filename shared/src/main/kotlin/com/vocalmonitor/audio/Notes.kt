package com.vocalmonitor.audio

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * MIDI/note utilities. A4 = 440 Hz = MIDI 69.
 */
object Notes {

    private const val A4_FREQ = 440.0
    private const val A4_MIDI = 69

    private val NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F",
        "F#", "G", "G#", "A", "A#", "B"
    )

    /** Frequency (Hz) → fractional MIDI number. */
    fun freqToMidi(freq: Double): Double =
        12.0 * (ln(freq / A4_FREQ) / ln(2.0)) + A4_MIDI

    /** MIDI number → frequency (Hz). */
    fun midiToFreq(midi: Double): Double =
        A4_FREQ * 2.0.pow((midi - A4_MIDI) / 12.0)

    /** "G3", "C#4" etc. for an integer MIDI value. */
    fun midiToName(midi: Int): String {
        val pc = ((midi % 12) + 12) % 12
        val octave = midi / 12 - 1
        return "${NAMES[pc]}$octave"
    }

    data class NoteInfo(
        /** Closest semitone name, e.g. "G3". */
        val name: String,
        /** Cents offset from that semitone, signed. */
        val cents: Int,
    )

    /** Snap a freq to its nearest semitone, return name + cents offset. */
    fun classify(freq: Double): NoteInfo? {
        if (freq <= 0.0 || !freq.isFinite()) return null
        val midi = freqToMidi(freq)
        val nearest = midi.roundToInt()
        val cents = ((midi - nearest) * 100.0).roundToInt()
        return NoteInfo(midiToName(nearest), cents)
    }
}
