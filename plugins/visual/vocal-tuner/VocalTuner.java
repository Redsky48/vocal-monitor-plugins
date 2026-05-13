package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vocal Tuner — pass-through audio + live pitch readout. Detects the
 * fundamental with the YIN cumulative-mean-normalized difference
 * function (de Cheveigné & Kawahara 2002), the same algorithm Auto-Tune
 * uses internally; reports the nearest semitone, the octave, and the
 * cents offset on a horizontal needle. ±5 cents lights green (in tune);
 * progressively yellow / red as the offset grows. Pass-through audio,
 * so dropping it anywhere in a chain is safe.
 */
public final class VocalTuner
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private static final int WINDOW = 2048;
    private static final int YIN_MAX_TAU = 1024;
    private static final int YIN_MIN_TAU = 32;
    private static final float YIN_THRESHOLD = 0.15f;
    private static final float A4 = 440f;
    private static final String[] NOTE_NAMES = {
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    };

    private int sampleRate = 44100;
    private float detectedFreq = 0f;
    private float voicingConfidence = 0f;
    private float smoothedFreq = 0f;
    private int samplesSinceDetect = 0;
    // YIN runs every ANALYSIS_INTERVAL samples (≈ 23 ms at 44.1 k).
    private static final int ANALYSIS_INTERVAL = 1024;

    private final float[] ring = new float[WINDOW];
    private int ringW = 0;
    private final float[] yinBuf = new float[WINDOW];
    private final float[] yinDiff = new float[YIN_MAX_TAU + 1];
    private final float[] yinCMND = new float[YIN_MAX_TAU + 1];

    // Smoothed needle position in cents (-50..+50). Lowpass so the
    // needle doesn't jitter on every micro-fluctuation.
    private float needleCents = 0f;
    private boolean voiced = false;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(ring, 0f);
        ringW = 0;
        detectedFreq = 0f; voicingConfidence = 0f; smoothedFreq = 0f;
        needleCents = 0f; voiced = false;
        samplesSinceDetect = 0;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            ring[ringW] = s;
            ringW++; if (ringW >= WINDOW) ringW = 0;
            samplesSinceDetect++;
        }
        if (samplesSinceDetect >= ANALYSIS_INTERVAL) {
            samplesSinceDetect = 0;
            detectPitch();
        }
    }

    private void detectPitch() {
        // Copy ring into linear buf so YIN sees most-recent samples last.
        int w = ringW;
        float rms = 0f;
        for (int i = 0; i < WINDOW; i++) {
            float s = ring[(w + i) % WINDOW];
            yinBuf[i] = s;
            rms += s * s;
        }
        rms = (float) Math.sqrt(rms / WINDOW);
        // Amplitude gate — silence has no fundamental.
        if (rms < 0.003f) {
            voicingConfidence = 0f;
            voiced = false;
            return;
        }
        // YIN difference function.
        final int half = WINDOW / 2;
        for (int tau = 1; tau <= YIN_MAX_TAU; tau++) {
            float sum = 0f;
            for (int j = 0; j < half; j++) {
                float d = yinBuf[j] - yinBuf[j + tau];
                sum += d * d;
            }
            yinDiff[tau] = sum;
        }
        // Cumulative mean normalized difference.
        yinCMND[0] = 1f;
        float running = 0f;
        for (int tau = 1; tau <= YIN_MAX_TAU; tau++) {
            running += yinDiff[tau];
            yinCMND[tau] = running > 1e-12f
                ? yinDiff[tau] * tau / running
                : 1f;
        }
        // Find first dip below threshold, then local minimum.
        int chosen = -1;
        for (int tau = YIN_MIN_TAU; tau < YIN_MAX_TAU - 1; tau++) {
            if (yinCMND[tau] < YIN_THRESHOLD) {
                while (tau + 1 < YIN_MAX_TAU && yinCMND[tau + 1] < yinCMND[tau]) tau++;
                chosen = tau;
                break;
            }
        }
        if (chosen < 0) {
            voicingConfidence = 0f;
            voiced = false;
            return;
        }
        // Parabolic interpolation for sub-sample tau.
        float refined = chosen;
        if (chosen > 0 && chosen < YIN_MAX_TAU) {
            float y1 = yinCMND[chosen - 1];
            float y2 = yinCMND[chosen];
            float y3 = yinCMND[chosen + 1];
            float denom = 2f * (2f * y2 - y1 - y3);
            if (Math.abs(denom) > 1e-9f) {
                float adj = (y3 - y1) / denom;
                if (adj > -1f && adj < 1f) refined += adj;
            }
        }
        detectedFreq = sampleRate / refined;
        voicingConfidence = 1f - yinCMND[chosen];
        if (detectedFreq < 50f || detectedFreq > 2000f) {
            voiced = false;
            return;
        }
        voiced = true;
        // Smooth slightly — heavier on glides, gentle on stable notes.
        if (smoothedFreq <= 0f) smoothedFreq = detectedFreq;
        else smoothedFreq = smoothedFreq + 0.45f * (detectedFreq - smoothedFreq);
    }

    // ---- Visual ----
    private static final int COLOR_BG          = 0xFF050505;
    private static final int COLOR_GRID        = 0xFF1E1E22;
    private static final int COLOR_TEXT_DIM    = 0xFF7C7C82;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_YELLOW      = 0xFFF5C842;
    private static final int COLOR_GREEN       = 0xFF6FE07A;
    private static final int COLOR_RED         = 0xFFE0606A;
    private static final int COLOR_DIM         = 0xFF3A3A40;

    private PluginPaint bgPaint, gridPaint, textDim, textBright, textHuge,
            needlePaint, tickPaint, centerPip, freqText;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        final float W = width, H = height;

        // 1. Background.
        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);

        // 2. Header
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("VOCAL TUNER", 12, 22, textBright);
        if (voiced) {
            textDim.setColor(COLOR_YELLOW).setTextSize(10f).setTextAlign(2);
            canvas.drawText(String.format("conf %d%%",
                    Math.round(voicingConfidence * 100)), W - 12, 22, textDim);
        } else {
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(10f).setTextAlign(2);
            canvas.drawText("--", W - 12, 22, textDim);
        }

        // 3. Compute note / cents from smoothed freq.
        String noteName = "--";
        int octave = 0;
        float centsOff = 0f;
        if (voiced && smoothedFreq > 0f) {
            double semitones = 12.0 * (Math.log(smoothedFreq / A4) / Math.log(2.0));
            // MIDI: A4 = 69, so note number = 69 + semitones.
            double midi = 69.0 + semitones;
            int midiRound = (int) Math.round(midi);
            centsOff = (float) ((midi - midiRound) * 100.0);
            int noteIdx = ((midiRound % 12) + 12) % 12;
            noteName = NOTE_NAMES[noteIdx];
            octave = (midiRound / 12) - 1;
        }
        // Smooth needle so it glides rather than jumps.
        float target = voiced ? Math.max(-50f, Math.min(50f, centsOff)) : 0f;
        needleCents = needleCents + 0.35f * (target - needleCents);

        // 4. Big note name in the centre upper half.
        textHuge.setColor(voiced ? COLOR_TEXT_BRIGHT : COLOR_DIM)
                .setTextSize(72f).setTextAlign(1);
        float nameY = H * 0.42f;
        canvas.drawText(noteName, W * 0.5f, nameY, textHuge);
        // Octave number small, lower right of the name.
        if (voiced) {
            textBright.setColor(COLOR_YELLOW).setTextSize(20f).setTextAlign(0);
            canvas.drawText(String.valueOf(octave),
                    W * 0.5f + getApproxTextWidth(noteName, 72f) * 0.55f,
                    nameY + 6f, textBright);
            // Hz readout below the note.
            freqText.setColor(COLOR_TEXT_DIM).setTextSize(13f).setTextAlign(1);
            canvas.drawText(String.format("%.1f Hz", smoothedFreq),
                    W * 0.5f, nameY + 22f, freqText);
        }

        // 5. Cents needle. Horizontal bar across the bottom half, -50
        //    cents on the left, +50 on the right, 0 in centre.
        float needleY0 = H * 0.62f;
        float needleY1 = H * 0.92f;
        float midY = (needleY0 + needleY1) * 0.5f;
        float trackX0 = 30f, trackX1 = W - 30f;
        float trackW = trackX1 - trackX0;
        // Track background line.
        gridPaint.setColor(COLOR_DIM).setStyle(PluginStyle.STROKE).setStrokeWidth(2f);
        canvas.drawLine(trackX0, midY, trackX1, midY, gridPaint);
        // Tick marks every 10 cents, bigger tick at 0.
        for (int c = -50; c <= 50; c += 10) {
            float t = (c + 50f) / 100f;
            float x = trackX0 + t * trackW;
            float yh = (c == 0) ? 10f : 5f;
            int col = (c == 0) ? COLOR_YELLOW : COLOR_TEXT_DIM;
            tickPaint.setColor(col).setStyle(PluginStyle.STROKE).setStrokeWidth(c == 0 ? 1.8f : 1f);
            canvas.drawLine(x, midY - yh, x, midY + yh, tickPaint);
            if (c % 25 == 0) {
                textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(1);
                canvas.drawText(c == 0 ? "0" : (c > 0 ? "+" + c : String.valueOf(c)),
                        x, needleY1 - 1, textDim);
            }
        }
        // Centre pip — circle at zero, lit green when in tune.
        if (voiced) {
            int needleColor = needleColorForCents(Math.abs(needleCents));
            // The needle itself: a vertical bar at the current cents position.
            float ndt = (needleCents + 50f) / 100f;
            float nx = trackX0 + ndt * trackW;
            needlePaint.setColor(needleColor).setStyle(PluginStyle.FILL)
                    .setGlow(needleColor, 8f);
            canvas.drawRect(nx - 2.5f, midY - 22f, nx + 2.5f, midY + 22f, needlePaint);
            // Cents label above needle.
            textBright.setColor(needleColor).setTextSize(13f).setTextAlign(1);
            String centsLabel = String.format("%+.1f c", needleCents);
            canvas.drawText(centsLabel, nx, midY - 26f, textBright);
            // "IN TUNE" pill when ±5 cents.
            if (Math.abs(needleCents) <= 5f) {
                centerPip.setColor(COLOR_GREEN).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f)
                        .setGlow(COLOR_GREEN, 6f);
                canvas.drawRoundRect(W * 0.5f - 38f, H * 0.5f + 4f,
                                     W * 0.5f + 38f, H * 0.5f + 22f, 9f, centerPip);
                textBright.setColor(COLOR_GREEN).setTextSize(10f).setTextAlign(1);
                canvas.drawText("IN TUNE", W * 0.5f, H * 0.5f + 17f, textBright);
            }
        }
    }

    private static int needleColorForCents(float absCents) {
        if (absCents <= 5f) return COLOR_GREEN;
        if (absCents <= 20f) {
            // Lerp green → yellow as we go from 5 to 20 cents.
            float t = (absCents - 5f) / 15f;
            return lerpColor(COLOR_GREEN, COLOR_YELLOW, t);
        }
        // 20+ cents: lerp yellow → red as we go to 50.
        float t = Math.min(1f, (absCents - 20f) / 30f);
        return lerpColor(COLOR_YELLOW, COLOR_RED, t);
    }

    private static int lerpColor(int a, int b, float t) {
        if (t < 0) t = 0; if (t > 1) t = 1;
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                |  (int) (ab + (bb - ab) * t);
    }

    // Rough text width estimate for layout when the host doesn't
    // expose measureText — needed to position the small octave number
    // next to the big note glyph.
    private static float getApproxTextWidth(String s, float size) {
        // Most letters in our note set average ~0.55*size wide.
        return s.length() * size * 0.55f;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint     = c.newPaint();
        gridPaint   = c.newPaint();
        textDim     = c.newPaint();
        textBright  = c.newPaint();
        textHuge    = c.newPaint();
        needlePaint = c.newPaint();
        tickPaint   = c.newPaint();
        centerPip   = c.newPaint();
        freqText    = c.newPaint();
    }
}
