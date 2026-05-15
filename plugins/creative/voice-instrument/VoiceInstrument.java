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
 * Voice Instrument — Phase 3 of the voice-instrument pipeline.
 *
 * Audio in → pitch detect → look up the matching LPC coefficients from
 * {@link VoiceProfileBus} (linearly interpolated between the two
 * nearest captured MIDI notes) → run input through the all-pole
 * synthesis filter 1 / A(z) → audio out shaped like the captured
 * vocal-tract envelope at that pitch.
 *
 * Typical chain:
 *
 *     midi-keyboard ──► voice-instrument ──► output
 *                          (uses profile captured by vocal-profile)
 *
 * The LPC filter coefficients are smoothed over ~30 ms between pitch
 * lookups so glissandos / portamento don't pop, and the cached
 * coefficient set is invalidated whenever the profile bus version
 * counter ticks (i.e. a new capture frame landed).
 *
 * Parameters:
 *   mix       : 0 = dry (input) ..  1 = wet (vocal-tract filtered)
 *   drive     : input gain into the LPC filter (pre)
 *   level     : output gain (post)
 *   bypass    : 1 = pass-through (debug)
 */
public final class VoiceInstrument
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(filterState, 0f);
        java.util.Arrays.fill(lpcCoefs, 0f);
        java.util.Arrays.fill(lpcTarget, 0f);
        ringW = 0;
        lookupCoef = 1f - (float) Math.exp(-1.0 / (sr * 0.030));   // 30 ms
        liveMidi = -1f;
        liveFreq = 0f;
        profileVersion = -1;
        haveProfile = false;
    }

    @Override public String[] parameterNames() {
        return new String[] { "mix", "drive", "level", "bypass" };
    }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) {
        if (n.equals("mix"))    return 1f;
        if (n.equals("drive"))  return 0.5f;
        if (n.equals("level"))  return 0.7f;
        if (n.equals("bypass")) return 0f;
        return 0f;
    }
    @Override public String parameterLabel(String n) {
        if (n.equals("mix"))    return "dry - wet";
        if (n.equals("drive"))  return "input drive";
        if (n.equals("level"))  return "output";
        if (n.equals("bypass")) return "0/1";
        return n;
    }
    @Override public void setParameter(String n, float v) {
        if (n.equals("mix"))    mixParam    = v;
        else if (n.equals("drive"))  driveParam  = 0.2f + v * 4f;
        else if (n.equals("level"))  levelParam  = v;
        else if (n.equals("bypass")) bypassParam = v > 0.5f;
    }
    private float mixParam = 1f, driveParam = 1f, levelParam = 0.7f;
    private boolean bypassParam = false;

    // ── DSP ──
    private static final int FRAME_SIZE = 1024;
    private static final int LPC_ORDER  = VoiceProfileBus.LPC_ORDER;
    private static final int LAG_MIN    = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.18f;
    private final float[] audioRing   = new float[FRAME_SIZE];
    private final float[] yinBuf      = new float[FRAME_SIZE];
    private final float[] yinDiff     = new float[LAG_MAX + 1];
    private final float[] yinCMND     = new float[LAG_MAX + 1];
    // LPC coefficient sets: lpcCoefs is the *currently smoothed* set
    // applied to the synthesis filter; lpcTarget is the freshest set
    // pulled from the bus; we lerp between them at lookupCoef rate so
    // pitch changes don't pop.  Layout: [a_1..a_LPC_ORDER, gain].
    private final float[] lpcCoefs    = new float[LPC_ORDER + 1];
    private final float[] lpcTarget   = new float[LPC_ORDER + 1];
    private final float[] filterState = new float[LPC_ORDER];
    private int ringW = 0;
    private float lookupCoef;
    private int   profileVersion;
    private boolean haveProfile;
    private float liveMidi = -1f;
    private float liveFreq = 0f;

    // Pitch-detection cadence — we don't need to re-run YIN every
    // sample, every 256-sample hop is plenty.
    private int sampleAcc = 0;
    private static final int PITCH_HOP = 256;

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float x = input[i];
            audioRing[ringW] = x;
            ringW = (ringW + 1) % FRAME_SIZE;
            sampleAcc++;
            if (sampleAcc >= PITCH_HOP) {
                sampleAcc = 0;
                detectPitchAndFetchProfile();
            }
            // Smooth coefficients toward target.
            for (int k = 0; k <= LPC_ORDER; k++) {
                lpcCoefs[k] += lookupCoef * (lpcTarget[k] - lpcCoefs[k]);
            }
            if (bypassParam || !haveProfile) {
                output[i] = x;
                continue;
            }
            // All-pole synthesis filter: y[n] = drive·x[n] − Σ a_k · y[n−k].
            // lpcCoefs[0..LPC_ORDER-1] holds a_1..a_LPC_ORDER (a_0 = 1
            // is implicit in the monic form), and filterState[k] is
            // y[n − (k+1)] from the previous output samples.
            float driven = x * driveParam;
            float y = driven;
            for (int k = 0; k < LPC_ORDER; k++) {
                y -= lpcCoefs[k] * filterState[k];
            }
            // Shift filter state.
            for (int k = LPC_ORDER - 1; k > 0; k--) {
                filterState[k] = filterState[k - 1];
            }
            filterState[0] = y;
            // Output: mix dry/wet + level.
            float wet = y * lpcCoefs[LPC_ORDER];        // multiplied by gain term
            float outSample = (mixParam * wet + (1f - mixParam) * x) * levelParam;
            // Soft clip just in case the LPC filter rings.
            if (outSample > 1.5f) outSample = 1.5f;
            if (outSample < -1.5f) outSample = -1.5f;
            output[i] = (float) Math.tanh(outSample);
        }
    }

    private void detectPitchAndFetchProfile() {
        double energy = 0;
        for (int i = 0; i < FRAME_SIZE; i++) {
            int idx = (ringW + i) % FRAME_SIZE;
            float v = audioRing[idx];
            yinBuf[i] = v;
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / FRAME_SIZE);
        if (rms < 0.001f) {
            // Silence — let coefficients fade toward zero (== no filtering).
            for (int k = 0; k <= LPC_ORDER; k++) lpcTarget[k] = 0f;
            liveFreq = 0f;
            return;
        }
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
        if (chosen < 0) return;
        float refined = chosen;
        if (chosen > 0 && chosen < maxLag) {
            float y1 = yinCMND[chosen - 1], y2 = yinCMND[chosen], y3 = yinCMND[chosen + 1];
            float denom = 2f * (2f * y2 - y1 - y3);
            if (Math.abs(denom) > 1e-9f) {
                float adj = (y3 - y1) / denom;
                if (adj > -1f && adj < 1f) refined += adj;
            }
        }
        liveFreq = sampleRate / refined;
        if (liveFreq < 50f || liveFreq > 1500f) return;
        liveMidi = (float)(69.0 + 12.0 * (Math.log(liveFreq / 440.0) / Math.log(2.0)));
        // Pull interpolated LPC + gain from the profile bus.
        if (VoiceProfileBus.getInterpolated(liveMidi, lpcTarget)) {
            haveProfile = true;
            profileVersion = VoiceProfileBus.getVersion();
        } else {
            // Empty profile — leave target zeros so wet == dry.
            for (int k = 0; k <= LPC_ORDER; k++) lpcTarget[k] = 0f;
            haveProfile = false;
        }
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SPEC        = 0xFFEE8A2C;
    private static final int COLOR_OK          = 0xFF6FE07A;
    private static final int COLOR_WARN        = 0xFFE0A040;
    private static final int COLOR_BAD         = 0xFFE0606A;

    private PluginPaint bgPaint, cardPaint, textBright, textDim, specLine;
    private PluginPath specPath;

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
        canvas.drawText("VOICE INSTRUMENT", 12f, 16f, textBright);

        int captured = VoiceProfileBus.getCapturedNoteCount();
        int statusCol = captured >= 20 ? COLOR_OK
                       : captured >= 5  ? COLOR_WARN
                       : COLOR_BAD;
        textDim.setColor(statusCol).setTextSize(9f).setTextAlign(2);
        canvas.drawText(
                String.format("profile: %d / %d notes  %s",
                        captured, VoiceProfileBus.N_NOTES,
                        captured < 5 ? "(capture first!)" : ""),
                W - 12f, 16f, textDim);

        float pad = 12f, headerH = 22f;
        // ── Big readout: input pitch + the matching LPC envelope ──
        float cardX0 = pad;
        float cardY0 = pad + headerH;
        float cardX1 = W * 0.32f;
        float cardY1 = H * 0.55f;
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(cardX0, cardY0, cardX1, cardY1, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(cardX0, cardY0, cardX1, cardY1, 8f, cardPaint);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("input pitch", cardX0 + 10f, cardY0 + 14f, textDim);
        if (liveFreq > 0f) {
            textBright.setColor(COLOR_SPEC).setTextSize(24f).setTextAlign(1);
            canvas.drawText(String.format("%.0f Hz", liveFreq),
                    (cardX0 + cardX1) * 0.5f, cardY0 + 38f, textBright);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(10f).setTextAlign(1);
            canvas.drawText(String.format("MIDI %.1f", liveMidi),
                    (cardX0 + cardX1) * 0.5f, cardY0 + 56f, textDim);
            textDim.setColor(haveProfile ? COLOR_OK : COLOR_WARN)
                    .setTextSize(9f).setTextAlign(1);
            canvas.drawText(haveProfile ? "PROFILE MATCHED" : "NO PROFILE - PASS-THROUGH",
                    (cardX0 + cardX1) * 0.5f, cardY1 - 8f, textDim);
        } else {
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(14f).setTextAlign(1);
            canvas.drawText("no signal", (cardX0 + cardX1) * 0.5f, cardY0 + 38f, textDim);
        }

        // ── LPC envelope plot ──
        float plX0 = cardX1 + 10f;
        float plX1 = W - pad;
        float plY0 = cardY0;
        float plY1 = cardY1;
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plX0, plY0, plX1, plY1, 6f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plX0, plY0, plX1, plY1, 6f, cardPaint);
        if (haveProfile) {
            // Compute |1/A(e^jω)| on 192 points 0..5.5 kHz from the
            // currently active (smoothed) coefficients.
            int bins = 192;
            float maxHz = 5500f;
            float[] mag = new float[bins];
            float dbMax = -120f;
            for (int b = 0; b < bins; b++) {
                float freq = (b + 1) * maxHz / bins;
                double w = 2.0 * Math.PI * freq / sampleRate;
                double re = 1.0, im = 0.0;     // a_0 = 1
                for (int k = 0; k < LPC_ORDER; k++) {
                    double ang = -w * (k + 1);
                    re += lpcCoefs[k] * Math.cos(ang);
                    im += lpcCoefs[k] * Math.sin(ang);
                }
                double mag2 = re * re + im * im;
                float v = mag2 > 1e-12 ? (float)(1.0 / Math.sqrt(mag2)) : 0f;
                float d = 20f * (float) Math.log10(Math.max(1e-9f, v));
                mag[b] = d;
                if (d > dbMax) dbMax = d;
            }
            float dbMin = dbMax - 50f;
            specPath.reset();
            boolean started = false;
            float pw = plX1 - plX0, ph = plY1 - plY0;
            for (int b = 0; b < bins; b++) {
                float px = plX0 + b * (pw / (float)(bins - 1));
                float d = mag[b];
                if (d < dbMin) d = dbMin; if (d > dbMax) d = dbMax;
                float py = plY1 - (d - dbMin) / (dbMax - dbMin) * ph;
                if (!started) { specPath.moveTo(px, py); started = true; }
                else specPath.lineTo(px, py);
            }
            specLine.setColor(COLOR_SPEC).setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f);
            canvas.drawPath(specPath, specLine);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
            canvas.drawText("active LPC envelope", plX0 + 6f, plY0 + 11f, textDim);
        } else {
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(10f).setTextAlign(1);
            canvas.drawText("capture a profile with vocal-profile first",
                    (plX0 + plX1) * 0.5f, (plY0 + plY1) * 0.5f, textDim);
        }

        // ── Footer: chain reminder ──
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(1);
        canvas.drawText("chain:  midi-keyboard  →  voice-instrument  →  output",
                W * 0.5f, H - 8f, textDim);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        specLine   = c.newPaint();
        specPath   = c.newPath();
    }
}
