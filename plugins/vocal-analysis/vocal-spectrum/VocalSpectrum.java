package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vocal Spectrum — pass-through audio plugin that renders:
 *
 *   - **Scrolling FFT spectrogram** (60 Hz – 16 kHz, log-y) on the
 *     left.  Linear-bin STFT for the heat colours.
 *   - **Log-Q live spectrum** on the right: per-semitone re-binned
 *     STFT magnitudes (MIDI 28..107 = E1..B7) with per-semitone
 *     **peak hold** that decays in 1.5 s.  This is the affordable
 *     stand-in for a true Constant-Q transform and gives the right
 *     on-screen feel (each semitone gets equal screen space).
 *   - **Spectral smoothing** knob (1 / 1/3 / 1/6 octave) — moving
 *     average across log-Q neighbours.
 *
 * Five vocal sub-band tints (MUD / BODY / HARSH / SIB / AIR) are
 * overlaid on the log-Q panel so the singer can recognise WHAT
 * they're looking at without reading a frequency tick.
 *
 * Parameters:
 *   smoothing  : 0 = 1/6 oct, 1 = 1/3 oct, 2 = 1 oct
 *   peakHold   : 0 = off, 1 = on
 */
public final class VocalSpectrum
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(specRing, 0f);
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(semiNow,  0f);
        java.util.Arrays.fill(semiPeak, 0f);
        ringW = 0; specWrite = 0; sampleAcc = 0;
    }

    @Override public String[] parameterNames() { return new String[] { "smoothing", "peakHold" }; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return n.equals("smoothing") ? 2f : 1f; }
    @Override public float parameterDefault(String n) {
        if (n.equals("smoothing")) return 1f;
        if (n.equals("peakHold"))  return 1f;
        return 0f;
    }
    @Override public String parameterLabel(String n) {
        if (n.equals("smoothing")) return "1/6 - 1/3 - 1 oct";
        if (n.equals("peakHold"))  return "off / on";
        return n;
    }
    @Override public void setParameter(String n, float v) {
        if (n.equals("smoothing")) smoothing = Math.round(v);
        else if (n.equals("peakHold")) peakHold = v > 0.5f;
    }

    private int smoothing = 1;
    private boolean peakHold = true;

    // ── FFT + spectrogram store ──
    private static final int FFT_SIZE = 1024;
    private static final int HOP      = 512;
    private static final int SPEC_COLS = 256;
    private static final int SPEC_BINS = FFT_SIZE / 2;
    private final float[] audioRing = new float[FFT_SIZE];
    private int ringW = 0, sampleAcc = 0;
    private final float[] specRing = new float[SPEC_COLS * SPEC_BINS];
    private int specWrite = 0;
    private final float[] hann = new float[FFT_SIZE];
    private boolean hannInit = false;
    private final float[] fftRe = new float[FFT_SIZE];
    private final float[] fftIm = new float[FFT_SIZE];

    // ── Log-Q live spectrum + peak hold ──
    private static final int MIDI_LO = 28;    // E1   ≈ 41.2 Hz
    private static final int MIDI_HI = 107;   // B7   ≈ 3951 Hz
    private static final int N_SEMI  = MIDI_HI - MIDI_LO + 1;
    private final float[] semiNow  = new float[N_SEMI];
    private final float[] semiPeak = new float[N_SEMI];
    private float peakDecay = 0.982f;   // ~1.5 s @ 86 fps

    // Pass-through + capture into a local ring so the visual can
    // animate even when the host doesn't wire up streams["waveform"].
    // Analysis is NOT driven here — render() picks the audio source
    // (host stream preferred, local ring fallback) and triggers
    // captureColumn() once per render frame.  Matches the glow-meter
    // pattern: plugin shows the live mic regardless of chain position.
    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % FFT_SIZE;
        }
    }

    // Pull audio from streams["waveform"] when supplied, else use the
    // local ring filled by process().  When the stream is supplied,
    // copy its last FFT_SIZE samples into audioRing and reset ringW
    // so captureColumn() reads them as a contiguous frame.
    private void prepareWindow(java.util.Map<String, float[]> streams) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 64) return;
        int n = wave.length;
        int start = n - FFT_SIZE;
        if (start < 0) {
            int pad = -start;
            for (int i = 0; i < pad; i++) audioRing[i] = 0f;
            for (int i = 0; i < n; i++) audioRing[pad + i] = wave[i];
        } else {
            for (int i = 0; i < FFT_SIZE; i++) audioRing[i] = wave[start + i];
        }
        ringW = 0;
    }

    private void captureColumn() {
        if (!hannInit) {
            for (int i = 0; i < FFT_SIZE; i++) {
                hann[i] = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
            }
            hannInit = true;
            // peak decay coefficient: peakHold halves in time = -ln(2)/halfSec
            // We want ~1.5 s for 1 → 0.1 → decay^fps_per_1.5s = 0.1, fps≈86
            // → decay = 0.1^(1/(1.5*86)) ≈ 0.982
        }
        for (int i = 0; i < FFT_SIZE; i++) {
            int idx = (ringW + i) % FFT_SIZE;
            fftRe[i] = audioRing[idx] * hann[i];
            fftIm[i] = 0f;
        }
        fftRadix2(fftRe, fftIm);
        // Spectrogram column (linear-bin dB).
        int colOff = specWrite * SPEC_BINS;
        for (int b = 0; b < SPEC_BINS; b++) {
            float mag = (float) Math.sqrt(fftRe[b] * fftRe[b] + fftIm[b] * fftIm[b]) / FFT_SIZE;
            float dB = (float) (20 * Math.log10(Math.max(1e-9f, mag)));
            float t = (dB + 90f) / 90f;
            if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
            specRing[colOff + b] = t;
        }
        specWrite = (specWrite + 1) % SPEC_COLS;
        // Log-Q live spectrum: distribute each FFT bin's magnitude
        // across its 2 nearest semitone bins (triangular bank).
        java.util.Arrays.fill(semiNow, 0f);
        float binHz = sampleRate / (float) FFT_SIZE;
        for (int k = 1; k < SPEC_BINS; k++) {
            float fkk = k * binHz;
            if (fkk < 35f || fkk > 4500f) continue;
            double midiF = 12.0 * (Math.log(fkk / 440.0) / Math.log(2.0)) + 69.0;
            double idxF = midiF - MIDI_LO;
            int nLow  = (int) Math.floor(idxF);
            int nHigh = nLow + 1;
            float frac = (float)(idxF - nLow);
            float magK = (float) Math.sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k])
                       / FFT_SIZE;
            if (nLow  >= 0 && nLow  < N_SEMI) semiNow[nLow]  += (1f - frac) * magK;
            if (nHigh >= 0 && nHigh < N_SEMI) semiNow[nHigh] += frac * magK;
        }
        // Apply smoothing.
        int smoothW = smoothing <= 0 ? 2 : smoothing == 1 ? 4 : 12;
        if (smoothW > 1) {
            float[] tmp = new float[N_SEMI];
            int half = smoothW / 2;
            for (int n = 0; n < N_SEMI; n++) {
                float sum = 0f; int cnt = 0;
                for (int k = -half; k <= half; k++) {
                    int j = n + k;
                    if (j < 0 || j >= N_SEMI) continue;
                    sum += semiNow[j]; cnt++;
                }
                tmp[n] = cnt > 0 ? sum / cnt : 0f;
            }
            System.arraycopy(tmp, 0, semiNow, 0, N_SEMI);
        }
        // Peak hold (decay every frame, raise to current if higher).
        for (int n = 0; n < N_SEMI; n++) {
            float decayed = semiPeak[n] * peakDecay;
            semiPeak[n] = Math.max(decayed, semiNow[n]);
        }
    }

    private static void fftRadix2(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2;
            double ang = -2.0 * Math.PI / size;
            float wpr = (float) Math.cos(ang);
            float wpi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += size) {
                float wr = 1f, wi = 0f;
                for (int j = 0; j < half; j++) {
                    int k = i + j, kh = k + half;
                    float tr = re[kh] * wr - im[kh] * wi;
                    float ti = re[kh] * wi + im[kh] * wr;
                    re[kh] = re[k] - tr; im[kh] = im[k] - ti;
                    re[k]  = re[k] + tr; im[k]  = im[k] + ti;
                    float nwr = wr * wpr - wi * wpi;
                    wi = wr * wpi + wi * wpr;
                    wr = nwr;
                }
            }
        }
    }

    // ── Visual ──────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SIGNATURE   = 0xFF5BD9E0;
    private static final int COLOR_MUD       = 0xFFB57A4F;
    private static final int COLOR_BODY      = 0xFF6FE07A;
    private static final int COLOR_HARSH     = 0xFFE34855;
    private static final int COLOR_SIBILANCE = 0xFFF5C842;
    private static final int COLOR_AIR       = 0xFF5BD9E0;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, bandPaint, cellPaint, peakPaint;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        if (width < 60 || height < 60) return;
        // Drive one analysis frame per render call.  Audio comes from
        // streams["waveform"] when the host supplies it, otherwise from
        // the local ring filled by process().
        prepareWindow(streams);
        captureColumn();
        float W = width, H = height;

        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("VOCAL SPECTRUM", 12f, 16f, textBright);
        String smLabel = smoothing <= 0 ? "1/6 oct" : smoothing == 1 ? "1/3 oct" : "1 oct";
        textDim.setColor(COLOR_SIGNATURE).setTextSize(10f).setTextAlign(2);
        canvas.drawText(String.format("log-Q  smoothing %s  peak %s",
                smLabel, peakHold ? "on" : "off"), W - 12f, 16f, textDim);

        float pad = 12f, headerH = 22f, labelH = 16f;
        float plotX0 = pad + 36f;
        float plotY0 = pad + headerH;
        float plotX1 = pad + 36f + (W - pad * 2 - 36f - 36f - 130f);
        float plotY1 = H - pad - labelH;
        // (Left card = spectrogram, right card = log-Q live spectrum.)

        // Spectrogram card.
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);

        float plotW = plotX1 - plotX0;
        float plotH = plotY1 - plotY0;
        float colW = plotW / SPEC_COLS;
        float logFmin = (float) Math.log(60.0);
        float logFmax = (float) Math.log(16000.0);
        for (int c = 0; c < SPEC_COLS; c++) {
            int colIdx = (specWrite + c) % SPEC_COLS;
            int colOff = colIdx * SPEC_BINS;
            float colX = plotX0 + c * colW;
            for (int b = 2; b < SPEC_BINS; b++) {
                float t = specRing[colOff + b];
                if (t < 0.08f) continue;
                float freq = b * (float) sampleRate / FFT_SIZE;
                if (freq < 60f || freq > 16000f) continue;
                float fT = ((float) Math.log(freq) - logFmin) / (logFmax - logFmin);
                float y = plotY1 - fT * plotH;
                float nextY = plotY1 -
                        ((float)(Math.log((b + 1) * (float) sampleRate / FFT_SIZE) - logFmin)
                          / (logFmax - logFmin)) * plotH;
                cellPaint.setColor(viridis(t)).setStyle(PluginStyle.FILL);
                canvas.drawRect(colX, nextY, colX + colW + 0.5f, y, cellPaint);
            }
        }
        int[] tickHz = { 100, 250, 500, 1000, 2500, 5000, 10000 };
        for (int hz : tickHz) {
            float fT = ((float) Math.log(hz) - logFmin) / (logFmax - logFmin);
            float y = plotY1 - fT * plotH;
            gridPaint.setColor(0x33FFFFFF).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            String lbl = hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz);
            canvas.drawText(lbl, plotX0 - 3f, y + 3f, textDim);
        }
        textDim.setTextSize(8.5f).setTextAlign(0);
        canvas.drawText("older", plotX0, plotY1 + 11f, textDim);
        textDim.setTextAlign(2);
        canvas.drawText("now", plotX1, plotY1 + 11f, textDim);

        // Log-Q live spectrum card on the right.
        float logX0 = plotX1 + 14f;
        float logX1 = W - pad;
        if (logX1 - logX0 > 60f) {
            cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(logX0 - 4f, plotY0 - 4f, logX1 + 4f, plotY1 + 4f, 8f, cardPaint);
            cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(logX0 - 4f, plotY0 - 4f, logX1 + 4f, plotY1 + 4f, 8f, cardPaint);
            float logW = logX1 - logX0;
            // Y axis is per-semitone (MIDI_LO..MIDI_HI), bottom→top = low→high.
            // Band tint overlays.
            drawBandTint(canvas, logX0, logX1, plotY0, plotY1,    80f,   300f, COLOR_MUD,       "MUD");
            drawBandTint(canvas, logX0, logX1, plotY0, plotY1,  1000f,  2000f, COLOR_BODY,      "BODY");
            drawBandTint(canvas, logX0, logX1, plotY0, plotY1,  2000f,  5000f, COLOR_HARSH,     "HARSH");
            drawBandTint(canvas, logX0, logX1, plotY0, plotY1,  5000f, 10000f, COLOR_SIBILANCE, "SIB");
            drawBandTint(canvas, logX0, logX1, plotY0, plotY1, 10000f, 16000f, COLOR_AIR,       "AIR");
            // Find max for normalisation.
            float maxV = 1e-6f;
            for (int n = 0; n < N_SEMI; n++) {
                if (semiPeak[n] > maxV) maxV = semiPeak[n];
            }
            // Bars + peak dots.
            float semiH = plotH / N_SEMI;
            for (int n = 0; n < N_SEMI; n++) {
                float yBot = plotY1 - n * semiH;
                float yTop = yBot - semiH;
                float v = semiNow[n] / maxV;
                if (v > 1f) v = 1f;
                float barX = logX0 + (1f - v) * logW * 0.0f;
                float barEnd = logX0 + logW * v;
                cellPaint.setColor(0xFFE6E6EA).setStyle(PluginStyle.FILL);
                canvas.drawRect(logX0, yTop + 1f, barEnd, yBot - 0.5f, cellPaint);
                if (peakHold) {
                    float pv = semiPeak[n] / maxV;
                    if (pv > 1f) pv = 1f;
                    float peakX = logX0 + logW * pv;
                    peakPaint.setColor(0xFFF5C842).setStyle(PluginStyle.FILL);
                    canvas.drawRect(peakX - 1f, yTop + 1f, peakX + 1f, yBot - 0.5f, peakPaint);
                }
            }
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
            canvas.drawText("log-Q (semitones)", logX0, plotY1 + 11f, textDim);
        }
    }

    // Translucent vocal-band tint overlay on the log-Q panel.
    private void drawBandTint(PluginCanvas canvas, float x0, float x1,
                               float plotY0, float plotY1,
                               float fLoHz, float fHiHz, int colour, String name) {
        // Convert Hz → semitone-index → y.
        double midiLow  = 12.0 * (Math.log(fLoHz / 440.0) / Math.log(2.0)) + 69.0;
        double midiHigh = 12.0 * (Math.log(fHiHz / 440.0) / Math.log(2.0)) + 69.0;
        double tLo = (midiLow  - MIDI_LO) / (double) N_SEMI;
        double tHi = (midiHigh - MIDI_LO) / (double) N_SEMI;
        if (tLo < 0) tLo = 0; if (tLo > 1) tLo = 1;
        if (tHi < 0) tHi = 0; if (tHi > 1) tHi = 1;
        float plotH = plotY1 - plotY0;
        float yLo = (float)(plotY1 - tLo * plotH);
        float yHi = (float)(plotY1 - tHi * plotH);
        bandPaint.setColor((colour & 0x00FFFFFF) | 0x22000000).setStyle(PluginStyle.FILL);
        canvas.drawRect(x0, yHi, x1, yLo, bandPaint);
        bandPaint.setColor((colour & 0x00FFFFFF) | 0xCC000000).setTextSize(8f).setTextAlign(2);
        canvas.drawText(name, x1 - 4f, (yHi + yLo) * 0.5f + 3f, bandPaint);
    }

    private static int viridis(double t) {
        if (t < 0) t = 0; if (t > 1) t = 1;
        double r, g, b;
        if (t < 0.5) {
            double u = t / 0.5;
            r = lerp(0x14, 0x21, u); g = lerp(0x0A, 0x91, u); b = lerp(0x2C, 0x8c, u);
        } else {
            double u = (t - 0.5) / 0.5;
            r = lerp(0x21, 0xfd, u); g = lerp(0x91, 0xe7, u); b = lerp(0x8c, 0x25, u);
        }
        return 0xFF000000 | (((int) r) << 16) | (((int) g) << 8) | ((int) b);
    }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        gridPaint  = c.newPaint();
        bandPaint  = c.newPaint();
        cellPaint  = c.newPaint();
        peakPaint  = c.newPaint();
    }
}
