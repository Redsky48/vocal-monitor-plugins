package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Register Detector — heuristic register classifier using **four
 * features** the literature actually uses:
 *
 *   - Pitch (Hz)              : range (low / mid / high)
 *   - Spectral tilt (dB/oct)  : low/high band ratio
 *   - Singer's-formant ratio  : 3 kHz band vs broadband RMS
 *   - **H1-H2 (dB)**          : magnitude of first harmonic minus
 *                                second harmonic, sampled directly
 *                                from the FFT with parabolic
 *                                interpolation.  This is the
 *                                textbook register cue (Sundberg,
 *                                Titze): large positive in falsetto/
 *                                breathy, small or negative in chest.
 *
 * Per-register evidence is a sum of per-feature gaussians; the
 * winning register is the argmax across 5 categories.
 *
 * **Honest caveat:** true register classification needs labelled
 * training data and a small classifier.  We don't have either, so
 * this is "the best heuristic possible with the right features",
 * not ML-grade.  Header tags it `heuristic`.
 */
public final class RegisterDetector
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        for (int j = 0; j < 4; j++) { sfState[j] = 0f; loState[j] = 0f; hiState[j] = 0f; }
        sfEnv = loEnv = hiEnv = totalEnv = 0f;
        ringW = 0; sampleAcc = 0;
        java.util.Arrays.fill(scores, 0f);
        java.util.Arrays.fill(scoreSmooth, 0f);
        bestIdx = -1;
        currentFreq = 0f; spectralTilt = 0f; sfRatio = 0f; h1h2Db = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // YIN + FFT config.
    private static final int FFT_N = 1024;
    private static final int FFT_HOP = 512;
    private static final int FFT_HALF = FFT_N / 2;
    private static final int LAG_MIN = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.15f;
    private final float[] audioRing = new float[FFT_N];
    private final float[] yinBuf = new float[FFT_N];
    private final float[] yinDiff = new float[LAG_MAX + 1];
    private final float[] yinCMND = new float[LAG_MAX + 1];
    private final float[] fftRe = new float[FFT_N];
    private final float[] fftIm = new float[FFT_N];
    private final float[] magDb = new float[FFT_HALF];
    private final float[] hann  = new float[FFT_N];
    {
        for (int i = 0; i < FFT_N; i++) {
            hann[i] = (float)(0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_N - 1))));
        }
    }
    private int ringW = 0, sampleAcc = 0;

    // Bands for tilt + SF.
    private float[] sfCoefs, loCoefs, hiCoefs;
    private final float[] sfState = new float[4];
    private final float[] loState = new float[4];
    private final float[] hiState = new float[4];
    private float sfEnv = 0f, loEnv = 0f, hiEnv = 0f;
    private float totalEnv = 0f;

    private static final int N_REG = 5;
    private static final String[] REG = { "CHEST", "MIX", "HEAD", "FALSETTO", "BELT" };
    private static final int[] REG_COLOURS = {
        0xFFE34855, 0xFFEE8A2C, 0xFF5BD9E0, 0xFFA060E0, 0xFFF5C842
    };
    private final float[] scores = new float[N_REG];
    private final float[] scoreSmooth = new float[N_REG];
    private int bestIdx = -1;
    private float currentFreq = 0f;
    private float spectralTilt = 0f;
    private float sfRatio = 0f;
    private float h1h2Db = 0f;

    // Pass-through + capture into a local ring; per-sample biquad
    // envelopes and analysis run in render() from streams["waveform"]
    // (preferred) or this ring.
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

    // Run the per-sample biquad envelope chain over the audioRing
    // window once per analysis call.  Biquad state is kept across
    // calls so the IIR envelope IIRs (sfEnv/loEnv/hiEnv/totalEnv)
    // evolve smoothly even though we batch-process a whole window.
    private void updateEnvelopes() {
        if (sfCoefs == null) {
            sfCoefs = bandpass(3000f, 4.0f, sampleRate);
            loCoefs = bandpass(600f,  1.0f, sampleRate);
            hiCoefs = bandpass(5000f, 1.0f, sampleRate);
        }
        float att = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.020));
        for (int i = 0; i < FFT_N; i++) {
            float s = audioRing[(ringW + i) % FFT_N];
            float ys = biquad(s, sfCoefs, sfState);
            float yl = biquad(s, loCoefs, loState);
            float yh = biquad(s, hiCoefs, hiState);
            sfEnv    += att * ((ys < 0 ? -ys : ys) - sfEnv);
            loEnv    += att * ((yl < 0 ? -yl : yl) - loEnv);
            hiEnv    += att * ((yh < 0 ? -yh : yh) - hiEnv);
            totalEnv += att * ((s < 0 ? -s : s) - totalEnv);
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
        if (rms < 0.003f) {
            for (int i = 0; i < N_REG; i++) scoreSmooth[i] *= 0.85f;
            bestIdx = -1;
            return;
        }
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
        if (chosen < 0) return;
        currentFreq = sampleRate / (float) chosen;
        spectralTilt = (float)(20.0 * Math.log10(Math.max(1e-6f, loEnv) / Math.max(1e-6f, hiEnv)));
        sfRatio = totalEnv > 1e-6f ? sfEnv / totalEnv : 0f;

        // FFT for H1-H2.
        for (int i = 0; i < FFT_N; i++) {
            fftRe[i] = yinBuf[i] * hann[i];
            fftIm[i] = 0f;
        }
        fft(fftRe, fftIm);
        for (int k = 0; k < FFT_HALF; k++) {
            float mag = (float) Math.sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]);
            magDb[k] = 20f * (float) Math.log10(Math.max(1e-9f, mag));
        }
        float binHz = sampleRate / (float) FFT_N;
        float h1 = peakMagDb(currentFreq, binHz);
        float h2 = peakMagDb(2f * currentFreq, binHz);
        h1h2Db = h1 - h2;

        // Per-register evidence using 4 features.
        // Pitch zones (center, sigma in Hz):
        //   chest 180/120, mix 380/130, head 650/170, falsetto 800/200, belt 600/200
        // H1-H2 expected (center dB / sigma dB):
        //   chest 1/4, mix 5/4, head 10/4, falsetto 14/5, belt 3/4
        // Spectral tilt expected (dB):
        //   chest +8/5, mix +3/5, head -2/5, falsetto -8/4, belt 0/5
        // SF ratio expected:
        //   chest 0.05/0.05, mix 0.08/0.05, head 0.05/0.04, falsetto 0.03/0.03, belt 0.18/0.06
        float[][] musP = { {180f,120f}, {380f,130f}, {650f,170f}, {800f,200f}, {600f,200f} };
        float[][] musT = { {  8f,  5f}, {  3f,  5f}, { -2f,  5f}, { -8f,  4f}, {  0f,  5f} };
        float[][] musH = { {  1f,  4f}, {  5f,  4f}, { 10f,  4f}, { 14f,  5f}, {  3f,  4f} };
        float[][] musS = { {0.05f,0.05f},{0.08f,0.05f},{0.05f,0.04f},{0.03f,0.03f},{0.18f,0.06f} };
        for (int i = 0; i < N_REG; i++) {
            float ep = gauss(currentFreq, musP[i][0], musP[i][1]);
            float et = gauss(spectralTilt, musT[i][0], musT[i][1]);
            float eh = gauss(h1h2Db,        musH[i][0], musH[i][1]);
            float es = gauss(sfRatio,       musS[i][0], musS[i][1]);
            scores[i] = ep * et * eh * es;
        }
        float maxR = 0f;
        for (float r : scores) if (r > maxR) maxR = r;
        if (maxR > 1e-6f) {
            for (int i = 0; i < N_REG; i++) scores[i] /= maxR;
        }
        for (int i = 0; i < N_REG; i++) {
            scoreSmooth[i] += 0.25f * (scores[i] - scoreSmooth[i]);
        }
        bestIdx = 0;
        for (int i = 1; i < N_REG; i++) {
            if (scoreSmooth[i] > scoreSmooth[bestIdx]) bestIdx = i;
        }
    }

    // Magnitude in dB at frequency f via parabolic interpolation
    // around the nearest bin.
    private float peakMagDb(float f, float binHz) {
        if (f <= 0f || f >= sampleRate * 0.5f) return -90f;
        float kF = f / binHz;
        int k = Math.round(kF);
        if (k <= 0 || k >= FFT_HALF - 1) {
            if (k < 0 || k >= FFT_HALF) return -90f;
            return magDb[k];
        }
        // Parabolic interpolation in dB domain.
        float y1 = magDb[k - 1], y2 = magDb[k], y3 = magDb[k + 1];
        float denom = (y1 - 2f * y2 + y3);
        if (Math.abs(denom) < 1e-6f) return y2;
        float p = 0.5f * (y1 - y3) / denom;
        float peak = y2 - 0.25f * (y1 - y3) * p;
        return peak;
    }

    private static float gauss(float x, float c, float s) {
        float d = (x - c) / s;
        return (float) Math.exp(-0.5 * d * d);
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

    private PluginPaint bgPaint, cardPaint, textBright, textDim,
            regBg, regFg;

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
        canvas.drawText("REGISTER DETECTOR", 12f, 16f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText("heuristic - 4-feature evidence", W - 12f, 16f, textDim);

        String big = bestIdx >= 0 ? REG[bestIdx] : "-";
        int bigCol = bestIdx >= 0 ? REG_COLOURS[bestIdx] : COLOR_TEXT_DIM;
        textBright.setColor(bigCol).setTextSize(32f).setTextAlign(0);
        canvas.drawText(big, 12f, 60f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(10f).setTextAlign(2);
        if (currentFreq > 0f) {
            canvas.drawText(String.format("%.0f Hz", currentFreq), W - 12f, 32f, textDim);
            canvas.drawText(String.format("tilt %+.1f dB", spectralTilt), W - 12f, 45f, textDim);
            canvas.drawText(String.format("H1-H2 %+.1f dB", h1h2Db), W - 12f, 58f, textDim);
            canvas.drawText(String.format("ring %.0f%%", sfRatio * 100f), W - 12f, 71f, textDim);
        }

        float barAreaY0 = 80f;
        float barAreaY1 = H - 14f;
        float barW = (W - 24f) / N_REG - 8f;
        for (int i = 0; i < N_REG; i++) {
            float x0 = 12f + i * ((W - 24f) / N_REG) + 4f;
            float x1 = x0 + barW;
            float v = scoreSmooth[i];
            regBg.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, barAreaY0, x1, barAreaY1 - 14f, 4f, regBg);
            regBg.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(x0, barAreaY0, x1, barAreaY1 - 14f, 4f, regBg);
            float fY = barAreaY1 - 14f - v * ((barAreaY1 - 14f) - barAreaY0);
            int col = REG_COLOURS[i];
            if (i != bestIdx) col = (col & 0x00FFFFFF) | 0x88000000;
            regFg.setColor(col).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, fY, x1, barAreaY1 - 14f, 4f, regFg);
            textDim.setColor(i == bestIdx ? REG_COLOURS[i] : COLOR_TEXT_DIM)
                    .setTextSize(9f).setTextAlign(1);
            canvas.drawText(REG[i], (x0 + x1) * 0.5f, barAreaY1 - 1f, textDim);
        }
    }

    private void initPaints(PluginCanvas c) {
        bgPaint    = c.newPaint();
        cardPaint  = c.newPaint();
        textBright = c.newPaint();
        textDim    = c.newPaint();
        regBg      = c.newPaint();
        regFg      = c.newPaint();
    }
}
