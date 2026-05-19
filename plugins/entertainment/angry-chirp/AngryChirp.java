package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.gamekit.Ease;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;

import java.util.Map;

/**
 * Angry Chirp — voice-controlled Flappy Bird.  Built on top of
 * {@link GamePluginBase} so the boilerplate (mic detector, dt /
 * scale bookkeeping, juice, particles, parameter contract) lives in
 * the kit and this file is just gameplay.
 *
 * Audio flow: {@code mic.hit()} in render() flaps the bird.  Backed
 * by {@code streams["waveform"]} so it works on both DAW and slim
 * live monitor.  Audio output is the dry signal (game plugins don't
 * transform audio).
 */
public final class AngryChirp extends GamePluginBase {

    // ── State machine ───────────────────────────────────────
    private static final int STATE_READY     = 0;
    private static final int STATE_PLAYING   = 1;
    private static final int STATE_GAME_OVER = 2;
    private int state = STATE_READY;

    // ── Bird ────────────────────────────────────────────────
    private float birdY = 0.4f;          // 0..1 of canvas height
    private float birdVel = 0f;          // px/sec
    private float birdRotDeg = 0f;
    private float flapAnim = 0f;

    // ── Pipes (fixed pool — no per-frame allocation) ────────
    private static final int MAX_PIPES = 8;
    private final float[]   pipeX     = new float[MAX_PIPES];
    private final float[]   pipeGapCy = new float[MAX_PIPES];
    private final boolean[] pipeAlive = new boolean[MAX_PIPES];
    private final boolean[] pipeScored= new boolean[MAX_PIPES];
    private float spawnTimer = 0f;
    private static final float SPAWN_INTERVAL_S = 1.8f;

    // ── Score ───────────────────────────────────────────────
    private int score = 0;
    private int bestScore = 0;

    // ── PRNG for pipe Y placement ───────────────────────────
    private long rng = 0xABCDEF12345L;
    private float nextRandom() {
        rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
        return (rng & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    }

    // ── Canvas-height cache (set per frame; physics derives from it) ──
    private int cachedHeight = 800;
    private float gravityPxS2()   { return cachedHeight * 1.75f; }
    private float flapImpulse()   { return -cachedHeight * 0.65f; }
    private float scrollSpeedPx() { return cachedHeight * 0.25f; }
    private float pipeWidthPx()   { return cachedHeight * 0.10f; }
    private float pipeGapPx()     { return cachedHeight * 0.30f; }
    private float groundPx()      { return cachedHeight * 0.10f; }
    private float birdRadius(int width, int height) {
        return Math.min(width, height) * 0.040f;
    }

    @Override
    protected void onInit(int sr) {
        resetForReady();
        bestScore = 0;
    }

    private void resetForReady() {
        state = STATE_READY;
        birdY = 0.4f; birdVel = 0f; birdRotDeg = 0f; flapAnim = 0f;
        score = 0; spawnTimer = 0f;
        for (int i = 0; i < MAX_PIPES; i++) {
            pipeAlive[i] = false; pipeScored[i] = false;
        }
        // Use a tighter mic threshold for chirp-style detection.
        mic.floor(0.015f).mult(2.5f).refractoryS(0.18f).reset();
    }

    private void startGame() {
        state = STATE_PLAYING;
        score = 0;
        spawnTimer = SPAWN_INTERVAL_S * 0.4f;
        birdVel = flapImpulse();
        flapAnim = 1f;
    }

    private void gameOver(int width, int height) {
        state = STATE_GAME_OVER;
        if (score > bestScore) bestScore = score;
        // Death feedback — kit-supplied juice & particles.
        juice.shake(14f * scale, 0.35f);
        juice.flash(0.25f, Palette.DEATH_FLASH);
        float bx = width * 0.30f;
        particles.burst(bx, birdY * height, 28, Palette.ACCENT_ORANGE);
    }

    private void flap() {
        if (state != STATE_PLAYING) return;
        birdVel = flapImpulse();
        flapAnim = 1f;
        // Subtle puff of "wing dust" each flap.
        float bx = 0f, by = 0f;            // particles spawn translated below
        particles.burst(bx, by, 4, Palette.SPARKLE);
    }

    @Override
    public void onTouchDown(float x, float y) {
        if (state == STATE_READY)         { startGame(); flap(); }
        else if (state == STATE_PLAYING)  { flap(); }
        else if (state == STATE_GAME_OVER){ resetForReady(); }
    }

    // ── Game-state step ─────────────────────────────────────
    private void step(int width, int height) {
        if (state != STATE_PLAYING) return;
        cachedHeight = height;
        float velCap = height * 0.75f;
        birdVel = Ease.clamp(birdVel + gravityPxS2() * dt, -velCap, velCap);
        birdY += (birdVel * dt) / height;
        birdRotDeg = Ease.clamp(birdVel / velCap * 70f, -25f, 70f);
        if (flapAnim > 0f) flapAnim = Math.max(0f, flapAnim - dt * 8f);

        float ground = groundPx();
        float groundY = height - ground;
        float birdR = birdRadius(width, height);
        float birdScreenY = birdY * height;
        if (birdScreenY > groundY - birdR) {
            birdY = (groundY - birdR) / height;
            gameOver(width, height);
            return;
        }
        if (birdScreenY < -birdR) {
            birdY = -birdR / height;
            gameOver(width, height);
            return;
        }

        spawnTimer += dt;
        if (spawnTimer >= SPAWN_INTERVAL_S) {
            spawnTimer = 0f;
            spawnPipe(width, height);
        }
        float birdX = width * 0.30f;
        float pw = pipeWidthPx();
        float gap = pipeGapPx();
        float hitR = birdR * 0.85f;
        for (int i = 0; i < MAX_PIPES; i++) {
            if (!pipeAlive[i]) continue;
            pipeX[i] -= scrollSpeedPx() * dt;
            if (pipeX[i] + pw < 0f) { pipeAlive[i] = false; continue; }
            if (!pipeScored[i] && pipeX[i] + pw < birdX) {
                pipeScored[i] = true;
                score++;
                // Score feedback.
                juice.scorePop("+1", birdX, birdScreenY - birdR * 1.4f, Palette.ACCENT_YELLOW);
                particles.burst(birdX, birdScreenY, 6, Palette.ACCENT_YELLOW);
            }
            float bx0 = birdX - hitR, bx1 = birdX + hitR;
            float by0 = birdScreenY - hitR, by1 = birdScreenY + hitR;
            float px0 = pipeX[i], px1 = pipeX[i] + pw;
            if (bx1 > px0 && bx0 < px1) {
                float gapTop = pipeGapCy[i] - gap / 2f;
                float gapBot = pipeGapCy[i] + gap / 2f;
                if (by0 < gapTop || by1 > gapBot) {
                    gameOver(width, height);
                    return;
                }
            }
        }
    }

    private void spawnPipe(int width, int height) {
        float gap = pipeGapPx();
        float marginTop = height * 0.10f;
        float marginBot = groundPx() + height * 0.10f;
        for (int i = 0; i < MAX_PIPES; i++) {
            if (!pipeAlive[i]) {
                pipeX[i] = width + 20f;
                float minCy = marginTop + gap / 2f;
                float maxCy = height - marginBot - gap / 2f;
                if (maxCy < minCy) maxCy = minCy + 1f;
                pipeGapCy[i] = minCy + nextRandom() * (maxCy - minCy);
                pipeAlive[i] = true;
                pipeScored[i] = false;
                return;
            }
        }
    }

    // ── Render ──────────────────────────────────────────────
    @Override
    public void render(
        PluginCanvas c,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    ) {
        beginFrame(width, height, timeMs, streams);
        if (mic.hit()) flap();
        cachedHeight = height;
        step(width, height);

        // Background.
        Gfx.gradientSky(c, width, height, Palette.SKY_DAY_TOP, Palette.SKY_DAY_BOT);

        // World layer (parallax + pipes + ground + bird) under shake.
        c.save();
        juice.applyShake(c);
        drawParallax(c, width, height, timeMs);
        for (int i = 0; i < MAX_PIPES; i++) {
            if (!pipeAlive[i]) continue;
            drawPipePair(c, pipeX[i], pipeGapCy[i], height);
        }
        drawGround(c, width, height, timeMs);
        drawBird(c, width * 0.30f, birdY * height, birdRotDeg, flapAnim,
                 birdRadius(width, height));
        c.restore();

        // HUD on top of shake.
        particles.draw(c);
        juice.drawOverlay(c, width, height);

        if (state == STATE_PLAYING || state == STATE_GAME_OVER) {
            float tagY = 36f * scale + 30f * scale;
            Gfx.roundPanel(c,
                width / 2f - 36f * scale, tagY - 20f * scale,
                width / 2f + 36f * scale, tagY + 20f * scale,
                8f * scale, Palette.UI_BG_CARD, Palette.UI_TEXT_INK,
                Math.max(1f, 2f * scale));
            Gfx.textCenter(c, Integer.toString(score),
                width / 2f, tagY + 8f * scale,
                28f * scale, Palette.UI_TEXT_INK);
        }

        drawMicHud(c, width, height);

        if (state == STATE_READY) {
            drawOverlayCard(c, width, height,
                "Chirp to start",
                "Make a sharp sound — 'pa!' or clap — to flap.");
        } else if (state == STATE_GAME_OVER) {
            drawOverlayCard(c, width, height,
                "Game over · " + score,
                bestScore > 0 ? "Best: " + bestScore + " · tap to retry"
                              : "Tap to try again");
        }
    }

    // ── Game-specific drawing (kept here — kit doesn't ship art) ──
    private void drawBird(PluginCanvas c, float cx, float cy,
                          float rotDeg, float flap, float r) {
        c.save();
        c.translate(cx, cy);
        c.rotate(rotDeg);
        PluginPaint body = c.newPaint();
        body.setRadialGradient(-r * 0.25f, -r * 0.25f, r * 1.4f,
            new int[] { 0xFFFFE066, Palette.ACCENT_ORANGE, 0xFFE07B1F },
            new float[] { 0f, 0.6f, 1f });
        c.drawCircle(0, 0, r, body);
        PluginPaint belly = c.newPaint();
        belly.setColor(Palette.SPARKLE);
        c.drawCircle(0, r * 0.38f, r * 0.5f, belly);
        PluginPaint wing = c.newPaint();
        wing.setColor(Palette.ACCENT_RED);
        float wingY = -r * 0.12f + (flap > 0.5f ? -r * 0.38f : r * 0.25f);
        c.drawCircle(-r * 0.25f, wingY, r * 0.5f, wing);
        Gfx.strokeCircle(c, r * 0.38f, -r * 0.25f, r * 0.31f,
            0xFFFFFFFF, 0, 0f);
        Gfx.strokeCircle(c, r * 0.50f, -r * 0.25f, r * 0.16f,
            Palette.UI_TEXT_INK, 0, 0f);
        PluginPath beak = c.newPath();
        beak.moveTo(r * 0.75f, -r * 0.12f);
        beak.lineTo(r * 1.38f,  0f);
        beak.lineTo(r * 0.75f,  r * 0.25f);
        beak.close();
        PluginPaint beakP = c.newPaint();
        beakP.setColor(Palette.ACCENT_ORANGE);
        c.drawPath(beak, beakP);
        c.restore();
    }

    private void drawPipePair(PluginCanvas c, float x, float gapCy, int height) {
        float pw = pipeWidthPx();
        float gap = pipeGapPx();
        float capH = pw * 0.30f;
        float capLip = pw * 0.10f;
        float strokeW = Math.max(1f, 2f * scale);
        float gapTop = gapCy - gap / 2f;
        float gapBot = gapCy + gap / 2f;
        float ground = groundPx();
        PluginPaint pipe = c.newPaint();
        pipe.setLinearGradient(x, 0, x + pw, 0,
            new int[] { 0xFF7BC95B, 0xFFA8E27D, 0xFF5BA340 },
            new float[] { 0f, 0.45f, 1f });
        c.drawRect(x, 0, x + pw, gapTop - capH, pipe);
        c.drawRect(x - capLip, gapTop - capH, x + pw + capLip, gapTop, pipe);
        c.drawRect(x, gapBot + capH, x + pw, height - ground, pipe);
        c.drawRect(x - capLip, gapBot, x + pw + capLip, gapBot + capH, pipe);
        PluginPaint outline = c.newPaint();
        outline.setColor(0xFF2A6B28);
        outline.setStyle(PluginStyle.STROKE);
        outline.setStrokeWidth(strokeW);
        c.drawRect(x, 0, x + pw, gapTop - capH, outline);
        c.drawRect(x - capLip, gapTop - capH, x + pw + capLip, gapTop, outline);
        c.drawRect(x, gapBot + capH, x + pw, height - ground, outline);
        c.drawRect(x - capLip, gapBot, x + pw + capLip, gapBot + capH, outline);
    }

    private void drawParallax(PluginCanvas c, int width, int height, long timeMs) {
        float cityHeight = height * 0.30f;
        float cityBase = height - groundPx() - cityHeight * 0.15f;
        float cityScroll = (timeMs * 0.012f) % width;
        PluginPaint city = c.newPaint();
        city.setColor(0x55FFFFFF);
        drawCity(c, city, -cityScroll,             cityBase, width, cityHeight);
        drawCity(c, city, -cityScroll + width,     cityBase, width, cityHeight);
        float cloudScroll = (timeMs * 0.020f) % (width + 200f);
        PluginPaint cloud = c.newPaint();
        cloud.setColor(0xCCFFFFFF);
        drawClouds(c, cloud, -cloudScroll, height);
        drawClouds(c, cloud, -cloudScroll + width + 200f, height);
    }

    private void drawCity(PluginCanvas c, PluginPaint p, float xOff,
                          float baseY, int width, float cityH) {
        long s = 0x123456789ABL;
        float x = xOff;
        float bwBase = 30f * scale, bwSpread = 40f * scale, g = 4f * scale;
        while (x < xOff + width) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            float bw = bwBase + ((s & 0xFF) / 255f) * bwSpread;
            float bh = cityH * (0.35f + (((s >>> 8) & 0xFF) / 255f) * 0.65f);
            c.drawRect(x, baseY - bh, x + bw, baseY + cityH, p);
            x += bw + g;
        }
    }

    private void drawClouds(PluginCanvas c, PluginPaint p, float xOff, int height) {
        long s = 0xCAFEBABEDEADL;
        float r = 20f * scale;
        for (int i = 0; i < 5; i++) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            float cxx = xOff + ((s & 0xFFFF) / 65535f) * 800f;
            float cyy = height * 0.10f + (((s >>> 16) & 0xFF) / 255f) * (height * 0.45f);
            c.drawCircle(cxx,            cyy,                r,        p);
            c.drawCircle(cxx + r * 1.1f, cyy - r * 0.3f,     r * 0.8f, p);
            c.drawCircle(cxx - r * 1.1f, cyy - r * 0.2f,     r * 0.7f, p);
            c.drawCircle(cxx + r * 0.4f, cyy + r * 0.5f,     r * 0.75f, p);
        }
    }

    private void drawGround(PluginCanvas c, int width, int height, long timeMs) {
        float ground = groundPx();
        float grassH = ground * 0.13f;
        PluginPaint dirt = c.newPaint();
        dirt.setColor(0xFFDED793);
        c.drawRect(0, height - ground, width, height, dirt);
        PluginPaint grass = c.newPaint();
        grass.setColor(0xFF74C44A);
        c.drawRect(0, height - ground, width, height - ground + grassH, grass);
        PluginPaint stripe = c.newPaint();
        stripe.setColor(0xFF5BAA38);
        float stripeW = Math.max(8f, 20f * scale);
        float groundScroll = (timeMs * 0.14f) % stripeW;
        for (float gx = -groundScroll; gx < width; gx += stripeW) {
            c.drawRect(gx, height - ground, gx + stripeW / 2f, height - ground + grassH, stripe);
        }
    }

    private void drawMicHud(PluginCanvas c, int width, int height) {
        float padX = 24f * scale;
        float barY = height - 38f * scale;
        float micR = 22f * scale;
        float barW = (width - padX * 2 - micR * 4) / 2f;
        // Dashed label.
        Gfx.textCenter(c, "—  CHIRP TO FLAP  —", width / 2f, height - 60f * scale,
            14f * scale, 0xFF4A4030);
        // Both meters read off mic.level().
        float lvl = Math.min(1f, mic.level() * 6f);
        Gfx.levelBar(c, padX, barY - 8f * scale, padX + barW, barY + 8f * scale, lvl);
        Gfx.levelBar(c, width - padX - barW, barY - 8f * scale, width - padX, barY + 8f * scale, lvl);
        // Mic icon centre.
        float micCx = width / 2f, micCy = barY;
        Gfx.strokeCircle(c, micCx, micCy, micR,
            0xFFFFFFFF, Palette.UI_TEXT_INK, Math.max(1f, 2f * scale));
        PluginPaint micBody = c.newPaint();
        boolean hot = mic.hotness() >= 1f;
        micBody.setColor(hot ? Palette.ACCENT_RED : 0xFF333344);
        c.drawRoundRect(micCx - micR * 0.27f, micCy - micR * 0.45f,
            micCx + micR * 0.27f, micCy + micR * 0.18f, micR * 0.18f, micBody);
    }

    private void drawOverlayCard(PluginCanvas c, int width, int height,
                                 String title, String sub) {
        float cx = width / 2f;
        float cardY = height * 0.42f;
        float halfW = Math.min(280f * scale, width * 0.42f);
        float cardH = 106f * scale;
        float radius = 14f * scale;
        Gfx.card(c, cx - halfW, cardY, cx + halfW, cardY + cardH,
                 radius, Math.max(1f, 2f * scale));
        Gfx.textCenter(c, title, cx, cardY + 40f * scale,
            22f * scale, Palette.ACCENT_RED);
        Gfx.textCenter(c, sub, cx, cardY + 72f * scale,
            13f * scale, 0xFF4A4030);
    }
}
