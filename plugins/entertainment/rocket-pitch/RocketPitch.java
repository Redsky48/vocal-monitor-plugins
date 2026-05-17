package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Rocket Pitch — voice-controlled rocket flying through a starfield.
 *
 * Pitch estimator: one-pole low-pass at 800 Hz (suppress harmonics
 * so we don't double-count crossings of higher partials) → count
 * upward zero-crossings → divide by block duration.  Cheap, gives a
 * good-enough fundamental for a kid's game (cents-accurate it is
 * NOT; "is the voice going up or down" it absolutely is).
 *
 * Mapping: pitch ∈ [80, 500] Hz → rocket vertical position ∈ [bottom,
 * top].  Volume (RMS) drives flame size.  Heavy temporal smoothing
 * on both so the rocket glides instead of jittering frame-by-frame.
 */
public final class RocketPitch implements VocalMonitorVisualPlugin {

    private int sampleRate = 44100;
    private float lpAlpha;
    private float lpPrev = 0f;
    private float smoothedPitchHz = 200f;
    private float smoothedRms = 0f;
    private float rocketY = 0.7f;        // 0..1 (top..bottom)
    private float flame = 0f;            // 0..1
    private long lastRenderMs = -1L;
    private float scroll = 0f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        this.lpAlpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * 800.0 / sr));
        lpPrev = 0f;
        smoothedPitchHz = 200f;
        smoothedRms = 0f;
        rocketY = 0.7f;
        flame = 0f;
        lastRenderMs = -1L;
        scroll = 0f;
    }

    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n)     { return 0f; }
    @Override public float parameterMax(String n)     { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n)  { return n; }
    @Override public void setParameter(String n, float v) {}

    @Override
    public void process(float[] input, float[] output) {
        // Passthrough — slim's live monitor doesn't drive visual state
        // through here; render() pulls streams["waveform"] instead.
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) output[i] = input[i];
    }

    /** Pull live mic chunk and update pitch + rms + rocket position. */
    private void feedLive(Map<String, float[]> streams) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 16) {
            smoothedPitchHz += 0.01f * (200f - smoothedPitchHz);
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
            smoothedPitchHz += 0.25f * (p - smoothedPitchHz);
        } else {
            smoothedPitchHz += 0.01f * (200f - smoothedPitchHz);
        }
        float norm = (smoothedPitchHz - 80f) / (500f - 80f);
        if (norm < 0f) norm = 0f;
        if (norm > 1f) norm = 1f;
        float targetY = 0.85f - norm * 0.75f;
        rocketY += 0.20f * (targetY - rocketY);
        float flameTarget = Math.min(1f, smoothedRms * 6f);
        flame += 0.25f * (flameTarget - flame);
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
        float dt = (lastRenderMs < 0) ? 0.016f : Math.min(0.05f, (timeMs - lastRenderMs) / 1000f);
        lastRenderMs = timeMs;
        scroll += dt * 60f;                  // pixels/sec
        if (scroll > 4096f) scroll -= 4096f;

        // Sunset sky gradient.
        PluginPaint bg = canvas.newPaint();
        bg.setLinearGradient(0, 0, 0, height,
            new int[] { 0xFF2A1B6E, 0xFFEC5A8C, 0xFFFFB272 },
            new float[] { 0f, 0.55f, 1f });
        canvas.drawRect(0, 0, width, height, bg);

        // Star field scrolling left.
        long sStar = 0xBEEFCAFEL;
        PluginPaint star = canvas.newPaint();
        for (int i = 0; i < 60; i++) {
            sStar ^= sStar << 13; sStar ^= sStar >>> 7; sStar ^= sStar << 17;
            float baseX = ((sStar & 0xFFFF) / 65535f) * (width * 2f);
            float sy = (((sStar >>> 16) & 0xFFFF) / 65535f) * height * 0.55f;
            float sx = (baseX - scroll * 0.5f) % (width * 2f);
            if (sx < 0) sx += width * 2f;
            if (sx > width) continue;
            float tw = 0.4f + 0.6f * (float) Math.sin(timeMs * 0.003 + i);
            star.setColor(0x00FFFFFF | ((int)(0xCC * tw) << 24));
            canvas.drawCircle(sx, sy, 1.7f, star);
        }

        // Drifting clouds (3-blob each).
        long sCloud = 0xDEADBEEFL;
        PluginPaint cloud = canvas.newPaint();
        cloud.setColor(0x66FFFFFF);
        for (int i = 0; i < 7; i++) {
            sCloud ^= sCloud << 13; sCloud ^= sCloud >>> 7; sCloud ^= sCloud << 17;
            float baseX = ((sCloud & 0xFFFF) / 65535f) * (width * 2f);
            float cy = 0.25f * height + (((sCloud >>> 16) & 0xFFFF) / 65535f) * (height * 0.55f);
            float cx = (baseX - scroll * 1.5f) % (width * 2f + 200f);
            if (cx < -200f) cx += width * 2f + 200f;
            if (cx > width + 200f) continue;
            canvas.drawCircle(cx,        cy,        28f, cloud);
            canvas.drawCircle(cx + 32f,  cy - 8f,   22f, cloud);
            canvas.drawCircle(cx - 28f,  cy - 6f,   18f, cloud);
            canvas.drawCircle(cx + 12f,  cy + 12f,  20f, cloud);
        }

        // Rocket position.
        float rx = width * 0.30f;
        float ry = height * rocketY;
        float rocketLen = Math.min(width, height) * 0.18f;
        float rocketW   = rocketLen * 0.45f;

        // Flame (behind the rocket).
        if (flame > 0.02f) {
            PluginPath flamePath = canvas.newPath();
            float tipX = rx - rocketLen * 0.55f - flame * rocketLen * 1.2f;
            float baseX = rx - rocketLen * 0.45f;
            flamePath.moveTo(baseX, ry - rocketW * 0.35f);
            flamePath.quadTo(baseX - flame * 30f, ry, tipX, ry);
            flamePath.quadTo(baseX - flame * 30f, ry, baseX, ry + rocketW * 0.35f);
            flamePath.close();
            PluginPaint flameP = canvas.newPaint();
            flameP.setLinearGradient(tipX, ry, baseX, ry,
                new int[] { 0xFFFFEE66, 0xFFFF8844, 0xFFFF3322 },
                new float[] { 0f, 0.6f, 1f });
            flameP.setGlow(0xFFFF6622, 16f);
            canvas.drawPath(flamePath, flameP);
        }

        // Rocket body — capsule pointing right.
        PluginPath body = canvas.newPath();
        body.moveTo(rx - rocketLen * 0.45f, ry - rocketW * 0.4f);
        body.lineTo(rx + rocketLen * 0.35f, ry - rocketW * 0.4f);
        body.quadTo(rx + rocketLen * 0.55f, ry, rx + rocketLen * 0.35f, ry + rocketW * 0.4f);
        body.lineTo(rx - rocketLen * 0.45f, ry + rocketW * 0.4f);
        body.close();
        PluginPaint bodyP = canvas.newPaint();
        bodyP.setLinearGradient(rx, ry - rocketW * 0.4f, rx, ry + rocketW * 0.4f,
            new int[] { 0xFFEEEEEE, 0xFFBBBBBB, 0xFF888888 },
            new float[] { 0f, 0.5f, 1f });
        canvas.drawPath(body, bodyP);

        // Fins.
        PluginPaint finP = canvas.newPaint();
        finP.setColor(0xFFE25656);
        PluginPath fin1 = canvas.newPath();
        fin1.moveTo(rx - rocketLen * 0.45f, ry - rocketW * 0.4f);
        fin1.lineTo(rx - rocketLen * 0.55f, ry - rocketW * 0.85f);
        fin1.lineTo(rx - rocketLen * 0.25f, ry - rocketW * 0.4f);
        fin1.close();
        canvas.drawPath(fin1, finP);
        PluginPath fin2 = canvas.newPath();
        fin2.moveTo(rx - rocketLen * 0.45f, ry + rocketW * 0.4f);
        fin2.lineTo(rx - rocketLen * 0.55f, ry + rocketW * 0.85f);
        fin2.lineTo(rx - rocketLen * 0.25f, ry + rocketW * 0.4f);
        fin2.close();
        canvas.drawPath(fin2, finP);

        // Cockpit window.
        PluginPaint win = canvas.newPaint();
        win.setColor(0xFF66CCEE);
        canvas.drawCircle(rx + rocketLen * 0.15f, ry, rocketW * 0.18f, win);
        PluginPaint winRim = canvas.newPaint();
        winRim.setColor(0xFF335577);
        winRim.setStyle(PluginStyle.STROKE);
        winRim.setStrokeWidth(2f);
        canvas.drawCircle(rx + rocketLen * 0.15f, ry, rocketW * 0.18f, winRim);

        // HUD — pitch readout.
        PluginPaint hud = canvas.newPaint();
        hud.setColor(0xFFFFFFFF);
        hud.setTextSize(22f);
        hud.setTextAlign(0);   // left
        canvas.drawText(
            "Pitch: " + Math.round(smoothedPitchHz) + " Hz",
            18f, 30f, hud
        );
        // Vertical pitch scale on right edge.
        float scaleX = width - 36f;
        PluginPaint sc = canvas.newPaint();
        sc.setColor(0x55FFFFFF);
        canvas.drawLine(scaleX, height * 0.1f, scaleX, height * 0.85f, sc);
        PluginPaint tick = canvas.newPaint();
        tick.setColor(0xCCFFFFFF);
        tick.setTextSize(11f);
        tick.setTextAlign(2);  // right
        int[] markers = { 100, 200, 300, 400, 500 };
        for (int hz : markers) {
            float norm = (hz - 80f) / (500f - 80f);
            float y = height * (0.85f - norm * 0.75f);
            canvas.drawLine(scaleX - 4f, y, scaleX + 4f, y, tick);
            canvas.drawText(hz + "", scaleX - 8f, y + 4f, tick);
        }
    }
}
