package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Pitch Arrow — direction-only training helper.
 *
 * User picks a target pitch (default A4 = 440 Hz).  Plugin estimates
 * input pitch via LP-then-upward-zero-crossings (same cheap method
 * the rocket-pitch game uses) and draws a giant up/down arrow
 * pointing the way the user should slide.  Arrow turns green inside
 * ±20 cents.  No actual cent-precision claim — this is "high, low,
 * or right on it?" feedback for kids learning pitch control.
 */
public final class PitchArrow implements VocalMonitorVisualPlugin {

    private int sampleRate = 44100;
    private float lpAlpha;
    private float lpPrev = 0f;
    private float smoothedPitchHz = 0f;
    private float smoothedRms = 0f;
    private float targetHz = 440f;       // A4
    private float tolCents = 20f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        this.lpAlpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * 800.0 / sr));
        lpPrev = 0f;
        smoothedPitchHz = 0f;
        smoothedRms = 0f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "target", "tolerance" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "target":    return 80f;
            case "tolerance": return 5f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "target":    return 800f;
            case "tolerance": return 100f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "target":    return 440f;
            case "tolerance": return 20f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "target":    return "Target Hz";
            case "tolerance": return "Tol. cents";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "target":    targetHz = v; break;
            case "tolerance": tolCents = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        // Passthrough — slim's live monitor delivers the mic to
        // render() via streams["waveform"], not through here.
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) output[i] = input[i];
    }

    private void feedLive(Map<String, float[]> streams) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 16) {
            smoothedPitchHz *= 0.85f;
            return;
        }
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
    }

    @Override
    public void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    ) {
        feedLive(streams);
        // Background.
        PluginPaint bg = canvas.newPaint();
        bg.setColor(0xFF101418);
        canvas.drawRect(0, 0, width, height, bg);

        boolean voiced = smoothedPitchHz > 50f && smoothedRms > 0.005f;
        float cents = voiced
            ? (float) (1200.0 * Math.log(smoothedPitchHz / targetHz) / Math.log(2.0))
            : 0f;
        boolean onTarget = voiced && Math.abs(cents) <= tolCents;
        int color =
            !voiced     ? 0xFF555566 :
            onTarget    ? 0xFF66DD66 :
            Math.abs(cents) < tolCents * 2.5f ? 0xFFE3B544 : 0xFFE25656;

        // Target heading.
        PluginPaint titleP = canvas.newPaint();
        titleP.setColor(0xFFCFCFCF);
        titleP.setTextSize(22f);
        titleP.setTextAlign(1);
        canvas.drawText("Target: " + noteName(targetHz) +
            "  (" + Math.round(targetHz) + " Hz)",
            width / 2f, 40f, titleP);

        // Giant directional arrow.
        float cx = width / 2f;
        float cy = height / 2f;
        float arrowSize = Math.min(width, height) * 0.35f;

        if (!voiced) {
            // Idle — show a hint instead of an arrow.
            PluginPaint hintP = canvas.newPaint();
            hintP.setColor(0xFF888888);
            hintP.setTextSize(32f);
            hintP.setTextAlign(1);
            canvas.drawText("Sing a note", cx, cy, hintP);
        } else if (onTarget) {
            // Tick / circle = on target.
            PluginPaint disc = canvas.newPaint();
            disc.setColor(color);
            disc.setGlow(color, 24f);
            canvas.drawCircle(cx, cy, arrowSize * 0.55f, disc);
            PluginPaint check = canvas.newPaint();
            check.setColor(0xFF101418);
            check.setStyle(PluginStyle.STROKE);
            check.setStrokeWidth(arrowSize * 0.08f);
            PluginPath tick = canvas.newPath();
            tick.moveTo(cx - arrowSize * 0.28f, cy);
            tick.lineTo(cx - arrowSize * 0.05f, cy + arrowSize * 0.22f);
            tick.lineTo(cx + arrowSize * 0.32f, cy - arrowSize * 0.22f);
            canvas.drawPath(tick, check);
        } else {
            // Arrow pointing up = sing higher; down = sing lower.
            boolean up = cents < 0;     // user is flat → sing higher
            PluginPath arr = canvas.newPath();
            float halfW = arrowSize * 0.6f;
            float halfH = arrowSize * 0.7f;
            if (up) {
                arr.moveTo(cx,           cy - halfH);
                arr.lineTo(cx + halfW,   cy);
                arr.lineTo(cx + halfW*0.4f, cy);
                arr.lineTo(cx + halfW*0.4f, cy + halfH);
                arr.lineTo(cx - halfW*0.4f, cy + halfH);
                arr.lineTo(cx - halfW*0.4f, cy);
                arr.lineTo(cx - halfW,   cy);
                arr.close();
            } else {
                arr.moveTo(cx,           cy + halfH);
                arr.lineTo(cx + halfW,   cy);
                arr.lineTo(cx + halfW*0.4f, cy);
                arr.lineTo(cx + halfW*0.4f, cy - halfH);
                arr.lineTo(cx - halfW*0.4f, cy - halfH);
                arr.lineTo(cx - halfW*0.4f, cy);
                arr.lineTo(cx - halfW,   cy);
                arr.close();
            }
            PluginPaint arrP = canvas.newPaint();
            arrP.setColor(color);
            arrP.setGlow(color, 18f);
            canvas.drawPath(arr, arrP);
        }

        // Cents readout under the arrow.
        PluginPaint cReadout = canvas.newPaint();
        cReadout.setColor(color);
        cReadout.setTextSize(28f);
        cReadout.setTextAlign(1);
        String label = !voiced ? "—"
            : (onTarget ? "On target!" :
               (cents > 0 ? "+" : "") + Math.round(cents) + " cents");
        canvas.drawText(label, cx, cy + arrowSize * 0.85f, cReadout);

        // Your-pitch readout.
        PluginPaint userP = canvas.newPaint();
        userP.setColor(0xFFCFCFCF);
        userP.setTextSize(16f);
        userP.setTextAlign(1);
        String youHz = voiced ? Math.round(smoothedPitchHz) + " Hz · " + noteName(smoothedPitchHz)
                              : "(silence)";
        canvas.drawText("You: " + youHz, cx, height - 28f, userP);
    }

    /** Crude Hz → note-name converter (sharps only).  C4 = MIDI 60. */
    private static String noteName(float hz) {
        if (hz < 20f) return "—";
        double midi = 69.0 + 12.0 * Math.log(hz / 440.0) / Math.log(2.0);
        int m = (int) Math.round(midi);
        int octave = (m / 12) - 1;
        String[] names = { "C","C#","D","D#","E","F","F#","G","G#","A","A#","B" };
        int idx = ((m % 12) + 12) % 12;
        return names[idx] + octave;
    }
}
