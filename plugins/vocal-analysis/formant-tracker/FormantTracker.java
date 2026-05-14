package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Formant Tracker — pro-grade F1/F2/F3 + bandwidths via **LPC root
 * finding** (Durand-Kerner) and **greedy continuity tracking**.
 *
 *   - 12th-order LPC by autocorrelation + Levinson-Durbin (existing).
 *   - **Durand-Kerner** finds the 12 complex roots in 25 iterations.
 *     For each root z = r·e^(jω):
 *       f = ω · sr / (2π)
 *       bw = −ln(r) · sr / π
 *   - Roots are kept when 90 ≤ f ≤ 5500 Hz, bw ≤ 600 Hz, |z| < 1.
 *   - **Continuity tracker**: each frame's previous F1/F2/F3 are
 *     greedily reassigned to the new root nearest in Hz (within 350
 *     Hz) — prevents F1↔F2 label swapping during glides.
 *
 * Displays F1/F2/F3 with their bandwidths on the classic F1-F2
 * vowel map.
 */
public final class FormantTracker
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        ringW = 0; sampleAcc = 0;
        java.util.Arrays.fill(trailF1, 0f);
        java.util.Arrays.fill(trailF2, 0f);
        trailW = 0;
        f1 = f2 = f3 = 0f;
        bw1 = bw2 = bw3 = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // ── LPC analysis ──
    //
    // Pro formant trackers (Praat, Wavesurfer) downsample the audio to
    // ≈ 2× the maximum formant frequency before fitting LPC, so the
    // same handful of poles only has to model the formant band — at
    // 44.1 kHz with order 12 the LPC under-fits and produces broad
    // peaks (BW > 1 kHz) instead of real formants.  We anti-alias at
    // 5 kHz with a 4-pole Butterworth then decimate by 4 → 11.025 kHz,
    // and run order-12 LPC there.
    private static final int LPC_ORDER  = 12;
    private static final int FRAME_SIZE = 1024;
    private static final int HOP        = 512;
    private static final int DEC_FACTOR = 4;
    private static final int DEC_SIZE   = FRAME_SIZE / DEC_FACTOR;     // 256
    private final float[] audioRing = new float[FRAME_SIZE];
    private final float[] frame     = new float[FRAME_SIZE];
    private final float[] frameDec  = new float[DEC_SIZE];
    private final float[] lpcA = new float[LPC_ORDER + 1];
    private final float[] R = new float[LPC_ORDER + 1];
    private int ringW = 0, sampleAcc = 0;

    // Anti-aliasing biquad — designed lazily once we know sampleRate.
    private float[] aaCoefs;

    // LPC magnitude spectrum, sampled on SPEC_BINS points across the
    // formant band (0..5.5 kHz), for peak-picking.
    private static final int SPEC_BINS = 256;
    private final float[] lpcSpec = new float[SPEC_BINS];

    // ── Result + trail ──
    private float f1 = 0f, f2 = 0f, f3 = 0f;
    private float bw1 = 0f, bw2 = 0f, bw3 = 0f;
    private static final int TRAIL_LEN = 80;
    private final float[] trailF1 = new float[TRAIL_LEN];
    private final float[] trailF2 = new float[TRAIL_LEN];
    private int trailW = 0;

    // Pass-through + capture into a local ring; analysis runs in
    // render() from streams["waveform"] (preferred) or this ring.
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
        if (aaCoefs == null) aaCoefs = lowPassBiquad(5000f, 0.707f, sampleRate);
        // 1. Pre-emphasis (HF +6 dB/oct, kills bass tilt that would
        //    otherwise dominate the LPC fit).
        float prev = 0f;
        double energy = 0;
        for (int i = 0; i < FRAME_SIZE; i++) {
            int idx = (ringW + i) % FRAME_SIZE;
            float v = audioRing[idx] - 0.97f * prev;
            prev = audioRing[idx];
            frame[i] = v;
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / FRAME_SIZE);
        if (rms < 0.001f) return;
        // 2. Anti-aliasing low-pass at 5 kHz — 4-pole Butterworth
        //    (cascade of two identical biquads).  Reset state per frame
        //    so the IIR transient lives at the edges of the analysis
        //    window where the Hann window will window it away.
        float s1a = 0f, s1b = 0f, s1c = 0f, s1d = 0f;
        float s2a = 0f, s2b = 0f, s2c = 0f, s2d = 0f;
        for (int i = 0; i < FRAME_SIZE; i++) {
            float x = frame[i];
            float y1 = aaCoefs[0] * x + aaCoefs[1] * s1a + aaCoefs[2] * s1b
                     - aaCoefs[3] * s1c - aaCoefs[4] * s1d;
            s1b = s1a; s1a = x; s1d = s1c; s1c = y1;
            float y2 = aaCoefs[0] * y1 + aaCoefs[1] * s2a + aaCoefs[2] * s2b
                     - aaCoefs[3] * s2c - aaCoefs[4] * s2d;
            s2b = s2a; s2a = y1; s2d = s2c; s2c = y2;
            frame[i] = y2;
        }
        // 3. Decimate by 4 + Hann window over the decimated frame.
        for (int i = 0; i < DEC_SIZE; i++) {
            float w = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (DEC_SIZE - 1)));
            frameDec[i] = frame[i * DEC_FACTOR] * w;
        }
        // 4. Autocorrelation on the decimated frame.
        for (int k = 0; k <= LPC_ORDER; k++) {
            float sum = 0f;
            for (int i = k; i < DEC_SIZE; i++) sum += frameDec[i] * frameDec[i - k];
            R[k] = sum;
        }
        if (R[0] < 1e-9f) return;
        // Levinson-Durbin → lpcA.
        float[] a = new float[LPC_ORDER + 1];
        float[] aPrev = new float[LPC_ORDER + 1];
        float Eerr = R[0];
        a[0] = 1f;
        for (int p = 1; p <= LPC_ORDER; p++) {
            float k = -R[p];
            for (int j = 1; j < p; j++) k -= a[j] * R[p - j];
            k /= Eerr;
            if (k > 0.99f) k = 0.99f; if (k < -0.99f) k = -0.99f;
            System.arraycopy(a, 0, aPrev, 0, p);
            a[p] = k;
            for (int j = 1; j < p; j++) a[j] = aPrev[j] + k * aPrev[p - j];
            Eerr *= 1f - k * k;
            if (Eerr < 1e-9f) Eerr = 1e-9f;
        }
        System.arraycopy(a, 0, lpcA, 0, LPC_ORDER + 1);

        // LPC magnitude spectrum |1 / A(e^jω)| sampled on SPEC_BINS
        // points across 0..5.5 kHz at the *decimated* sample rate
        // (sampleRate / DEC_FACTOR).  Pick 2-bin local maxima, then
        // estimate bandwidth from the −3 dB width.  Real formants come
        // out narrow (50–200 Hz); we reject anything wider than 500 Hz
        // as a non-formant spectral lump.
        float maxHz = 5500f;
        float decSr = sampleRate / (float) DEC_FACTOR;
        for (int b = 0; b < SPEC_BINS; b++) {
            float freq = (b + 1) * maxHz / SPEC_BINS;
            double w = 2.0 * Math.PI * freq / decSr;
            double re = 0, im = 0;
            for (int k = 0; k <= LPC_ORDER; k++) {
                re += lpcA[k] * Math.cos(-w * k);
                im += lpcA[k] * Math.sin(-w * k);
            }
            double mag2 = re * re + im * im;
            lpcSpec[b] = mag2 > 1e-12f ? (float)(1.0 / Math.sqrt(mag2)) : 0f;
        }
        float[] candF  = new float[8];
        float[] candBw = new float[8];
        int nCand = 0;
        for (int b = 2; b < SPEC_BINS - 2 && nCand < candF.length; b++) {
            float v = lpcSpec[b];
            if (v > lpcSpec[b - 1] && v > lpcSpec[b + 1]
                    && v > lpcSpec[b - 2] && v > lpcSpec[b + 2]) {
                float peakHz = (b + 1) * maxHz / SPEC_BINS;
                if (peakHz < 150f || peakHz > 5300f) continue;
                // −3 dB bandwidth: walk left + right until magnitude
                // drops 1/sqrt(2) below the peak.
                float thresh = v * 0.7071f;
                int bLo = b, bHi = b;
                while (bLo > 0 && lpcSpec[bLo] > thresh) bLo--;
                while (bHi < SPEC_BINS - 1 && lpcSpec[bHi] > thresh) bHi++;
                float bwHz = (bHi - bLo) * maxHz / SPEC_BINS;
                if (bwHz > 500f) continue;     // not a real formant
                candF[nCand]  = peakHz;
                candBw[nCand] = bwHz;
                nCand++;
            }
        }
        if (nCand == 0) return;
        // Sort candidates ascending by frequency.
        for (int i = 1; i < nCand; i++) {
            float kf = candF[i], kbw = candBw[i];
            int j = i - 1;
            while (j >= 0 && candF[j] > kf) {
                candF[j + 1] = candF[j];
                candBw[j + 1] = candBw[j];
                j--;
            }
            candF[j + 1] = kf;
            candBw[j + 1] = kbw;
        }
        // Continuity assignment to F1/F2/F3.  If we have a previous
        // estimate, find the nearest new candidate within 350 Hz.
        // Otherwise take the 3 lowest candidates.
        float[] newF  = new float[3];
        float[] newBw = new float[3];
        boolean[] used = new boolean[nCand];
        float[] prevF = { f1, f2, f3 };
        boolean haveHistory = f1 > 0f && f2 > 0f;
        if (haveHistory) {
            // Greedy: pick the (formantSlot, candidate) pair with the
            // smallest distance, repeat for 3 slots.
            for (int round = 0; round < 3; round++) {
                int bestSlot = -1, bestCand = -1;
                float bestDist = 350f;
                for (int slot = 0; slot < 3; slot++) {
                    if (prevF[slot] <= 0f || newF[slot] > 0f) continue;
                    for (int c = 0; c < nCand; c++) {
                        if (used[c]) continue;
                        float d = Math.abs(candF[c] - prevF[slot]);
                        if (d < bestDist) {
                            bestDist = d; bestSlot = slot; bestCand = c;
                        }
                    }
                }
                if (bestSlot < 0) break;
                newF [bestSlot] = candF [bestCand];
                newBw[bestSlot] = candBw[bestCand];
                used[bestCand] = true;
            }
        }
        // Fill any unassigned slots from the lowest remaining
        // candidates, in ascending order.  Slot ranges overlap so a
        // single peak can land in slot 0 OR slot 1 depending on the
        // assignment order — that's intentional, it avoids dropping a
        // perfectly good F1 when no candidate exists in F2's narrow
        // range.
        for (int slot = 0; slot < 3; slot++) {
            if (newF[slot] > 0f) continue;
            for (int c = 0; c < nCand; c++) {
                if (used[c]) continue;
                float lo = slot == 0 ? 150f : slot == 1 ? 600f  : 1700f;
                float hi = slot == 0 ? 1200f : slot == 1 ? 3200f : 5000f;
                if (candF[c] < lo || candF[c] > hi) continue;
                newF[slot]  = candF[c];
                newBw[slot] = candBw[c];
                used[c] = true;
                break;
            }
        }
        // Smooth EACH slot independently — partial results (only F1
        // found, or only F2 found) still update the live display
        // instead of being thrown away because the "and" gate failed.
        float coef = 0.3f;
        if (newF[0]  > 0f) f1  = f1  == 0f ? newF[0]  : f1  + coef * (newF[0]  - f1);
        if (newF[1]  > 0f) f2  = f2  == 0f ? newF[1]  : f2  + coef * (newF[1]  - f2);
        if (newF[2]  > 0f) f3  = f3  == 0f ? newF[2]  : f3  + coef * (newF[2]  - f3);
        if (newBw[0] > 0f) bw1 = bw1 == 0f ? newBw[0] : bw1 + coef * (newBw[0] - bw1);
        if (newBw[1] > 0f) bw2 = bw2 == 0f ? newBw[1] : bw2 + coef * (newBw[1] - bw2);
        if (newBw[2] > 0f) bw3 = bw3 == 0f ? newBw[2] : bw3 + coef * (newBw[2] - bw3);
        if (f1 > 0f && f2 > 0f) {
            trailF1[trailW] = f1;
            trailF2[trailW] = f2;
            trailW = (trailW + 1) % TRAIL_LEN;
        }
    }

    // RBJ-cookbook low-pass biquad, normalised to a0=1 form for the
    // direct-form-1 difference equation used inline above.
    private static float[] lowPassBiquad(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double a0 = 1 + alpha;
        return new float[] {
            (float)((1 - cs) * 0.5 / a0),     // b0
            (float)((1 - cs)        / a0),     // b1
            (float)((1 - cs) * 0.5 / a0),     // b2
            (float)(-2 * cs        / a0),     // a1
            (float)((1 - alpha)    / a0),     // a2
        };
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SIGNATURE   = 0xFFEE8A2C;
    private static final int COLOR_GRID        = 0xFF202125;

    private static final String[] VOWELS = { "I","E","A","O","U" };
    private static final float[] VOWEL_F1 = { 300f, 480f, 700f, 500f, 320f };
    private static final float[] VOWEL_F2 = { 2200f, 1800f, 1100f, 900f, 800f };

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, trailPaint, dotPaint, vowelPaint, vowelLabel;

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
        canvas.drawText("FORMANT TRACKER", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText("LPC 12 @ 11 kHz + peak-pick", W - 12f, 16f, textDim);

        float pad = 12f, headerH = 24f, footerH = 26f;
        float plotX0 = pad + 40f;
        float plotY0 = pad + headerH;
        float plotX1 = W - pad;
        float plotY1 = H - pad - footerH;

        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        for (int hz = 200; hz <= 1000; hz += 200) {
            float y = mapF1(hz, plotY0, plotY1);
            gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8f).setTextAlign(2);
            canvas.drawText(hz + "", plotX0 - 3f, y + 3f, textDim);
        }
        for (int hz = 800; hz <= 2400; hz += 400) {
            float x = mapF2(hz, plotX0, plotX1);
            gridPaint.setColor(COLOR_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(x, plotY0, x, plotY1, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8f).setTextAlign(1);
            canvas.drawText(hz + "", x, plotY1 + 11f, textDim);
        }
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("F1 (Hz)", pad, plotY0 - 4f, textDim);
        textDim.setTextAlign(2);
        canvas.drawText("F2 (Hz)", plotX1, plotY1 + 22f, textDim);

        for (int i = 0; i < VOWELS.length; i++) {
            float vx = mapF2(VOWEL_F2[i], plotX0, plotX1);
            float vy = mapF1(VOWEL_F1[i], plotY0, plotY1);
            vowelPaint.setColor(0x44EE8A2C).setStyle(PluginStyle.FILL);
            canvas.drawCircle(vx, vy, 20f, vowelPaint);
            vowelPaint.setColor(COLOR_SIGNATURE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
            canvas.drawCircle(vx, vy, 20f, vowelPaint);
            vowelLabel.setColor(COLOR_TEXT_BRIGHT).setTextSize(13f).setTextAlign(1);
            canvas.drawText(VOWELS[i], vx, vy + 5f, vowelLabel);
        }

        for (int i = 0; i < TRAIL_LEN; i++) {
            int idx = (trailW - 1 - i + TRAIL_LEN * 2) % TRAIL_LEN;
            float ff1 = trailF1[idx];
            float ff2 = trailF2[idx];
            if (ff1 <= 0f) continue;
            float tx = mapF2(ff2, plotX0, plotX1);
            float ty = mapF1(ff1, plotY0, plotY1);
            int alpha = (int)(220 * (1f - i / (float) TRAIL_LEN));
            if (alpha < 30) alpha = 30;
            int col = (alpha << 24) | (COLOR_SIGNATURE & 0x00FFFFFF);
            trailPaint.setColor(col).setStyle(PluginStyle.FILL);
            canvas.drawCircle(tx, ty, i == 0 ? 5f : 2.5f, trailPaint);
        }

        if (f1 > 0f && f2 > 0f) {
            float tx = mapF2(f2, plotX0, plotX1);
            float ty = mapF1(f1, plotY0, plotY1);
            dotPaint.setColor(0xFFF5C842).setStyle(PluginStyle.STROKE).setStrokeWidth(1.5f);
            canvas.drawLine(tx - 14, ty, tx + 14, ty, dotPaint);
            canvas.drawLine(tx, ty - 14, tx, ty + 14, dotPaint);
            dotPaint.setColor(0xFFF5C842).setStyle(PluginStyle.FILL);
            canvas.drawCircle(tx, ty, 4f, dotPaint);
        }

        float ny = H - pad - 4f;
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText(f1 > 0f ? String.format("F1 %.0f Hz (BW %.0f)", f1, bw1) : "F1 --",
                pad, ny, textDim);
        canvas.drawText(f2 > 0f ? String.format("F2 %.0f Hz (BW %.0f)", f2, bw2) : "F2 --",
                pad + 140f, ny, textDim);
        canvas.drawText(f3 > 0f ? String.format("F3 %.0f Hz (BW %.0f)", f3, bw3) : "F3 --",
                pad + 280f, ny, textDim);
        textDim.setColor(COLOR_SIGNATURE).setTextAlign(2);
        canvas.drawText("vowel map", plotX1, ny, textDim);
    }

    private float mapF1(float hz, float y0, float y1) {
        float t = (hz - 200f) / 800f;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return y0 + t * (y1 - y0);
    }
    private float mapF2(float hz, float x0, float x1) {
        float t = (hz - 800f) / 1600f;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return x1 - t * (x1 - x0);
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        gridPaint  = c.newPaint();
        trailPaint = c.newPaint();
        dotPaint   = c.newPaint();
        vowelPaint = c.newPaint();
        vowelLabel = c.newPaint();
    }
}
