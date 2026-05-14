package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Register Detector — heuristic register classifier from three
 * cheap acoustic correlates:
 *
 *   - Pitch (Hz)            : low / mid / high range
 *   - Spectral tilt (dB/oct): negative (warm chest) vs flat (mix) vs
 *                              steep negative (light head / falsetto)
 *   - Singer's-formant ratio: high → engaged "ring" (belt / loud mix)
 *
 * Five categories: CHEST, MIX, HEAD, FALSETTO, BELT.  The "best
 * fit" is shown as the highlighted label; the bar chart shows the
 * confidence for each category so transitions are readable.
 */
public final class RegisterDetector
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    private int sampleRate = 44100;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        java.util.Arrays.fill(audioRing, 0f);
        for (int j = 0; j < 4; j++) { sfState[j] = 0f; loState[j] = 0f; hiState[j] = 0f; }
        sfEnv = loEnv = hiEnv = 0f;
        ringW = 0; sampleAcc = 0;
        java.util.Arrays.fill(scores, 0f);
        java.util.Arrays.fill(scoreSmooth, 0f);
        bestIdx = -1;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n) { return 0f; }
    @Override public float parameterMax(String n) { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) { return n; }
    @Override public void setParameter(String n, float v) { }

    // YIN config.
    private static final int ANALYSIS_SIZE = 1024;
    private static final int ANALYSIS_HOP  = 512;
    private static final int LAG_MIN = 32, LAG_MAX = 512;
    private static final float YIN_THRESHOLD = 0.15f;
    private final float[] audioRing = new float[ANALYSIS_SIZE];
    private final float[] yinBuf = new float[ANALYSIS_SIZE];
    private final float[] yinDiff = new float[LAG_MAX + 1];
    private final float[] yinCMND = new float[LAG_MAX + 1];
    private int ringW = 0, sampleAcc = 0;

    // Spectral tilt: ratio of low-band (300–1k) to high-band (3–8k)
    // RMS envelopes from cheap bandpass biquads.
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

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        if (sfCoefs == null) {
            sfCoefs = bandpass(3000f, 4.0f, sampleRate);
            loCoefs = bandpass(600f,  1.0f, sampleRate);
            hiCoefs = bandpass(5000f, 1.0f, sampleRate);
        }
        float att = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.020));
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            audioRing[ringW] = s;
            ringW = (ringW + 1) % ANALYSIS_SIZE;
            float ys = biquad(s, sfCoefs, sfState);
            float yl = biquad(s, loCoefs, loState);
            float yh = biquad(s, hiCoefs, hiState);
            sfEnv += att * ((ys < 0 ? -ys : ys) - sfEnv);
            loEnv += att * ((yl < 0 ? -yl : yl) - loEnv);
            hiEnv += att * ((yh < 0 ? -yh : yh) - hiEnv);
            totalEnv += att * ((s < 0 ? -s : s) - totalEnv);
            sampleAcc++;
            if (sampleAcc >= ANALYSIS_HOP) {
                sampleAcc = 0;
                analyseFrame();
            }
        }
    }

    private void analyseFrame() {
        double energy = 0;
        for (int i = 0; i < ANALYSIS_SIZE; i++) {
            int idx = (ringW + i) % ANALYSIS_SIZE;
            float v = audioRing[idx];
            yinBuf[i] = v;
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / ANALYSIS_SIZE);
        if (rms < 0.003f) {
            // Unvoiced — slowly decay all scores toward 0.
            for (int i = 0; i < N_REG; i++) scoreSmooth[i] *= 0.85f;
            bestIdx = -1;
            return;
        }
        int half = ANALYSIS_SIZE / 2;
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
        // Spectral tilt: log ratio low/high (positive = chesty, negative = airy).
        spectralTilt = (float)(20.0 * Math.log10(Math.max(1e-6f, loEnv) / Math.max(1e-6f, hiEnv)));
        // Singer's formant ratio.
        sfRatio = totalEnv > 1e-6f ? sfEnv / totalEnv : 0f;

        // Score each register based on Hz / tilt / sf.
        // Heuristics are deliberately wide so transitions read clearly.
        float[] raw = scores;
        for (int i = 0; i < N_REG; i++) raw[i] = 0f;
        // CHEST: low Hz (<300), strong tilt (>+8 dB), moderate sf.
        raw[0] = gauss(currentFreq, 180f, 120f)
               * sigmoid((spectralTilt - 4f) / 4f) * 1.2f;
        // MIX: middle Hz (250-600), flat tilt (-2..+5), moderate sf.
        raw[1] = gauss(currentFreq, 400f, 150f)
               * gauss(spectralTilt, 2f, 6f) * 1.1f;
        // HEAD: high Hz (>500), tilt slightly negative (-4..+2), low-mid sf.
        raw[2] = gauss(currentFreq, 700f, 180f)
               * gauss(spectralTilt, -2f, 5f) * 1.0f;
        // FALSETTO: very high Hz, very negative tilt (-10..-3), low sf.
        raw[3] = gauss(currentFreq, 800f, 200f)
               * sigmoid(-(spectralTilt + 3f) / 3f)
               * sigmoid((0.05f - sfRatio) * 30f);
        // BELT: high Hz AND high sfRatio (engaged ring).
        raw[4] = gauss(currentFreq, 700f, 220f)
               * sigmoid((sfRatio - 0.12f) * 40f) * 1.3f;
        // Normalise so the strongest score = 1.0.
        float maxR = 0f;
        for (float r : raw) if (r > maxR) maxR = r;
        if (maxR > 1e-3f) {
            for (int i = 0; i < N_REG; i++) raw[i] /= maxR;
        }
        // Smooth toward target for visual stability.
        for (int i = 0; i < N_REG; i++) {
            scoreSmooth[i] += 0.25f * (raw[i] - scoreSmooth[i]);
        }
        // Best fit.
        bestIdx = 0;
        for (int i = 1; i < N_REG; i++) {
            if (scoreSmooth[i] > scoreSmooth[bestIdx]) bestIdx = i;
        }
    }

    private static float gauss(float x, float c, float s) {
        float d = (x - c) / s;
        return (float) Math.exp(-0.5 * d * d);
    }
    private static float sigmoid(float x) {
        return 1f / (1f + (float) Math.exp(-x));
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
        float W = width, H = height;
        bgPaint.setColor(COLOR_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bgPaint);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(12f).setTextAlign(0);
        canvas.drawText("REGISTER DETECTOR", 12f, 16f, textBright);

        // Big register label + Hz/tilt/sf strip on the right.
        String big = bestIdx >= 0 ? REG[bestIdx] : "-";
        int bigCol = bestIdx >= 0 ? REG_COLOURS[bestIdx] : COLOR_TEXT_DIM;
        textBright.setColor(bigCol).setTextSize(32f).setTextAlign(0);
        canvas.drawText(big, 12f, 60f, textBright);
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(10f).setTextAlign(2);
        if (currentFreq > 0f) {
            canvas.drawText(String.format("%.0f Hz", currentFreq), W - 12f, 36f, textDim);
            canvas.drawText(String.format("tilt %+.1f dB", spectralTilt), W - 12f, 50f, textDim);
            canvas.drawText(String.format("ring %.0f%%", sfRatio * 100f), W - 12f, 64f, textDim);
        }

        // 5 confidence bars at the bottom.
        float barAreaY0 = 80f;
        float barAreaY1 = H - 14f;
        float barW = (W - 24f) / N_REG - 8f;
        for (int i = 0; i < N_REG; i++) {
            float x0 = 12f + i * ((W - 24f) / N_REG) + 4f;
            float x1 = x0 + barW;
            float v = scoreSmooth[i];
            // Background.
            regBg.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, barAreaY0, x1, barAreaY1 - 14f, 4f, regBg);
            regBg.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(x0, barAreaY0, x1, barAreaY1 - 14f, 4f, regBg);
            // Fill from bottom.
            float fY = barAreaY1 - 14f - v * ((barAreaY1 - 14f) - barAreaY0);
            int col = REG_COLOURS[i];
            if (i != bestIdx) col = (col & 0x00FFFFFF) | 0x88000000;
            regFg.setColor(col).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x0, fY, x1, barAreaY1 - 14f, 4f, regFg);
            // Label below.
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
