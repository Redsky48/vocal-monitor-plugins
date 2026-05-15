package com.vocalmonitor.plugin;

/**
 * Shared profile bus for the voice-instrument plugin family.
 *
 * The vocal-profile plugin writes captured LPC vectors here keyed by
 * MIDI note number; the voice-instrument plugin reads them back (with
 * linear interpolation between captured notes) to drive an LPC re-
 * synthesis filter at any pitch a MIDI keyboard or other source asks
 * for.
 *
 * The host loads this stub once on its own classloader so every plugin
 * sees the same static map.  On the desktop test app the
 * native-stub directory is shared automatically; on Android the host
 * just needs to expose this class through its plugin classloader (the
 * normal "shared interface" pattern used by PluginCanvas / PluginPaint
 * already covers it).
 */
public final class VoiceProfileBus {
    private VoiceProfileBus() {}

    /** LPC polynomial order used across the voice-instrument family. */
    public static final int LPC_ORDER = 24;
    public static final int MIDI_LO   = 36;     // C2
    public static final int MIDI_HI   = 96;     // C7
    public static final int N_NOTES   = MIDI_HI - MIDI_LO + 1;

    // Per-note entry layout: [a_1 .. a_LPC_ORDER, gain, sampleCount].
    // a_0 = 1 is implicit (monic LPC).  Length = LPC_ORDER + 2.
    private static final float[][] notes = new float[N_NOTES][];
    private static final Object LOCK = new Object();
    private static volatile int captureVersion = 0;

    /**
     * Add one analysis frame to the running average for the MIDI note
     * nearest to {@code pitchHz}.  Pitch outside the [MIDI_LO, MIDI_HI]
     * range is ignored.  The LPC array may be any length ≤ LPC_ORDER;
     * missing coefficients are treated as zero.
     */
    public static void addSample(float pitchHz, float[] lpc, float gain) {
        if (pitchHz <= 0f) return;
        int midi = (int) Math.round(69.0 + 12.0
                * (Math.log(pitchHz / 440.0) / Math.log(2.0)));
        if (midi < MIDI_LO || midi > MIDI_HI) return;
        int idx = midi - MIDI_LO;
        synchronized (LOCK) {
            float[] entry = notes[idx];
            if (entry == null) {
                entry = new float[LPC_ORDER + 2];
                notes[idx] = entry;
            }
            float n = entry[LPC_ORDER + 1];
            float w = 1f / (n + 1f);
            for (int k = 0; k < LPC_ORDER; k++) {
                float v = (k + 1) < lpc.length ? lpc[k + 1] : 0f;   // skip a_0
                entry[k] = (1f - w) * entry[k] + w * v;
            }
            entry[LPC_ORDER]     = (1f - w) * entry[LPC_ORDER] + w * gain;
            entry[LPC_ORDER + 1] = n + 1f;
            captureVersion++;
        }
    }

    /**
     * Fill {@code outLpc} with [a_1..a_LPC_ORDER, gain] interpolated
     * for the requested fractional MIDI value.  Returns false if the
     * profile is completely empty.  Out-of-range MIDI clamps to the
     * captured range.
     */
    public static boolean getInterpolated(float midi, float[] outLpc) {
        synchronized (LOCK) {
            float lo = (float) Math.floor(midi);
            int iLo = (int) lo;
            int iHi = iLo + 1;
            float t = midi - lo;
            float[] e1 = nearestEntry(iLo);
            float[] e2 = nearestEntry(iHi);
            if (e1 == null && e2 == null) return false;
            if (e1 == null) e1 = e2;
            if (e2 == null) e2 = e1;
            for (int k = 0; k <= LPC_ORDER; k++) {
                outLpc[k] = (1f - t) * e1[k] + t * e2[k];
            }
            return true;
        }
    }

    /** Walk outward from {@code midi} to find the closest captured note. */
    private static float[] nearestEntry(int midi) {
        int idx = midi - MIDI_LO;
        if (idx < 0) idx = 0;
        if (idx >= N_NOTES) idx = N_NOTES - 1;
        if (notes[idx] != null) return notes[idx];
        for (int d = 1; d < N_NOTES; d++) {
            int lo = idx - d, hi = idx + d;
            if (lo >= 0 && notes[lo] != null) return notes[lo];
            if (hi < N_NOTES && notes[hi] != null) return notes[hi];
        }
        return null;
    }

    public static void clear() {
        synchronized (LOCK) {
            java.util.Arrays.fill(notes, null);
            captureVersion++;
        }
    }

    /** Sample count for the given MIDI note (0 if uncaptured). */
    public static int getSampleCount(int midi) {
        if (midi < MIDI_LO || midi > MIDI_HI) return 0;
        synchronized (LOCK) {
            float[] e = notes[midi - MIDI_LO];
            return e == null ? 0 : (int) e[LPC_ORDER + 1];
        }
    }

    /** Total number of MIDI notes that have at least one sample. */
    public static int getCapturedNoteCount() {
        int c = 0;
        synchronized (LOCK) {
            for (float[] e : notes) if (e != null) c++;
        }
        return c;
    }

    /** Monotonically increasing version counter — consumers can poll
     *  this to invalidate their cached interpolated filter. */
    public static int getVersion() { return captureVersion; }
}
