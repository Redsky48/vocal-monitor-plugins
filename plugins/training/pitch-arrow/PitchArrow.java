package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;
import com.vocalmonitor.plugin.gamekit.audio.NoteName;
import com.vocalmonitor.plugin.gamekit.audio.PitchTracker;

import java.util.Map;

/**
 * Pitch Arrow — direction-only training helper.
 *
 * User picks a target pitch (default A4 = 440 Hz).  Plugin shows a
 * giant up/down arrow pointing the way the user should slide.  Arrow
 * turns green inside ±tolCents cents.
 *
 * Built on top of PluginGameKit's {@link PitchTracker} (LP +
 * upward-zero-crossings + smoothing) and {@link NoteName} (Hz →
 * "A4" / cents-from-target) — same pitch algo as every other
 * training plugin in this repo, but now in one place.
 */
public final class PitchArrow implements VocalMonitorVisualPlugin {

    private final PitchTracker pitch = new PitchTracker();
    private float targetHz = 440f;
    private float tolCents = 20f;

    @Override
    public void init(int sr) {
        pitch.setSampleRate(sr).reset();
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
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) output[i] = input[i];
    }

    @Override
    public void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    ) {
        pitch.feed(streams, 0.016f);
        float scale = Math.min(width, height) / 360f;
        PluginPaint bg = canvas.newPaint();
        bg.setColor(0xFF101418);
        canvas.drawRect(0, 0, width, height, bg);

        boolean voiced = pitch.voiced();
        float cents = voiced ? pitch.centsFrom(targetHz) : 0f;
        boolean onTarget = voiced && Math.abs(cents) <= tolCents;
        int color =
            !voiced     ? 0xFF555566 :
            onTarget    ? 0xFF66DD66 :
            Math.abs(cents) < tolCents * 2.5f ? 0xFFE3B544 : 0xFFE25656;

        // Target heading.
        PluginPaint titleP = canvas.newPaint();
        titleP.setColor(0xFFCFCFCF);
        titleP.setTextSize(22f * scale);
        titleP.setTextAlign(1);
        canvas.drawText("Target: " + NoteName.of(targetHz) +
            "  (" + Math.round(targetHz) + " Hz)",
            width / 2f, 40f, titleP);

        float cx = width / 2f;
        float cy = height / 2f;
        float arrowSize = Math.min(width, height) * 0.35f;

        if (!voiced) {
            PluginPaint hintP = canvas.newPaint();
            hintP.setColor(0xFF888888);
            hintP.setTextSize(32f * scale);
            hintP.setTextAlign(1);
            canvas.drawText("Sing a note", cx, cy, hintP);
        } else if (onTarget) {
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
        cReadout.setTextSize(28f * scale);
        cReadout.setTextAlign(1);
        String label = !voiced ? "—"
            : (onTarget ? "On target!" :
               (cents > 0 ? "+" : "") + Math.round(cents) + " cents");
        canvas.drawText(label, cx, cy + arrowSize * 0.85f, cReadout);

        // Your-pitch readout.
        PluginPaint userP = canvas.newPaint();
        userP.setColor(0xFFCFCFCF);
        userP.setTextSize(16f * scale);
        userP.setTextAlign(1);
        String youHz = voiced
            ? Math.round(pitch.hz()) + " Hz · " + NoteName.of(pitch.hz())
            : "(silence)";
        canvas.drawText("You: " + youHz, cx, height - 28f, userP);
    }
}
