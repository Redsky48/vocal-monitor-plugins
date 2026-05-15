package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginHost;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * MIDI Keyboard — Phase 2 of the voice-instrument pipeline.
 *
 * On-canvas piano (C2..C7, 61 keys) that generates a glottal-pulse
 * excitation source at the played pitch.  Output is the raw excitation
 * waveform — pipe it into the voice-instrument plugin to colour it
 * with a captured vocal-profile and you have a playable vocal synth.
 *
 * Source model: bandlimited sawtooth + 1-pole low-pass shelf at 500 Hz
 * to mimic the natural ~−12 dB/oct tilt of a real glottal pulse.
 * Cleaner than a raw saw, lighter than a true LF / Rosenberg model.
 *
 * Up to {@link #MAX_VOICES} simultaneous notes (touch is monophonic on
 * Android / desktop today; extra voices come into play when the
 * "note" parameter is driven by an external sequencer or a future
 * MIDI bus).  AR envelope keeps note releases from clicking.
 */
public final class MidiKeyboard
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;
    private PluginHost host;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        attackCoef  = 1f - (float) Math.exp(-1.0 / (sr * 0.005));   // 5 ms
        releaseCoef = 1f - (float) Math.exp(-1.0 / (sr * 0.080));   // 80 ms
        portaCoef   = 1f - (float) Math.exp(-1.0 / (sr * 0.030));   // 30 ms
        glottalCoef = 1f - (float) Math.exp(-2.0 * Math.PI * 500.0 / sr);
        for (int i = 0; i < MAX_VOICES; i++) {
            voices[i] = new Voice();
        }
        glottalState = 0f;
        touchMidi = -1;
        currentNoteParam = 0f;
        currentVelParam  = 0.8f;
    }

    @Override public void setHost(PluginHost h) { this.host = h; }

    @Override public String[] parameterNames() {
        return new String[] { "note", "velocity", "tone", "level" };
    }
    @Override public float parameterMin(String n) {
        if (n.equals("note")) return 0f;
        return 0f;
    }
    @Override public float parameterMax(String n) {
        if (n.equals("note")) return 127f;
        return 1f;
    }
    @Override public float parameterDefault(String n) {
        if (n.equals("note"))     return 0f;
        if (n.equals("velocity")) return 0.8f;
        if (n.equals("tone"))     return 0.5f;
        if (n.equals("level"))    return 0.8f;
        return 0f;
    }
    @Override public String parameterLabel(String n) {
        if (n.equals("note"))     return "MIDI 0..127 (0 = off)";
        if (n.equals("velocity")) return "0..1";
        if (n.equals("tone"))     return "dark - bright";
        if (n.equals("level"))    return "output";
        return n;
    }
    @Override public void setParameter(String n, float v) {
        if (n.equals("note")) {
            int midi = Math.round(v);
            if (midi != Math.round(currentNoteParam)) {
                if (paramVoiceMidi >= 0) noteOff(paramVoiceMidi);
                if (midi > 0) {
                    noteOn(midi, currentVelParam);
                    paramVoiceMidi = midi;
                } else {
                    paramVoiceMidi = -1;
                }
            }
            currentNoteParam = v;
        }
        else if (n.equals("velocity")) currentVelParam = v;
        else if (n.equals("tone"))     toneParam       = v;
        else if (n.equals("level"))    levelParam      = v;
    }

    private float currentNoteParam = 0f, currentVelParam = 0.8f;
    private float toneParam = 0.5f, levelParam = 0.8f;
    private int   paramVoiceMidi = -1;

    // ── Voice model ──
    private static final int MAX_VOICES = 8;
    private static final class Voice {
        boolean active;
        boolean gate;
        int midi;
        float phase;
        float freq;       // current (with portamento)
        float targetFreq;
        float env;
        float vel;
        long  startedAt;  // for stealing
    }
    private final Voice[] voices = new Voice[MAX_VOICES];
    private long voiceCounter = 0;
    private float attackCoef, releaseCoef, portaCoef, glottalCoef;
    private float glottalState;     // 1-pole LP filter state for output tilt

    private static float midiToHz(int m) {
        return 440f * (float) Math.pow(2.0, (m - 69) / 12.0);
    }

    private void noteOn(int midi, float vel) {
        // Steal: prefer inactive, then the oldest gate==false, then oldest overall.
        int best = -1;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < MAX_VOICES; i++) {
            if (!voices[i].active) { best = i; break; }
        }
        if (best < 0) {
            for (int i = 0; i < MAX_VOICES; i++) {
                if (!voices[i].gate && voices[i].startedAt < oldest) {
                    oldest = voices[i].startedAt; best = i;
                }
            }
        }
        if (best < 0) {
            oldest = Long.MAX_VALUE;
            for (int i = 0; i < MAX_VOICES; i++) {
                if (voices[i].startedAt < oldest) {
                    oldest = voices[i].startedAt; best = i;
                }
            }
        }
        Voice v = voices[best];
        v.active = true;
        v.gate = true;
        v.midi = midi;
        v.targetFreq = midiToHz(midi);
        if (v.freq <= 0f) v.freq = v.targetFreq;     // first time: no glide
        v.vel = vel;
        v.startedAt = ++voiceCounter;
    }

    private void noteOff(int midi) {
        for (int i = 0; i < MAX_VOICES; i++) {
            if (voices[i].active && voices[i].gate && voices[i].midi == midi) {
                voices[i].gate = false;
            }
        }
    }

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float mix = 0f;
            int activeCount = 0;
            for (int vi = 0; vi < MAX_VOICES; vi++) {
                Voice v = voices[vi];
                if (!v.active) continue;
                // Portamento.
                v.freq += portaCoef * (v.targetFreq - v.freq);
                // AR envelope.
                float coef = v.gate ? attackCoef : releaseCoef;
                float target = v.gate ? v.vel : 0f;
                v.env += coef * (target - v.env);
                if (!v.gate && v.env < 1e-4f) { v.active = false; v.freq = 0f; continue; }
                // Sawtooth (naive — bandlimited not strictly needed at
                // sub-Nyquist musical pitches; the glottal LP at 500 Hz
                // hammers any aliasing well below audibility).
                v.phase += v.freq / sampleRate;
                if (v.phase >= 1f) v.phase -= 1f;
                float saw = 2f * v.phase - 1f;
                mix += saw * v.env;
                activeCount++;
            }
            // Glottal-pulse tilt: 1-pole LP, cutoff modulated by "tone".
            float lpC = glottalCoef * (0.3f + 1.5f * toneParam);
            if (lpC > 0.999f) lpC = 0.999f;
            glottalState += lpC * (mix - glottalState);
            // Mix tilt vs raw a little — at tone=1 we keep more high end.
            float tilt = glottalState + 0.25f * toneParam * (mix - glottalState);
            float outSample = tilt * levelParam;
            if (activeCount > 1) outSample *= 1f / (float) Math.sqrt(activeCount);
            output[i] = outSample;
        }
    }

    // ── Touch handling ──
    private int touchMidi = -1;
    private float kbX0 = 0f, kbX1 = 0f, kbY0 = 0f, kbY1 = 0f;
    private static final int MIDI_LO = 36, MIDI_HI = 96;

    private int midiAt(float x, float y) {
        if (y < kbY0 || y > kbY1 || x < kbX0 || x > kbX1) return -1;
        // First test black keys (they're drawn on top).
        int totalWhite = 0;
        for (int m = MIDI_LO; m <= MIDI_HI; m++) if (!isBlack(m)) totalWhite++;
        float whiteW = (kbX1 - kbX0) / (float) totalWhite;
        float blackBottom = kbY0 + (kbY1 - kbY0) * 0.62f;
        if (y <= blackBottom) {
            int whiteIdx = 0;
            for (int m = MIDI_LO; m <= MIDI_HI; m++) {
                if (isBlack(m)) {
                    float wx0 = kbX0 + (whiteIdx - 1) * whiteW;
                    float bx0 = wx0 + whiteW * 0.65f;
                    float bx1 = wx0 + whiteW * 1.35f;
                    if (x >= bx0 && x <= bx1) return m;
                } else {
                    whiteIdx++;
                }
            }
        }
        // Then white keys.
        int whiteIdx = 0;
        for (int m = MIDI_LO; m <= MIDI_HI; m++) {
            if (isBlack(m)) continue;
            float x0 = kbX0 + whiteIdx * whiteW;
            float x1 = x0 + whiteW;
            if (x >= x0 && x <= x1) return m;
            whiteIdx++;
        }
        return -1;
    }
    private static boolean isBlack(int midi) {
        int pc = ((midi % 12) + 12) % 12;
        return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
    }

    @Override public void onTouchDown(float x, float y) {
        int m = midiAt(x, y);
        if (m < 0) return;
        if (touchMidi >= 0) noteOff(touchMidi);
        noteOn(m, currentVelParam);
        touchMidi = m;
    }
    @Override public void onTouchMove(float x, float y) {
        int m = midiAt(x, y);
        if (m < 0 || m == touchMidi) return;
        noteOff(touchMidi);
        noteOn(m, currentVelParam);
        touchMidi = m;
    }
    @Override public void onTouchUp(float x, float y) {
        if (touchMidi >= 0) {
            noteOff(touchMidi);
            touchMidi = -1;
        }
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_KEY_WHITE   = 0xFFE6E6EA;
    private static final int COLOR_KEY_BLACK   = 0xFF20222A;
    private static final int COLOR_KEY_ACTIVE  = 0xFFF5C842;

    private PluginPaint bgPaint, cardPaint, textBright, textDim, keyW, keyB, keyActive;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        if (width < 60 || height < 60) return;
        float W = width, H = height;
        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("MIDI KEYBOARD", 12f, 16f, textBright);
        int playing = 0;
        for (Voice v : voices) if (v != null && v.active) playing++;
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText(String.format("glottal-pulse synth  -  %d voice%s",
                playing, playing == 1 ? "" : "s"), W - 12f, 16f, textDim);

        float pad = 12f, headerH = 22f;
        kbX0 = pad;
        kbY0 = pad + headerH;
        kbX1 = W - pad;
        kbY1 = H - pad - 14f;
        if (kbY1 - kbY0 < 60f) kbY1 = kbY0 + 60f;

        int totalWhite = 0;
        for (int m = MIDI_LO; m <= MIDI_HI; m++) if (!isBlack(m)) totalWhite++;
        float whiteW = (kbX1 - kbX0) / (float) totalWhite;
        int whiteIdx = 0;
        // White keys.
        for (int m = MIDI_LO; m <= MIDI_HI; m++) {
            if (isBlack(m)) continue;
            float x0 = kbX0 + whiteIdx * whiteW;
            float x1 = x0 + whiteW - 1f;
            boolean active = isPlaying(m);
            keyW.setColor(active ? COLOR_KEY_ACTIVE : COLOR_KEY_WHITE).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, kbY0, x1, kbY1, 3f, keyW);
            keyW.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawRoundRect(x0, kbY0, x1, kbY1, 3f, keyW);
            if ((m % 12) == 0) {
                int oct = (m / 12) - 1;
                textDim.setColor(COLOR_TEXT_DIM).setTextSize(8f).setTextAlign(1);
                canvas.drawText("C" + oct, (x0 + x1) * 0.5f, kbY1 - 3f, textDim);
            }
            whiteIdx++;
        }
        // Black keys overlay.
        whiteIdx = 0;
        for (int m = MIDI_LO; m <= MIDI_HI; m++) {
            if (isBlack(m)) {
                float wx0 = kbX0 + (whiteIdx - 1) * whiteW;
                float bx0 = wx0 + whiteW * 0.65f;
                float bx1 = wx0 + whiteW * 1.35f;
                float by0 = kbY0;
                float by1 = kbY0 + (kbY1 - kbY0) * 0.62f;
                boolean active = isPlaying(m);
                keyB.setColor(active ? COLOR_KEY_ACTIVE : COLOR_KEY_BLACK).setStyle(PluginStyle.FILL);
                canvas.drawRoundRect(bx0, by0, bx1, by1, 2f, keyB);
            } else {
                whiteIdx++;
            }
        }
    }

    private boolean isPlaying(int midi) {
        for (Voice v : voices) {
            if (v != null && v.active && v.gate && v.midi == midi) return true;
        }
        return false;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        keyW       = c.newPaint();
        keyB       = c.newPaint();
        keyActive  = c.newPaint();
    }
}
