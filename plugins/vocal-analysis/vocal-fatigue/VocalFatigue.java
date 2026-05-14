package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Vocal Load — long-window trend monitor with the clinical voice-
 * fatigue feature set.  NOT a medical diagnosis.  The display
 * answers one question: "is the singer's voice getting tireder vs
 * the start of this session?"
 *
 * 7 features, averaged over 30-second windows (last ~30 min):
 *
 *   - Pitch stability     : avg |cents-off-nearest-semitone|
 *   - Brightness          : HF/LF envelope ratio (4 kHz / 300 Hz)
 *   - HNR                 : 10·log10((1-CMND)/CMND) from YIN
 *   - Dynamic range       : RMS stdev within the window
 *   - **Jitter**          : Praat local (period perturbation %)
 *   - **Shimmer**         : Praat local (peak amplitude dB)
 *   - **CPP**             : Cepstral peak prominence (dB)
 *
 * Jitter / shimmer / CPP are the clinical fatigue indicators —
 * vocal-fold dysfunction shows up as elevated jitter+shimmer and
 * dropping CPP *before* the change is audible to the singer.
 *
 * Composite "load" score = average of normalised deltas where
 * degradation is in the upward direction.  Range 0–100,
 * 0 = "fresh", 100 = "you should stop, your fold tissue is loud".
 */
public final class VocalFatigue
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        java.util.Arrays.fill(loadHist, 0f);
        java.util.Arrays.fill(bandLoStateA, 0f);
        java.util.Arrays.fill(bandHiStateA, 0f);
        ringW = 0; sampleAcc = 0; histW = 0;
        resetWindow();
        baselineSet = false;
        for (int i = 0; i < N_FEAT; i++) { baseline[i] = 0f; lastDelta[i] = 0f; }
        currentLoad = 0f;
        prevPeriod = Float.NaN; prevPeak = Float.NaN;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // YIN + FFT shared on the 1024-sample frame.
    private static final int FFT_N = 1024;
    private static final int FFT_HOP = 512;
    private static final int FFT_HALF = FFT_N / 2;
    private static final int LAG_MIN = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.15f;
    private static final float A4 = 440f;
    private final float[] audioRing = new float[FFT_N];
    private final float[] yinBuf = new float[FFT_N];
    private final float[] yinDiff = new float[LAG_MAX + 1];
    private final float[] yinCMND = new float[LAG_MAX + 1];
    private final float[] fftRe = new float[FFT_N];
    private final float[] fftIm = new float[FFT_N];
    private final float[] cepIn = new float[FFT_N];
    private final float[] cepInIm = new float[FFT_N];
    private final float[] cepDb = new float[FFT_HALF];
    private final float[] hann = new float[FFT_N];
    {
        for (int i = 0; i < FFT_N; i++) {
            hann[i] = (float)(0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_N - 1))));
        }
    }
    private int ringW = 0, sampleAcc = 0;

    // Brightness via lo (300 Hz) and hi (4 kHz) bandpasses.
    private float[] bandLoCoefs, bandHiCoefs;
    private final float[] bandLoStateA = new float[4];
    private final float[] bandHiStateA = new float[4];
    private float loEnv = 0f, hiEnv = 0f;

    // 30-second window accumulators.
    private static final float WINDOW_SEC = 30f;
    private double winSumCentsAbs = 0;
    private double winSumLo = 0, winSumHi = 0;
    private double winSumHnr = 0;
    private double winSumRms = 0, winSumRmsSq = 0;
    private double winSumJitter = 0, winSumPeriod = 0; int winJitterN = 0;
    private double winSumShimmer = 0; int winShimmerN = 0;
    private double winSumCpp = 0; int winCppN = 0;
    private int winAccCount = 0;
    private float prevPeriod = Float.NaN, prevPeak = Float.NaN;

    // 7 features.
    private static final int N_FEAT = 7;
    private static final String[] FEAT_LABELS =
        { "pitch", "bright", "HNR", "DR", "jitter", "shimmer", "CPP" };
    private final float[] baseline = new float[N_FEAT];
    private final float[] lastDelta = new float[N_FEAT];
    private boolean baselineSet = false;

    private static final int HIST = 60;
    private final float[] loadHist = new float[HIST];
    private int histW = 0;
    private float currentLoad = 0f;

    // Pass-through + capture into a local ring; analysis runs in
    // render() from streams["waveform"] (preferred) or this ring.
    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % FFT_N;
        }
    }

    private void prepareWindow(java.util.Map<String, float[]> streams) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 64) return;
        int n = wave.length;
        int start = n - FFT_N;
        if (start < 0) {
            int pad = -start;
            for (int i = 0; i < pad; i++) audioRing[i] = 0f;
            for (int i = 0; i < n; i++) audioRing[pad + i] = wave[i];
        } else {
            for (int i = 0; i < FFT_N; i++) audioRing[i] = wave[start + i];
        }
        ringW = 0;
    }

    // Run the brightness biquad envelope chain over the audioRing
    // window once per analysis call (biquad state is kept across calls
    // so the IIRs evolve smoothly).
    private void updateEnvelopes() {
        if (bandLoCoefs == null) {
            bandLoCoefs = bandpass(300f, 1.0f, sampleRate);
            bandHiCoefs = bandpass(4000f, 1.0f, sampleRate);
        }
        float att = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.030));
        for (int i = 0; i < FFT_N; i++) {
            float s = audioRing[(ringW + i) % FFT_N];
            float yl = biquad(s, bandLoCoefs, bandLoStateA);
            float yh = biquad(s, bandHiCoefs, bandHiStateA);
            loEnv += att * ((yl < 0 ? -yl : yl) - loEnv);
            hiEnv += att * ((yh < 0 ? -yh : yh) - hiEnv);
        }
    }

    private void analyseFrame() {
        double energy = 0;
        for (int i = 0; i < FFT_N; i++) {
            int idx = (ringW + i) % FFT_N;
            float v = audioRing[idx];
            yinBuf[i] = v;
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / FFT_N);
        if (rms < 0.003f) { prevPeriod = Float.NaN; prevPeak = Float.NaN; return; }
        int half = FFT_N / 2;
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
        if (chosen < 0) { prevPeriod = Float.NaN; prevPeak = Float.NaN; return; }
        // Parabolic interpolation on YIN minimum → sub-sample period.
        float period = chosen;
        if (chosen > 1 && chosen < maxLag - 1) {
            float s0 = yinCMND[chosen - 1], s1 = yinCMND[chosen], s2 = yinCMND[chosen + 1];
            float denom = (s0 - 2f * s1 + s2);
            if (Math.abs(denom) > 1e-12f) period = chosen + 0.5f * (s0 - s2) / denom;
        }
        float freq = sampleRate / period;
        if (freq < 60f || freq > 1500f) { prevPeriod = Float.NaN; prevPeak = Float.NaN; return; }
        double semitones = 12.0 * (Math.log(freq / A4) / Math.log(2.0));
        int midiR = (int) Math.round(69.0 + semitones);
        float centsAbs = (float) Math.abs((69.0 + semitones - midiR) * 100.0);
        float hnr = (float)(10.0 * Math.log10(
                Math.max(1e-3f, (1f - yinCMND[chosen]) / yinCMND[chosen])));
        // Per-period peak amplitude — last `period` samples.
        int periodLen = Math.max(1, Math.round(period));
        int start = FFT_N - periodLen;
        float pk = 0f;
        for (int j = start; j < FFT_N; j++) {
            float a = Math.abs(yinBuf[j]);
            if (a > pk) pk = a;
        }
        // Jitter contribution: |T - prevT|.
        if (!Float.isNaN(prevPeriod)) {
            winSumJitter += Math.abs(period - prevPeriod);
            winJitterN++;
        }
        winSumPeriod += period;
        // Shimmer contribution: |20·log10(pk / prevPk)| dB.
        if (!Float.isNaN(prevPeak) && prevPeak > 1e-6f && pk > 1e-6f) {
            winSumShimmer += Math.abs(20.0 * Math.log10(pk / prevPeak));
            winShimmerN++;
        }
        prevPeriod = period;
        prevPeak = pk;

        // CPP contribution: 1024-pt FFT → dB log mag → IFFT → cepstrum.
        for (int i = 0; i < FFT_N; i++) {
            fftRe[i] = yinBuf[i] * hann[i];
            fftIm[i] = 0f;
        }
        fft(fftRe, fftIm);
        for (int k = 0; k < FFT_N; k++) {
            int kk = k <= FFT_HALF ? k : FFT_N - k;
            float mag = (float) Math.sqrt(fftRe[kk] * fftRe[kk] + fftIm[kk] * fftIm[kk]);
            cepIn[k] = 20f * (float) Math.log10(Math.max(1e-9f, mag));
            cepInIm[k] = 0f;
        }
        fft(cepIn, cepInIm);
        for (int k = 0; k < FFT_HALF; k++) cepDb[k] = cepIn[k] / FFT_N;
        int qMin = Math.max(2, (int) Math.ceil(sampleRate / 500.0));
        int qMax = Math.min(FFT_HALF - 1, (int) Math.floor(sampleRate / 60.0));
        if (qMax > qMin + 4) {
            double sumQ = 0, sumC = 0, sumQQ = 0, sumQC = 0;
            int nn = qMax - qMin + 1;
            for (int q = qMin; q <= qMax; q++) {
                sumQ += q; sumC += cepDb[q];
                sumQQ += (double)q * q; sumQC += (double)q * cepDb[q];
            }
            double denom = nn * sumQQ - sumQ * sumQ;
            if (Math.abs(denom) > 1e-9) {
                double slope = (nn * sumQC - sumQ * sumC) / denom;
                double intercept = (sumC - slope * sumQ) / nn;
                int peakQ = qMin; float pkVal = cepDb[qMin];
                for (int q = qMin; q <= qMax; q++) {
                    if (cepDb[q] > pkVal) { pkVal = cepDb[q]; peakQ = q; }
                }
                double predAtPeak = slope * peakQ + intercept;
                float cpp = (float)(pkVal - predAtPeak);
                if (cpp < 0f) cpp = 0f;
                winSumCpp += cpp;
                winCppN++;
            }
        }

        winSumCentsAbs += centsAbs;
        winSumLo += loEnv;
        winSumHi += hiEnv;
        winSumHnr += hnr;
        winSumRms += rms;
        winSumRmsSq += rms * rms;
        winAccCount++;
        // Window closes after WINDOW_SEC of analysis calls.  Analysis
        // now runs once per render call (~60 fps), so the count of
        // calls per second is approximately the render fps.
        float windowFrames = WINDOW_SEC * 60f;
        if (winAccCount >= windowFrames) closeWindow();
    }

    private void closeWindow() {
        if (winAccCount == 0) return;
        float avgCents  = (float)(winSumCentsAbs / winAccCount);
        float avgBright = (float)(winSumHi / Math.max(1, winSumLo));
        float avgHnr    = (float)(winSumHnr / winAccCount);
        float avgRms    = (float)(winSumRms / winAccCount);
        float varRms    = (float)(winSumRmsSq / winAccCount - avgRms * avgRms);
        if (varRms < 0f) varRms = 0f;
        float dr = (float) Math.sqrt(varRms);
        float jitter  = winJitterN > 0 && winAccCount > 0
                ? (float)((winSumJitter / winJitterN)
                          / (winSumPeriod / winAccCount) * 100.0)
                : 0f;
        float shimmer = winShimmerN > 0 ? (float)(winSumShimmer / winShimmerN) : 0f;
        float cpp     = winCppN > 0     ? (float)(winSumCpp / winCppN) : 0f;

        float[] vals = { avgCents, avgBright, avgHnr, dr, jitter, shimmer, cpp };
        if (!baselineSet) {
            System.arraycopy(vals, 0, baseline, 0, N_FEAT);
            baselineSet = true;
        }
        // Degradation direction per feature:
        //   pitch ↑, brightness ↓, HNR ↓, DR ↓,
        //   jitter ↑, shimmer ↑, CPP ↓
        boolean[] up = { true, false, false, false, true, true, false };
        float[] floor = { 1f, 0.05f, 1f, 0.01f, 0.1f, 0.1f, 1f };
        float sum = 0f;
        for (int i = 0; i < N_FEAT; i++) {
            float b = baseline[i];
            float d;
            if (up[i]) {
                d = Math.max(0f, (vals[i] - b) / Math.max(floor[i], b));
            } else {
                d = Math.max(0f, (b - vals[i]) / Math.max(floor[i], b));
            }
            // Clamp single-feature delta to ~3× baseline to prevent
            // one outlier dominating.
            if (d > 3f) d = 3f;
            lastDelta[i] = d;
            sum += d;
        }
        // Average per-feature normalised delta → 0..3 typically, map
        // to 0..100 with 1.5 = "very tired" being load 100.
        currentLoad = Math.min(100f, (sum / N_FEAT) / 1.5f * 100f);
        loadHist[histW] = currentLoad;
        histW = (histW + 1) % HIST;
        resetWindow();
    }

    private void resetWindow() {
        winSumCentsAbs = winSumLo = winSumHi = winSumHnr = 0;
        winSumRms = winSumRmsSq = 0;
        winSumJitter = winSumPeriod = 0; winJitterN = 0;
        winSumShimmer = 0; winShimmerN = 0;
        winSumCpp = 0; winCppN = 0;
        winAccCount = 0;
    }

    private static void fft(float[] re, float[] im) {
        int n = re.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wRe = (float) Math.cos(ang);
            float wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float wpr = 1f, wpi = 0f;
                int half = len >> 1;
                for (int k = 0; k < half; k++) {
                    int a = i + k, b = a + half;
                    float tr = wpr * re[b] - wpi * im[b];
                    float ti = wpr * im[b] + wpi * re[b];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    float nwpr = wpr * wRe - wpi * wIm;
                    wpi = wpr * wIm + wpi * wRe;
                    wpr = nwpr;
                }
            }
        }
    }

    private static float biquad(float x, float[] c, float[] st) {
        float y = c[0] * x + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3];
        st[1] = st[0]; st[0] = x;
        st[3] = st[2]; st[2] = y;
        return y;
    }
    private static float[] bandpass(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double a0 = 1.0 + alpha;
        return new float[] {
            (float)(alpha / a0), 0f, (float)(-alpha / a0),
            (float)(-2.0 * cs / a0), (float)((1.0 - alpha) / a0)
        };
    }

    // ── Visual ─────────────────────────────────────────────────
    private static final int COLOR_BG          = 0xFF0E0F12;
    private static final int COLOR_CARD        = 0xFF1A1B1F;
    private static final int COLOR_CARD_BORDER = 0xFF2A2B2F;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_TEXT_DIM    = 0xFF8A8B8F;
    private static final int COLOR_SIGNATURE   = 0xFFFFA040;

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            gridPaint, fillPaint, linePaint, barPaint;
    private PluginPath linePath, fillPath;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        if (width < 60 || height < 60) return;
        prepareWindow(streams);
        updateEnvelopes();
        analyseFrame();
        float W = width, H = height;
        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("VOCAL LOAD", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText("trend vs session start - NOT a medical diagnosis",
                W - 12f, 16f, textDim);

        int col = loadColour(currentLoad);
        textBright.setColor(col).setTextSize(34f).setTextAlign(0);
        canvas.drawText(String.format("%.0f", currentLoad), 12f, 56f, textBright);
        textDim.setColor(col).setTextSize(11f).setTextAlign(0);
        String verdict = currentLoad < 25 ? "FRESH"
                      : currentLoad < 50 ? "WARM"
                      : currentLoad < 75 ? "WORKING" : "TIRED";
        canvas.drawText(verdict, 70f, 56f, textDim);

        // Per-feature contribution row underneath the big number.
        float pad = 12f, topY = 70f;
        float featRowY = topY;
        float featRowH = 18f;
        float plotY0 = featRowY + featRowH + 8f;
        // Each feature: small bar 0..3 (clamped delta) coloured by load colour.
        float featX0 = pad + 24f;
        float featX1 = W - pad;
        float cellW = (featX1 - featX0) / N_FEAT;
        for (int i = 0; i < N_FEAT; i++) {
            float x0 = featX0 + i * cellW + 1f;
            float x1 = featX0 + (i + 1) * cellW - 1f;
            cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, featRowY, x1, featRowY + featRowH, 3f, cardPaint);
            float d = Math.min(3f, lastDelta[i]) / 3f;
            int barCol = d > 0.66f ? 0xFFE0606A : d > 0.33f ? 0xFFFFA040 : 0xFF6FE07A;
            barPaint.setColor(barCol).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, featRowY, x0 + (x1 - x0) * d, featRowY + featRowH, 3f, barPaint);
            textDim.setColor(COLOR_TEXT_BRIGHT).setTextSize(8.5f).setTextAlign(1);
            canvas.drawText(FEAT_LABELS[i], (x0 + x1) * 0.5f,
                    featRowY + featRowH * 0.5f + 3f, textDim);
        }

        // Per-window trend graph.
        float plotX0 = pad + 24f, plotX1 = W - pad;
        float plotY1 = H - pad - 14f;
        float plotW = plotX1 - plotX0, plotH = plotY1 - plotY0;
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(plotX0 - 4f, plotY0 - 4f, plotX1 + 4f, plotY1 + 4f, 8f, cardPaint);
        for (int s = 0; s <= 100; s += 25) {
            float y = plotY1 - (s / 100f) * plotH;
            gridPaint.setColor(0xFF353638).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
            canvas.drawLine(plotX0, y, plotX1, y, gridPaint);
            textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(2);
            canvas.drawText(s + "", plotX0 - 3f, y + 3f, textDim);
        }
        float step = plotW / (HIST - 1f);
        linePath.reset(); fillPath.reset();
        boolean started = false;
        for (int i = 0; i < HIST; i++) {
            int idx = (histW + i) % HIST;
            float v = loadHist[idx];
            float px = plotX0 + i * step;
            float py = plotY1 - (v / 100f) * plotH;
            if (!started) {
                linePath.moveTo(px, py);
                fillPath.moveTo(px, plotY1).lineTo(px, py);
                started = true;
            } else {
                linePath.lineTo(px, py);
                fillPath.lineTo(px, py);
            }
        }
        fillPath.lineTo(plotX0 + (HIST - 1) * step, plotY1).close();
        fillPaint.setColor(0x44FFA040).setStyle(PluginStyle.FILL);
        canvas.drawPath(fillPath, fillPaint);
        linePaint.setColor(COLOR_SIGNATURE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f);
        canvas.drawPath(linePath, linePaint);

        textDim.setColor(COLOR_TEXT_DIM).setTextSize(8.5f).setTextAlign(0);
        canvas.drawText("session start", plotX0, plotY1 + 11f, textDim);
        textDim.setTextAlign(2);
        canvas.drawText("now (30 s windows)", plotX1, plotY1 + 11f, textDim);
    }

    private static int loadColour(float v) {
        if (v < 25f) return 0xFF6FE07A;
        if (v < 50f) return 0xFFF5C842;
        if (v < 75f) return 0xFFFFA040;
        return 0xFFE0606A;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        gridPaint  = c.newPaint();
        fillPaint  = c.newPaint();
        linePaint  = c.newPaint();
        barPaint   = c.newPaint();
        linePath   = c.newPath();
        fillPath   = c.newPath();
    }
}
