package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Scale Runner — sing-back drill on a C major scale.
 *
 * The plugin cycles a ping-pong scale (C4 D4 E4 F4 G4 A4 B4 C5 then
 * back down).  Each step lasts `stepSec` seconds, during which it:
 *   - plays a soft sine tone at the target Hz in the output buffer
 *   - estimates the input pitch
 *   - if the user's pitch sits within ±50 cents for at least 250 ms
 *     of the step, the step gets a green tick and stays ticked for
 *     the rest of the loop
 *
 * Visual: 8 note cells in a row, current cell highlighted in yellow,
 * ticked cells in green, untouched cells grey.  Big readout of the
 * current target + your pitch.
 */
public final class ScaleRunner implements VocalMonitorVisualPlugin {

    private static final int N_STEPS = 15;          // up 8 + down 7
    private static final int[] MIDI_SEQ = {
        60, 62, 64, 65, 67, 69, 71, 72,             // C4..C5 ascending
        71, 69, 67, 65, 64, 62, 60                  // descending
    };
    private static final String[] NAME_SEQ = {
        "C4","D4","E4","F4","G4","A4","B4","C5",
        "B4","A4","G4","F4","E4","D4","C4"
    };

    private int sampleRate = 44100;
    private float lpAlpha;
    private float lpPrev = 0f;
    private float smoothedPitchHz = 0f;
    private float smoothedRms = 0f;
    private double tonePhase = 0.0;
    private int stepIdx = 0;
    private float stepTime = 0f;
    private float matchTime = 0f;
    private float stepSec = 2.0f;
    private float toneLevel = 0.12f;
    private final boolean[] ticked = new boolean[N_STEPS];
    private long lastRenderMs = -1L;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        this.lpAlpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * 800.0 / sr));
        lpPrev = 0f;
        smoothedPitchHz = 0f;
        smoothedRms = 0f;
        tonePhase = 0.0;
        stepIdx = 0;
        stepTime = 0f;
        matchTime = 0f;
        for (int i = 0; i < N_STEPS; i++) ticked[i] = false;
    }

    @Override public String[] parameterNames() {
        return new String[] { "stepSec", "toneLevel" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "stepSec":   return 0.8f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "stepSec":   return 4f;
            default:          return 0.5f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "stepSec":   return 2f;
            case "toneLevel": return 0.12f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "stepSec":   return "Step (s)";
            case "toneLevel": return "Tone vol";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "stepSec":   stepSec = v; break;
            case "toneLevel": toneLevel = v; break;
        }
    }

    private float targetHz() {
        int midi = MIDI_SEQ[stepIdx];
        return (float) (440.0 * Math.pow(2.0, (midi - 69) / 12.0));
    }

    @Override
    public void process(float[] input, float[] output) {
        // Audio effect: emit the reference tone mixed with dry signal.
        // Pitch detection + step advance live in feedLive(), driven by
        // the host's wall-clock dt so slim's live monitor behaves the
        // same as DAW playback.
        final double twoPi    = 2.0 * Math.PI;
        final float t = targetHz();
        final double phaseInc = twoPi * t / sampleRate;
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float tone = (float) (Math.sin(tonePhase) * toneLevel);
            tonePhase += phaseInc;
            if (tonePhase > twoPi) tonePhase -= twoPi;
            output[i] = (float) Math.tanh(tone + input[i] * 0.6f);
        }
    }

    private void feedLive(Map<String, float[]> streams, long timeMs) {
        float dt = (lastRenderMs < 0) ? 0.016f
            : Math.min(0.10f, (timeMs - lastRenderMs) / 1000f);
        lastRenderMs = timeMs;
        final float t = targetHz();

        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave != null && wave.length >= 16) {
            int upwardZc = 0;
            boolean wasPositive = lpPrev >= 0f;
            double sumSq = 0.0;
            for (int i = 0; i < wave.length; i++) {
                lpPrev += lpAlpha * (wave[i] - lpPrev);
                boolean isPositive = lpPrev >= 0f;
                if (isPositive && !wasPositive) upwardZc++;
                wasPositive = isPositive;
                sumSq += wave[i] * wave[i];
            }
            float rms = (float) Math.sqrt(sumSq / wave.length);
            smoothedRms += 0.30f * (rms - smoothedRms);
            if (rms > 0.008f) {
                float duration = wave.length / (float) sampleRate;
                float p = upwardZc / duration;
                if (p < 60f)  p = 60f;
                if (p > 800f) p = 800f;
                smoothedPitchHz += 0.30f * (p - smoothedPitchHz);
            } else {
                smoothedPitchHz *= 0.85f;
            }
        } else {
            smoothedPitchHz *= 0.85f;
        }

        stepTime += dt;
        if (smoothedPitchHz > 50f && smoothedRms > 0.005f) {
            float cents = (float)(1200.0 * Math.log(smoothedPitchHz / t) / Math.log(2.0));
            if (Math.abs(cents) <= 50f) matchTime += dt;
        }
        if (matchTime > 0.25f) {
            ticked[stepIdx] = true;
        }
        if (stepTime >= stepSec) {
            stepTime = 0f;
            matchTime = 0f;
            stepIdx++;
            if (stepIdx >= N_STEPS) {
                stepIdx = 0;
                for (int i = 0; i < N_STEPS; i++) ticked[i] = false;
            }
        }
    }

    @Override
    public void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    ) {
        feedLive(streams, timeMs);
        float scale = Math.min(width, height) / 360f;
        PluginPaint bg = canvas.newPaint();
        bg.setColor(0xFF101418);
        canvas.drawRect(0, 0, width, height, bg);

        // Heading + step progress bar.
        PluginPaint title = canvas.newPaint();
        title.setColor(0xFFCFCFCF);
        title.setTextSize(20f * scale);
        title.setTextAlign(1);
        canvas.drawText("SCALE RUNNER  ·  C major", width / 2f, 32f, title);

        // Step countdown bar at the top.
        float topBarY = 50f;
        float pad = 24f;
        PluginPaint topBg = canvas.newPaint();
        topBg.setColor(0xFF222630);
        canvas.drawRoundRect(pad, topBarY, width - pad, topBarY + 6, 3f, topBg);
        float progress = Math.min(1f, stepTime / Math.max(0.1f, stepSec));
        PluginPaint topFg = canvas.newPaint();
        topFg.setColor(0xFFFFD66B);
        canvas.drawRoundRect(pad, topBarY,
            pad + (width - pad * 2) * progress, topBarY + 6, 3f, topFg);

        // Note cells — show the 8 ascending notes; descending re-uses
        // the same cells (the highlight just moves backward).
        int cells = 8;
        float cellW = (width - pad * 2) / (float) cells;
        float cellH = 64f;
        float cellY0 = height * 0.45f - cellH / 2f;
        int displayIdx = stepIdx < 8 ? stepIdx : (15 - stepIdx);
        for (int i = 0; i < cells; i++) {
            float x0 = pad + i * cellW;
            boolean isCurrent = i == displayIdx;
            boolean isTicked = ticked[i] || (stepIdx >= 8 && ticked[15 - stepIdx] && i == displayIdx);
            int fill = isCurrent ? 0xFFFFD66B : (isTicked ? 0xFF66DD66 : 0xFF22262C);
            PluginPaint cell = canvas.newPaint();
            cell.setColor(fill);
            if (isCurrent) cell.setGlow(0xFFFFD66B, 12f);
            canvas.drawRoundRect(x0 + 4, cellY0, x0 + cellW - 4, cellY0 + cellH, 10f, cell);
            // Name.
            PluginPaint nm = canvas.newPaint();
            nm.setColor(isCurrent || isTicked ? 0xFF101418 : 0xFFCFCFCF);
            nm.setTextSize(20f * scale);
            nm.setTextAlign(1);
            canvas.drawText(NAME_SEQ[i], x0 + cellW / 2f, cellY0 + cellH / 2f + 7, nm);
            // Tick mark.
            if (isTicked && !isCurrent) {
                PluginPaint tickP = canvas.newPaint();
                tickP.setColor(0xFF101418);
                tickP.setStyle(PluginStyle.STROKE);
                tickP.setStrokeWidth(3f * scale);
                float tcx = x0 + cellW - 18f;
                float tcy = cellY0 + 14f;
                PluginPath tk = canvas.newPath();
                tk.moveTo(tcx - 8f, tcy);
                tk.lineTo(tcx - 2f, tcy + 6f);
                tk.lineTo(tcx + 6f, tcy - 6f);
                canvas.drawPath(tk, tickP);
            }
        }

        // Current target + user readout.
        float tHz = targetHz();
        PluginPaint sub = canvas.newPaint();
        sub.setColor(0xFFFFD66B);
        sub.setTextSize(28f * scale);
        sub.setTextAlign(1);
        canvas.drawText("Now: " + NAME_SEQ[stepIdx < 8 ? stepIdx : (15 - stepIdx)] +
            "  (" + Math.round(tHz) + " Hz)", width / 2f, height * 0.72f, sub);

        boolean voiced = smoothedPitchHz > 50f && smoothedRms > 0.005f;
        if (voiced) {
            float cents = (float)(1200.0 * Math.log(smoothedPitchHz / tHz) / Math.log(2.0));
            boolean inTune = Math.abs(cents) <= 50f;
            PluginPaint you = canvas.newPaint();
            you.setColor(inTune ? 0xFF66DD66 : 0xFFE25656);
            you.setTextSize(20f * scale);
            you.setTextAlign(1);
            canvas.drawText(
                "You: " + Math.round(smoothedPitchHz) + " Hz  (" +
                    (cents > 0 ? "+" : "") + Math.round(cents) + " cents)",
                width / 2f, height * 0.82f, you);
        } else {
            PluginPaint you = canvas.newPaint();
            you.setColor(0xFF888888);
            you.setTextSize(18f * scale);
            you.setTextAlign(1);
            canvas.drawText("Sing the note…", width / 2f, height * 0.82f, you);
        }
    }
}
