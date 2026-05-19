package com.vocalmonitor.plugin.gamekit.audio;

/**
 * Pitch ↔ note-name conversion utilities.  Stateless — pure math
 * over the 12-tone equal-tempered scale with A4 = 440 Hz.
 *
 *   NoteName.of(440f)              → "A4"
 *   NoteName.of(261.63f)           → "C4"
 *   NoteName.midiOf(261.63f)       → 60.00
 *   NoteName.hzOf(60)              → 261.6256
 *   NoteName.cents(261.63f, 277.2f)→ -100  (≈ one semitone flat)
 *
 * Uses sharps only (C#, D#, …) — no flat aliases.  Sub-A0 / super-
 * C8 inputs round to the nearest in-range note.
 */
public final class NoteName {
    private NoteName() {}

    private static final String[] SHARPS = {
        "C","C#","D","D#","E","F","F#","G","G#","A","A#","B"
    };

    /** Convert Hz to a "C4" / "A#3"-style note name.  Returns "—"
     *  for non-positive Hz. */
    public static String of(float hz) {
        if (hz < 20f) return "—";
        int m = Math.round(midiOf(hz));
        return ofMidi(m);
    }

    /** Convert a MIDI note number (60 = C4) to a note name. */
    public static String ofMidi(int midi) {
        int octave = (midi / 12) - 1;
        int idx = ((midi % 12) + 12) % 12;
        return SHARPS[idx] + octave;
    }

    /** Hz → fractional MIDI number (60 = C4, 69 = A4 = 440 Hz). */
    public static float midiOf(float hz) {
        return (float) (69.0 + 12.0 * Math.log(hz / 440.0) / Math.log(2.0));
    }

    /** MIDI number → Hz.  Accepts int or float. */
    public static float hzOf(int midi)   { return hzOf((float) midi); }
    public static float hzOf(float midi) {
        return (float) (440.0 * Math.pow(2.0, (midi - 69.0) / 12.0));
    }

    /** Cents from `actualHz` to `targetHz`.  Positive = sharp, negative
     *  = flat.  Returns 0 if either input is non-positive. */
    public static float cents(float actualHz, float targetHz) {
        if (actualHz <= 0f || targetHz <= 0f) return 0f;
        return (float) (1200.0 * Math.log(actualHz / targetHz) / Math.log(2.0));
    }

    /**
     * "Snap" a pitch to the nearest semitone.  Returns the in-tune Hz
     * of that semitone — useful for auto-tune-style display: pick the
     * nearest note for the user, render that note as the target, then
     * show their cent-error to it.
     */
    public static float snapToSemitone(float hz) {
        if (hz < 20f) return 0f;
        int m = Math.round(midiOf(hz));
        return hzOf(m);
    }
}
