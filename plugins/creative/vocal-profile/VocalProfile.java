package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;
import com.vocalmonitor.plugin.VoiceProfileBus;

import java.util.Map;

/**
 * Vocal Profile (Capture) — Phase 1 of the voice-instrument pipeline.
 *
 * Listens to the live vocal signal, runs YIN for pitch and order-24
 * autocorrelation LPC for the spectral envelope, and dumps each voiced
 * frame into the shared {@link VoiceProfileBus} keyed by the nearest
 * MIDI note.  Per-note entries are running-averaged so the profile
 * smooths out across multiple captured frames at the same pitch.
 *
 * The display shows a 61-key piano (C2..C7) where the brightness of
 * each key encodes how many frames have been captured for that note,
 * plus the live LPC magnitude spectrum so the user can see what's
 * being learned.
 *
 * Parameters:
 *   record  : 1 = actively capturing, 0 = idle (pass-through only)
 *   clear   : raise > 0.5 to wipe the entire profile bus (single-shot)
 */
public final class VocalProfile
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(lpcMagDb, -90f);
        ringW = 0;
        recordOn = false;
        clearPending = false;
        lastCapturedMidi = -1;
        liveLpcReady = false;
    }

    @Override public String[] parameterNames() { return new String[] { "record", "clear" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) {
        if (n.equals("record")) return 1f;
        return 0f;
    }
    @Override public String parameterLabel(String n) {
        if (n.equals("record")) return "off / on";
        if (n.equals("clear"))  return "wipe profile";
        return n;
    }
    @Override public void setParameter(String n, float v) {
        if (n.equals("record")) recordOn = v > 0.5f;
        else if (n.equals("clear")) {
            boolean now = v > 0.5f;
            if (now && !clearPending) VoiceProfileBus.clear();
            clearPending = now;
        }
    }

    private boolean recordOn = true;
    private boolean clearPending = false;
    private int lastCapturedMidi = -1;

    // ── DSP ──
    private static final int FRAME_SIZE = 1024;
    private static final int HOP        = 512;
    private static final int LPC_ORDER  = VoiceProfileBus.LPC_ORDER;     // 24
    private static final int LAG_MIN    = 32;
    private static final int LAG_MAX    = 512;
    private static final float YIN_THRESHOLD = 0.15f;
    private final float[] audioRing = new float[FRAME_SIZE];
    private final float[] frame     = new float[FRAME_SIZE];
    private final float[] yinBuf    = new float[FRAME_SIZE];
    private final float[] yinDiff   = new float[LAG_MAX + 1];
    private final float[] yinCMND   = new float[LAG_MAX + 1];
    private final float[] R         = new float[LPC_ORDER + 1];
    private final float[] lpc       = new float[LPC_ORDER + 1];       // a_0..a_LPC_ORDER
    private final float[] lpcA      = new float[LPC_ORDER + 1];
    private final float[] lpcAPrev  = new float[LPC_ORDER + 1];
    private int ringW = 0;

    // Live LPC spectrum buffer (for visual).
    private static final int SPEC_BINS = 192;
    private final float[] lpcMagDb = new float[SPEC_BINS];
    private boolean liveLpcReady = false;
    private float currentFreq = 0f;
    private float currentGain = -90f;
    private int   currentMidi = -1;

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % FRAME_SIZE;
        }
    }

    private void prepareWindow(java.util.Map<String, float[]> streams) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 64) return;
        int n = wave.length;
        int start = n - FRAME_SIZE;
        if (start < 0) {
            int pad = -start;
            for (int i = 0; i < pad; i++) audioRing[i] = 0f;
            for (int i = 0; i < n; i++) audioRing[pad + i] = wave[i];
        } else {
            for (int i = 0; i < FRAME_SIZE; i++) audioRing[i] = wave[start + i];
        }
        ringW = 0;
    }

    private void analyseFrame() {
        // Pre-emphasis + Hann window for LPC fitting.
        float prev = 0f;
        double energy = 0;
        for (int i = 0; i < FRAME_SIZE; i++) {
            int idx = (ringW + i) % FRAME_SIZE;
            float v = audioRing[idx] - 0.97f * prev;
            prev = audioRing[idx];
            float w = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME_SIZE - 1)));
            frame[i] = v * w;
            yinBuf[i] = audioRing[idx];
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / FRAME_SIZE);
        currentGain = 20f * (float) Math.log10(Math.max(1e-9f, rms));
        if (rms < 0.003f) { currentFreq = 0f; currentMidi = -1; return; }

        // YIN for pitch.
        int half = FRAME_SIZE / 2;
        int maxLag = Math.min(half, LAG_MAX);
        for (int tau = 1; tau <= maxLag; tau++) {
            float sum = 0f;
            for (int j = 0; j < half; j++) {
                float d = yinBuf[j] - yinBuf[j + tau];
                sum += d * d;
            }
            yinDiff[tau] = sum;
        }
        yinCMND[0] = 1f;
        float running = 0f;
        for (int tau = 1; tau <= maxLag; tau++) {
            running += yinDiff[tau];
            yinCMND[tau] = running > 1e-12f ? yinDiff[tau] * tau / running : 1f;
        }
        int chosen = -1;
        for (int tau = LAG_MIN; tau < maxLag - 1; tau++) {
            if (yinCMND[tau] < YIN_THRESHOLD) {
                while (tau + 1 < maxLag && yinCMND[tau + 1] < yinCMND[tau]) tau++;
                chosen = tau;
                break;
            }
        }
        if (chosen < 0) { currentFreq = 0f; currentMidi = -1; return; }
        float refined = chosen;
        if (chosen > 0 && chosen < maxLag) {
            float y1 = yinCMND[chosen - 1], y2 = yinCMND[chosen], y3 = yinCMND[chosen + 1];
            float denom = 2f * (2f * y2 - y1 - y3);
            if (Math.abs(denom) > 1e-9f) {
                float adj = (y3 - y1) / denom;
                if (adj > -1f && adj < 1f) refined += adj;
            }
        }
        currentFreq = sampleRate / refined;
        if (currentFreq < 50f || currentFreq > 1500f) {
            currentMidi = -1;
            return;
        }
        currentMidi = (int) Math.round(69.0 + 12.0
                * (Math.log(currentFreq / 440.0) / Math.log(2.0)));

        // Autocorrelation LPC (order 24) on pre-emphasised frame.
        for (int k = 0; k <= LPC_ORDER; k++) {
            float sum = 0f;
            for (int i = k; i < FRAME_SIZE; i++) sum += frame[i] * frame[i - k];
            R[k] = sum;
        }
        if (R[0] < 1e-9f) return;
        // Levinson-Durbin.
        float Eerr = R[0];
        lpcA[0] = 1f;
        for (int p = 1; p <= LPC_ORDER; p++) {
            float k = -R[p];
            for (int j = 1; j < p; j++) k -= lpcA[j] * R[p - j];
            k /= Eerr;
            if (k > 0.99f) k = 0.99f; if (k < -0.99f) k = -0.99f;
            System.arraycopy(lpcA, 0, lpcAPrev, 0, p);
            lpcA[p] = k;
            for (int j = 1; j < p; j++) lpcA[j] = lpcAPrev[j] + k * lpcAPrev[p - j];
            Eerr *= 1f - k * k;
            if (Eerr < 1e-9f) Eerr = 1e-9f;
        }
        System.arraycopy(lpcA, 0, lpc, 0, LPC_ORDER + 1);

        // Sample |1 / A(e^jω)| for the live display (0..sampleRate/2).
        float maxHz = sampleRate * 0.45f;
        for (int b = 0; b < SPEC_BINS; b++) {
            float freq = (b + 1) * maxHz / SPEC_BINS;
            double w = 2.0 * Math.PI * freq / sampleRate;
            double re = 0, im = 0;
            for (int k = 0; k <= LPC_ORDER; k++) {
                re += lpc[k] * Math.cos(-w * k);
                im += lpc[k] * Math.sin(-w * k);
            }
            double mag2 = re * re + im * im;
            float v = mag2 > 1e-12 ? (float)(1.0 / Math.sqrt(mag2)) : 0f;
            lpcMagDb[b] = 20f * (float) Math.log10(Math.max(1e-9f, v));
        }
        liveLpcReady = true;

        // Capture into the shared bus if recording is on.
        if (recordOn) {
            VoiceProfileBus.addSample(currentFreq, lpc, rms);
            lastCapturedMidi = currentMidi;
        }
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_KEY_WHITE   = 0xFF20222A;
    private static final int COLOR_KEY_BLACK   = 0xFF0A0B0E;
    private static final int COLOR_KEY_ACCENT  = 0xFF6FE07A;     // captured
    private static final int COLOR_KEY_LIVE    = 0xFFF5C842;     // currently being captured
    private static final int COLOR_SPEC        = 0xFF6DD3E0;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            keyFill, keyBorder, livePaint, specLine;
    private PluginPath specPath;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        if (width < 60 || height < 60) return;
        prepareWindow(streams);
        analyseFrame();

        float W = width, H = height;
        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("VOCAL PROFILE", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText(
                String.format("LPC %d  -  %d / %d notes  -  %s",
                        LPC_ORDER, VoiceProfileBus.getCapturedNoteCount(),
                        VoiceProfileBus.N_NOTES, recordOn ? "RECORDING" : "PAUSED"),
                W - 12f, 16f, textDim);

        float pad = 12f, headerH = 22f;
        // ── Piano keyboard (top half) ──
        float kbY0 = pad + headerH;
        float kbY1 = pad + headerH + H * 0.40f;
        if (kbY1 - kbY0 < 40f) kbY1 = kbY0 + 40f;
        float kbW = W - pad * 2;
        // Build list of white-key MIDI numbers (and remember which are
        // black for overlay).  Range = C2..C7 = MIDI 36..96.
        int totalWhite = 0;
        for (int m = VoiceProfileBus.MIDI_LO; m <= VoiceProfileBus.MIDI_HI; m++) {
            if (!isBlackKey(m)) totalWhite++;
        }
        float whiteW = kbW / (float) totalWhite;
        int whiteIdx = 0;
        // First pass — draw white keys.
        for (int m = VoiceProfileBus.MIDI_LO; m <= VoiceProfileBus.MIDI_HI; m++) {
            if (isBlackKey(m)) continue;
            float x0 = pad + whiteIdx * whiteW;
            float x1 = x0 + whiteW - 1f;
            int samples = VoiceProfileBus.getSampleCount(m);
            int col = (samples == 0) ? COLOR_KEY_WHITE
                    : tintByCount(COLOR_KEY_ACCENT, samples);
            if (m == currentMidi && recordOn) col = COLOR_KEY_LIVE;
            keyFill.setColor(col).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, kbY0, x1, kbY1, 3f, keyFill);
            keyBorder.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawRoundRect(x0, kbY0, x1, kbY1, 3f, keyBorder);
            // C label every octave.
            if ((m % 12) == 0) {
                int oct = (m / 12) - 1;
                textDim.setColor(COLOR_TEXT_DIM).setTextSize(8f).setTextAlign(1);
                canvas.drawText("C" + oct, (x0 + x1) * 0.5f, kbY1 - 3f, textDim);
            }
            whiteIdx++;
        }
        // Second pass — overlay black keys on top.
        whiteIdx = 0;
        for (int m = VoiceProfileBus.MIDI_LO; m <= VoiceProfileBus.MIDI_HI; m++) {
            if (isBlackKey(m)) {
                // Black key sits between the previous white and the next.
                float xWhite0 = pad + (whiteIdx - 1) * whiteW;
                float bx0 = xWhite0 + whiteW * 0.65f;
                float bx1 = xWhite0 + whiteW * 1.35f;
                float by0 = kbY0;
                float by1 = kbY0 + (kbY1 - kbY0) * 0.62f;
                int samples = VoiceProfileBus.getSampleCount(m);
                int col = (samples == 0) ? COLOR_KEY_BLACK
                        : tintByCount(COLOR_KEY_ACCENT, samples);
                if (m == currentMidi && recordOn) col = COLOR_KEY_LIVE;
                keyFill.setColor(col).setStyle(PluginStyle.FILL);
                canvas.drawRoundRect(bx0, by0, bx1, by1, 2f, keyFill);
            } else {
                whiteIdx++;
            }
        }

        // ── Live LPC spectrum (bottom half) ──
        float spY0 = kbY1 + 10f;
        float spY1 = H - pad - 14f;
        if (spY1 - spY0 > 20f && liveLpcReady) {
            cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(pad, spY0, W - pad, spY1, 6f, cardPaint);
            cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(pad, spY0, W - pad, spY1, 6f, cardPaint);
            float plotW = W - pad * 2;
            float plotH = spY1 - spY0;
            // dB range: peak − 50 dB to peak.
            float dbMax = -120f;
            for (int b = 0; b < SPEC_BINS; b++) if (lpcMagDb[b] > dbMax) dbMax = lpcMagDb[b];
            float dbMin = dbMax - 50f;
            specPath.reset();
            boolean started = false;
            for (int b = 0; b < SPEC_BINS; b++) {
                float px = pad + b * (plotW / (float)(SPEC_BINS - 1));
                float d = lpcMagDb[b];
                if (d < dbMin) d = dbMin; if (d > dbMax) d = dbMax;
                float py = spY1 - (d - dbMin) / (dbMax - dbMin) * plotH;
                if (!started) { specPath.moveTo(px, py); started = true; }
                else specPath.lineTo(px, py);
            }
            specLine.setColor(COLOR_SPEC).setStyle(PluginStyle.STROKE).setStrokeWidth(1.4f);
            canvas.drawPath(specPath, specLine);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
            canvas.drawText("live LPC envelope", pad + 6f, spY0 + 11f, textDim);
            String pitchLabel = currentFreq > 0f
                    ? String.format("%.0f Hz  -  MIDI %d", currentFreq, currentMidi)
                    : "unvoiced";
            textDim.setColor(COLOR_TEXT_DIM).setTextAlign(2);
            canvas.drawText(pitchLabel, W - pad - 6f, spY0 + 11f, textDim);
        }
    }

    private static boolean isBlackKey(int midi) {
        int pc = ((midi % 12) + 12) % 12;
        return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
    }

    /** Brighten the accent colour by sample count (1 → 50%, 20+ → full). */
    private static int tintByCount(int base, int samples) {
        float t = Math.min(1f, samples / 20f);
        int alpha = (int)(80 + 175 * t);
        return (alpha << 24) | (base & 0x00FFFFFF);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        keyFill    = c.newPaint();
        keyBorder  = c.newPaint();
        livePaint  = c.newPaint();
        specLine   = c.newPaint();
        specPath   = c.newPath();
    }
}
