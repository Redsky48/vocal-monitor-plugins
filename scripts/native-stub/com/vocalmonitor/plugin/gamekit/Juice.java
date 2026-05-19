package com.vocalmonitor.plugin.gamekit;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;

/**
 * Game-feel "juice" — the small layer of feedback that makes a hit
 * feel like a hit: screen shake, full-screen flashes, expanding
 * impact rings, score popups.
 *
 * Usage pattern:
 *
 *   render() {
 *       juice.update(dt);
 *       canvas.save();
 *       juice.applyShake(canvas);     // before world draw
 *       drawWorld();
 *       canvas.restore();
 *       juice.drawOverlay(canvas, w, h);   // flashes + popups on top
 *   }
 *
 *   onHit() {
 *       juice.shake(8f, 0.25f);
 *       juice.flash(0.18f, Palette.HIT_FLASH);
 *       juice.impactRing(x, y, 60f, Palette.HIT_FLASH);
 *       juice.scorePop("+1", x, y, Palette.ACCENT_YELLOW);
 *   }
 *
 * Fixed-size popup / ring pools — never allocates inside the hot
 * loop.  Stateful, one instance per plugin.
 */
public final class Juice {

    // ── Camera shake ────────────────────────────────────────
    private float shakeAmp = 0f;
    private float shakeTimer = 0f;
    private float shakeDur = 0f;
    private float shakeOffX = 0f, shakeOffY = 0f;
    private long shakeRng = 0xC0FFEEL;

    /** Trigger a screen shake of [amplitude] dp for [duration] s.
     *  Subsequent calls take the max amplitude / longest duration. */
    public void shake(float amplitude, float duration) {
        if (amplitude > shakeAmp) shakeAmp = amplitude;
        if (duration > shakeTimer) {
            shakeTimer = duration;
            shakeDur = duration;
        }
    }

    /** Push the current shake offset onto the canvas matrix.  Pair
     *  with `canvas.save()` / `canvas.restore()` around the world
     *  draw — the overlay (flash, popups) shouldn't shake. */
    public void applyShake(PluginCanvas canvas) {
        if (shakeAmp <= 0f || shakeTimer <= 0f) return;
        canvas.translate(shakeOffX, shakeOffY);
    }

    // ── Flash ───────────────────────────────────────────────
    private float flashTimer = 0f;
    private float flashDur = 0f;
    private int flashColor = 0xFFFFFFFF;

    /** Fill the screen with [color] at full alpha, fading to zero
     *  over [duration] seconds.  Use sparingly — overdone, this
     *  becomes annoying. */
    public void flash(float duration, int color) {
        if (duration > flashTimer) {
            flashTimer = duration;
            flashDur = duration;
        }
        this.flashColor = color;
    }

    // ── Impact rings ────────────────────────────────────────
    private static final int MAX_RINGS = 8;
    private final float[] ringX = new float[MAX_RINGS];
    private final float[] ringY = new float[MAX_RINGS];
    private final float[] ringR = new float[MAX_RINGS];
    private final float[] ringR0 = new float[MAX_RINGS];
    private final float[] ringTimer = new float[MAX_RINGS];
    private final float[] ringDur = new float[MAX_RINGS];
    private final int[]   ringColor = new int[MAX_RINGS];

    /** Spawn an expanding ring at (x, y) starting at radius [r0] and
     *  growing to ~3× that over 0.4s while fading out.  Slot-based;
     *  spawning when all 8 slots are alive just overwrites the
     *  oldest one. */
    public void impactRing(float x, float y, float r0, int color) {
        int slot = 0;
        float oldest = Float.MAX_VALUE;
        for (int i = 0; i < MAX_RINGS; i++) {
            if (ringTimer[i] <= 0f) { slot = i; oldest = -1f; break; }
            if (ringTimer[i] < oldest) { oldest = ringTimer[i]; slot = i; }
        }
        ringX[slot] = x; ringY[slot] = y;
        ringR[slot] = r0; ringR0[slot] = r0;
        ringTimer[slot] = 0.4f; ringDur[slot] = 0.4f;
        ringColor[slot] = color;
    }

    // ── Score popups ────────────────────────────────────────
    private static final int MAX_POPS = 8;
    private final float[]  popX = new float[MAX_POPS];
    private final float[]  popY = new float[MAX_POPS];
    private final float[]  popTimer = new float[MAX_POPS];
    private final float[]  popDur = new float[MAX_POPS];
    private final int[]    popColor = new int[MAX_POPS];
    private final String[] popText = new String[MAX_POPS];

    /** Spawn a text popup at (x, y) that floats upward + fades over
     *  0.6s.  Slot-based, oldest-replaced. */
    public void scorePop(String text, float x, float y, int color) {
        int slot = 0;
        float oldest = Float.MAX_VALUE;
        for (int i = 0; i < MAX_POPS; i++) {
            if (popTimer[i] <= 0f) { slot = i; oldest = -1f; break; }
            if (popTimer[i] < oldest) { oldest = popTimer[i]; slot = i; }
        }
        popX[slot] = x; popY[slot] = y;
        popTimer[slot] = 0.6f; popDur[slot] = 0.6f;
        popText[slot] = text;
        popColor[slot] = color;
    }

    // ── Per-frame update ────────────────────────────────────
    public void update(float dt) {
        // Camera shake — Gaussian-ish jitter that decays linearly.
        if (shakeTimer > 0f) {
            shakeTimer -= dt;
            float frac = shakeTimer / Math.max(shakeDur, 0.001f);
            if (frac < 0f) frac = 0f;
            float amp = shakeAmp * frac;
            shakeOffX = (rand() - 0.5f) * 2f * amp;
            shakeOffY = (rand() - 0.5f) * 2f * amp;
            if (shakeTimer <= 0f) { shakeAmp = 0f; shakeOffX = shakeOffY = 0f; }
        }
        // Flash decays.
        if (flashTimer > 0f) flashTimer -= dt;
        // Rings grow.
        for (int i = 0; i < MAX_RINGS; i++) {
            if (ringTimer[i] <= 0f) continue;
            ringTimer[i] -= dt;
            float t = 1f - (ringTimer[i] / ringDur[i]);
            ringR[i] = ringR0[i] * (1f + 3f * Ease.outCubic(t));
        }
        // Popups float + fade.
        for (int i = 0; i < MAX_POPS; i++) {
            if (popTimer[i] <= 0f) continue;
            popTimer[i] -= dt;
            popY[i] -= 50f * dt;   // upward drift
        }
    }

    /** Draw flashes + impact rings + score popups on top of the
     *  world.  Call after the world draw (and after `restore()`-ing
     *  any shake translate). */
    public void drawOverlay(PluginCanvas canvas, int width, int height) {
        if (flashTimer > 0f) {
            float a = flashTimer / Math.max(flashDur, 0.001f);
            if (a < 0f) a = 0f; if (a > 1f) a = 1f;
            PluginPaint p = canvas.newPaint();
            p.setColor(Palette.withAlpha(flashColor, a * 0.85f));
            canvas.drawRect(0, 0, width, height, p);
        }
        for (int i = 0; i < MAX_RINGS; i++) {
            if (ringTimer[i] <= 0f) continue;
            float t = 1f - (ringTimer[i] / ringDur[i]);
            float a = (1f - t);
            Gfx.ring(canvas, ringX[i], ringY[i], ringR[i],
                Math.max(1.5f, 4f * (1f - t)),
                Palette.withAlpha(ringColor[i], a));
        }
        for (int i = 0; i < MAX_POPS; i++) {
            if (popTimer[i] <= 0f) continue;
            float t = 1f - (popTimer[i] / popDur[i]);
            float a = 1f - t;
            Gfx.textCenter(canvas, popText[i], popX[i], popY[i],
                22f + 6f * Ease.outBack(t),
                Palette.withAlpha(popColor[i], a));
        }
    }

    public void reset() {
        shakeAmp = 0f; shakeTimer = 0f; shakeOffX = shakeOffY = 0f;
        flashTimer = 0f;
        for (int i = 0; i < MAX_RINGS; i++) ringTimer[i] = 0f;
        for (int i = 0; i < MAX_POPS;  i++) popTimer[i]  = 0f;
    }

    private float rand() {
        shakeRng ^= shakeRng << 13;
        shakeRng ^= shakeRng >>> 7;
        shakeRng ^= shakeRng << 17;
        return (shakeRng & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    }
}
