package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Balloon Blow — voice-driven inflation game.
 *
 * Audio side: a fast-attack / slow-release envelope follower on the
 * input.  When the envelope sits above a small floor the balloon
 * inflates (target size = envelope × gain); when the user goes
 * silent it deflates exponentially.  Hitting 100 % triggers a pop:
 * 80 confetti particles fly out at randomised angles + velocities
 * and animate with simple Euler integration + gravity for 1.5 s,
 * then a fresh balloon spawns.
 *
 * The size update happens in process() (audio thread), animation
 * lives in render() (UI thread).  Particles use plain float arrays
 * so we don't allocate per-frame.
 */
public final class BalloonBlow implements VocalMonitorVisualPlugin {

    private int sampleRate = 44100;
    private float envelope = 0f;
    private float size = 0f;            // 0..1
    private long popUntilMs = -1L;
    private long lastRenderMs = -1L;

    private static final int N_CONFETTI = 80;
    private final float[] cx = new float[N_CONFETTI];
    private final float[] cy = new float[N_CONFETTI];
    private final float[] vx = new float[N_CONFETTI];
    private final float[] vy = new float[N_CONFETTI];
    private final int[]   cc = new int[N_CONFETTI];
    // PRNG seed (avoid java.util.Random to keep the audio path
    // allocation-free and platform-deterministic).
    private long rngState = 0xC0FFEEDEADL;

    private float nextFloat() {
        // xorshift64 → [0, 1)
        rngState ^= rngState << 13;
        rngState ^= rngState >>> 7;
        rngState ^= rngState << 17;
        return (rngState & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    }

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        envelope = 0f; size = 0f;
        popUntilMs = -1L;
        lastRenderMs = -1L;
    }

    @Override public String[] parameterNames() {
        return new String[] { "sensitivity" };
    }
    @Override public float parameterMin(String n) { return 1f; }
    @Override public float parameterMax(String n) { return 20f; }
    @Override public float parameterDefault(String n) { return 6f; }
    @Override public String parameterLabel(String n) { return "Sensitivity"; }
    private float sensitivity = 6f;
    @Override public void setParameter(String n, float v) {
        if ("sensitivity".equals(n)) sensitivity = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        // Pure pass-through.  Slim's live-monitor path doesn't call
        // this with the mic — visual state is driven from render() via
        // streams["waveform"].  Save-time export does call process(),
        // so we just pass the signal through untouched (game plugin,
        // no audio effect).
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) output[i] = input[i];
    }

    /** Pull the latest mic chunk and step envelope + balloon size.
     *  Called once per render frame (~60 Hz) — perceptually fine for
     *  an inflation game. */
    private void feedLive(Map<String, float[]> streams) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length == 0) {
            size *= 0.995f;
            return;
        }
        double sumSq = 0.0;
        for (int i = 0; i < wave.length; i++) sumSq += wave[i] * wave[i];
        float rms = (float) Math.sqrt(sumSq / wave.length);
        float att = 0.25f, rel = 0.06f;
        if (rms > envelope) envelope += att * (rms - envelope);
        else                envelope += rel * (rms - envelope);
        float target = Math.min(1f, envelope * sensitivity);
        if (target > size) size += (target - size) * 0.18f;
        else               size *= 0.985f;
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
        // Scale text / stroke / padding sizes to the canvas (positions
        // are already width / height-relative).
        float scale = Math.min(width, height) / 360f;

        // Sky-gradient background.
        PluginPaint bg = canvas.newPaint();
        bg.setLinearGradient(0, 0, 0, height,
            new int[] { 0xFF1B2566, 0xFF111933 },
            new float[] { 0f, 1f });
        canvas.drawRect(0, 0, width, height, bg);

        // Star field (deterministic pattern via xorshift of a fixed seed).
        long sStar = 31415926L;
        PluginPaint star = canvas.newPaint();
        star.setColor(0xFFE6E6E6);
        for (int i = 0; i < 80; i++) {
            sStar ^= sStar << 13; sStar ^= sStar >>> 7; sStar ^= sStar << 17;
            float sx = ((sStar & 0xFFFF) / 65535f) * width;
            float sy = (((sStar >>> 16) & 0xFFFF) / 65535f) * height * 0.6f;
            float twinkle = 0.5f + 0.5f * (float) Math.sin(timeMs * 0.003 + i);
            star.setColor(0x66FFFFFF | ((int)(0x99 * twinkle) << 24));
            canvas.drawCircle(sx, sy, 1.5f * scale, star);
        }

        float cxBal = width / 2f;
        float cyBal = height * 0.48f;
        float maxR  = Math.min(width, height) * 0.36f;

        boolean popping = popUntilMs > 0 && timeMs < popUntilMs;
        if (popping) {
            // Animate confetti.
            PluginPaint dot = canvas.newPaint();
            for (int i = 0; i < N_CONFETTI; i++) {
                vy[i] += 400f * dt;            // gravity
                cx[i] += vx[i] * dt;
                cy[i] += vy[i] * dt;
                dot.setColor(cc[i]);
                canvas.drawCircle(cx[i], cy[i], 4f * scale, dot);
            }
            drawHud(canvas, width, height, true, scale);
            return;
        }

        // Trigger pop on full inflation.
        if (size > 0.97f) {
            popUntilMs = timeMs + 1500L;
            for (int i = 0; i < N_CONFETTI; i++) {
                cx[i] = cxBal;
                cy[i] = cyBal;
                double angle = nextFloat() * Math.PI * 2.0;
                float speed = 200f + nextFloat() * 500f;
                vx[i] = (float) (Math.cos(angle) * speed);
                vy[i] = (float) (Math.sin(angle) * speed);
                int r = (int) (nextFloat() * 256);
                int g = (int) (nextFloat() * 256);
                int b = (int) (nextFloat() * 256);
                cc[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            size = 0f;
            return;
        }

        // Balloon body — radial gradient for a hint of sheen.
        float r = 24f + size * (maxR - 24f);
        PluginPaint balloon = canvas.newPaint();
        balloon.setRadialGradient(
            cxBal - r * 0.3f, cyBal - r * 0.3f, r * 1.4f,
            new int[] { 0xFFFFAACC, 0xFFEE5577, 0xFFCC3355 },
            new float[] { 0f, 0.6f, 1f }
        );
        canvas.drawCircle(cxBal, cyBal, r, balloon);

        // Knot at the bottom (small triangle).
        PluginPath tail = canvas.newPath();
        tail.moveTo(cxBal, cyBal + r);
        tail.lineTo(cxBal - r * 0.12f, cyBal + r + 16f);
        tail.lineTo(cxBal + r * 0.12f, cyBal + r + 16f);
        tail.close();
        PluginPaint tailP = canvas.newPaint();
        tailP.setColor(0xFFAA3344);
        canvas.drawPath(tail, tailP);

        // String dangling down — wiggly with time.
        PluginPath string = canvas.newPath();
        string.moveTo(cxBal, cyBal + r + 16f);
        for (int i = 1; i <= 12; i++) {
            float ty = cyBal + r + 16f + i * 14f;
            float wig = (float) Math.sin(timeMs * 0.004 + i * 0.6) * (i * 1.5f);
            string.lineTo(cxBal + wig, ty);
        }
        PluginPaint strP = canvas.newPaint();
        strP.setColor(0xFFBBBBBB);
        strP.setStyle(PluginStyle.STROKE);
        strP.setStrokeWidth(1.5f * scale);
        canvas.drawPath(string, strP);

        // Cute face — eyes + smile.
        PluginPaint eye = canvas.newPaint();
        eye.setColor(0xFF221122);
        canvas.drawCircle(cxBal - r * 0.28f, cyBal - r * 0.15f, r * 0.08f, eye);
        canvas.drawCircle(cxBal + r * 0.28f, cyBal - r * 0.15f, r * 0.08f, eye);
        PluginPaint smile = canvas.newPaint();
        smile.setColor(0xFF221122);
        smile.setStyle(PluginStyle.STROKE);
        smile.setStrokeWidth(2.5f * scale);
        PluginPath smilePath = canvas.newPath();
        smilePath.moveTo(cxBal - r * 0.18f, cyBal + r * 0.12f);
        smilePath.quadTo(cxBal, cyBal + r * 0.30f, cxBal + r * 0.18f, cyBal + r * 0.12f);
        canvas.drawPath(smilePath, smile);

        // Highlight blob.
        PluginPaint hi = canvas.newPaint();
        hi.setColor(0x99FFFFFF);
        canvas.drawCircle(cxBal - r * 0.4f, cyBal - r * 0.45f, r * 0.18f, hi);

        drawHud(canvas, width, height, false, scale);
    }

    private void drawHud(PluginCanvas canvas, int width, int height, boolean popping, float scale) {
        float pad = 24f * scale;
        float barH = 16f * scale;
        float barY0 = height - 40f * scale, barY1 = barY0 + barH;
        float barX0 = pad, barX1 = width - pad;
        PluginPaint barBg = canvas.newPaint();
        barBg.setColor(0xFF223344);
        canvas.drawRoundRect(barX0, barY0, barX1, barY1, 8f * scale, barBg);
        float fillX = barX0 + (barX1 - barX0) * size;
        int barColor = size > 0.85f ? 0xFFE25656 : (size > 0.6f ? 0xFFE3B544 : 0xFF66CC66);
        PluginPaint barFg = canvas.newPaint();
        barFg.setColor(barColor);
        canvas.drawRoundRect(barX0, barY0, fillX, barY1, 8f * scale, barFg);

        PluginPaint label = canvas.newPaint();
        label.setColor(0xFFFFFFFF);
        label.setTextSize(20f * scale);
        label.setTextAlign(1);
        String msg = popping ? "POP!" : (size > 0.85f ? "Easy…" : "Sing!");
        canvas.drawText(msg, width / 2f, 36f * scale, label);
    }
}
