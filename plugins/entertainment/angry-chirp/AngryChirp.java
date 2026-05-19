package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.gamekit.Ease;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;
import com.vocalmonitor.plugin.gamekit.svg.PluginShape;
import com.vocalmonitor.plugin.gamekit.svg.Svg;

import java.util.Map;

/**
 * Angry Chirp v2 — voice-controlled Flappy Bird, now rendered with
 * SVG sprite art instead of procedural shapes.
 *
 * Visual assets (in `assets/`):
 *   - bird.svg        — detailed angry-bird character, replaces
 *                       the original procedural circle+wing
 *   - pipe-top.svg    — pipe descending from the top of the
 *                       screen, with cap + bolts + highlight stripe
 *   - pipe-bottom.svg — same pipe rising from the ground
 *   - cloud.svg       — fluffy white cloud with subtle bottom shade
 *
 * Falls back to a "procedural lite" rendering if the host doesn't
 * support {@code loadAssetText} (PluginHost default impl) — so the
 * plugin still loads on older hosts, just less pretty.
 *
 * All gameplay tuning (gravity, scroll speed, pipe gap, chirp
 * threshold) is unchanged from v1.  See git history for the
 * pre-SVG procedural version.
 */
public final class AngryChirp extends GamePluginBase {

    // ── State machine ───────────────────────────────────────
    private static final int STATE_READY     = 0;
    private static final int STATE_PLAYING   = 1;
    private static final int STATE_GAME_OVER = 2;
    private int state = STATE_READY;

    // ── Bird state ──────────────────────────────────────────
    private float birdY = 0.4f;
    private float birdVel = 0f;
    private float birdRotDeg = 0f;
    private float flapAnim = 0f;        // 0..1, decays after flap
    private float lastFlapTime = 0f;

    // ── Pipes (fixed pool, no per-frame alloc) ──────────────
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

    // ── Physics scaled to canvas height ─────────────────────
    private int cachedHeight = 800;
    private float gravityPxS2()   { return cachedHeight * 1.75f; }
    private float flapImpulse()   { return -cachedHeight * 0.65f; }
    private float scrollSpeedPx() { return cachedHeight * 0.25f; }
    private float pipeWidthPx()   { return cachedHeight * 0.10f; }
    private float pipeGapPx()     { return cachedHeight * 0.30f; }
    private float groundPx()      { return cachedHeight * 0.10f; }
    private float birdRadius(int w, int h) {
        return Math.min(w, h) * 0.044f;
    }

    // ── SVG sprites (lazy-loaded on first render) ───────────
    private PluginShape spriteBird = null;
    private PluginShape spritePipeTop = null;
    private PluginShape spritePipeBot = null;
    private PluginShape spriteCloud = null;
    private boolean spritesTried = false;

    // ── PRNG for pipe Y placement ───────────────────────────
    private long rng = 0xABCDEF12345L;
    private float nextRandom() {
        rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
        return (rng & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    }

    @Override
    protected void onInit(int sr) {
        resetForReady();
        bestScore = 0;
        // Sprite caches are per-instance — must clear too so a
        // re-init starts cold.
        spriteBird = null;
        spritePipeTop = null;
        spritePipeBot = null;
        spriteCloud = null;
        spritesTried = false;
    }

    private void resetForReady() {
        state = STATE_READY;
        birdY = 0.4f; birdVel = 0f; birdRotDeg = 0f; flapAnim = 0f;
        score = 0; spawnTimer = 0f;
        for (int i = 0; i < MAX_PIPES; i++) {
            pipeAlive[i] = false; pipeScored[i] = false;
        }
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
        // Big juice on death.
        juice.shake(16f * scale, 0.40f);
        juice.flash(0.30f, Palette.DEATH_FLASH);
        float bx = width * 0.30f;
        // Three layered particle bursts for a thick impact feel.
        particles.burst(bx, birdY * height, 24, Palette.ACCENT_RED);
        particles.burst(bx, birdY * height, 18, Palette.ACCENT_ORANGE);
        particles.burst(bx, birdY * height, 10, Palette.SPARKLE);
        juice.impactRing(bx, birdY * height, 40f * scale, Palette.HIT_FLASH);
    }

    private void flap() {
        if (state != STATE_PLAYING) return;
        birdVel = flapImpulse();
        flapAnim = 1f;
        lastFlapTime = (System.nanoTime() / 1_000_000L) / 1000f;
        // Feather puff at the wing position.
        // (Particles are spawned at bird coords inside render below
        // so they line up with where the bird actually is.)
    }

    @Override
    public void onTouchDown(float x, float y) {
        if (state == STATE_READY)         { startGame(); flap(); }
        else if (state == STATE_PLAYING)  { flap(); }
        else if (state == STATE_GAME_OVER){ resetForReady(); }
    }

    // ── Game step ───────────────────────────────────────────
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
        float hitR = birdR * 0.78f;     // slightly more forgiving than v1
        for (int i = 0; i < MAX_PIPES; i++) {
            if (!pipeAlive[i]) continue;
            pipeX[i] -= scrollSpeedPx() * dt;
            if (pipeX[i] + pw < 0f) { pipeAlive[i] = false; continue; }
            if (!pipeScored[i] && pipeX[i] + pw < birdX) {
                pipeScored[i] = true;
                score++;
                juice.scorePop("+1", birdX, birdScreenY - birdR * 1.4f, Palette.ACCENT_YELLOW);
                particles.burst(birdX, birdScreenY, 8, Palette.ACCENT_YELLOW);
                particles.burst(birdX, birdScreenY, 4, Palette.SPARKLE);
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

    private void tryLoadSprites() {
        if (spritesTried || host == null) { spritesTried = true; return; }
        spritesTried = true;
        String s1 = host.loadAssetText("bird.svg");
        if (s1 != null) spriteBird = Svg.parse(s1);
        String s2 = host.loadAssetText("pipe-top.svg");
        if (s2 != null) spritePipeTop = Svg.parse(s2);
        String s3 = host.loadAssetText("pipe-bottom.svg");
        if (s3 != null) spritePipeBot = Svg.parse(s3);
        String s4 = host.loadAssetText("cloud.svg");
        if (s4 != null) spriteCloud = Svg.parse(s4);
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
        if (mic.hit()) {
            flap();
            // Mic-triggered flap leaves an extra sparkle.
            if (state == STATE_PLAYING) {
                particles.burst(width * 0.30f, birdY * height, 6, Palette.SPARKLE);
            }
        }
        cachedHeight = height;
        tryLoadSprites();
        step(width, height);

        // ── Background gradient.
        Gfx.gradientSky(c, width, height, Palette.SKY_DAY_TOP, Palette.SKY_DAY_BOT);

        // World layer (everything that shakes on impact) ↓
        c.save();
        juice.applyShake(c);
        drawParallaxClouds(c, width, height, timeMs);
        drawSilhouetteCity(c, width, height, timeMs);
        for (int i = 0; i < MAX_PIPES; i++) {
            if (!pipeAlive[i]) continue;
            drawPipePair(c, pipeX[i], pipeGapCy[i], height);
        }
        drawGround(c, width, height, timeMs);
        drawBird(c, width * 0.30f, birdY * height, birdRotDeg, flapAnim,
                 birdRadius(width, height));
        c.restore();

        // Particles + juice overlay (don't shake — read clearly).
        particles.draw(c);
        juice.drawOverlay(c, width, height);

        if (state == STATE_PLAYING || state == STATE_GAME_OVER) {
            drawScoreTag(c, width / 2f, 36f * scale + 30f * scale, score);
        }

        drawMicHud(c, width, height);

        if (state == STATE_READY) {
            drawOverlayCard(c, width, height,
                "Chirp to start",
                "Sing 'pa!' or clap to flap.");
        } else if (state == STATE_GAME_OVER) {
            drawOverlayCard(c, width, height,
                "Game over · " + score,
                bestScore > 0 ? "Best: " + bestScore + " · tap to retry"
                              : "Tap to try again");
        }
    }

    // ── Drawing helpers ─────────────────────────────────────
    private void drawBird(PluginCanvas c, float cx, float cy,
                          float rotDeg, float flap, float r) {
        if (spriteBird == null || spriteBird.isEmpty()) {
            drawBirdProcedural(c, cx, cy, rotDeg, flap, r);
            return;
        }
        // SVG bird is in a -50..+50 viewBox; scale to fit radius r,
        // with a tiny pulse on every flap for "feedback feel".
        c.save();
        c.translate(cx, cy);
        c.rotate(rotDeg);
        float pulse = 1f + flap * 0.06f;
        float vb = spriteBird.viewBoxHeight();           // 100
        float s = (r * 2.4f * pulse) / vb;
        spriteBird.draw(c, 0f, 0f, s);
        c.restore();
    }

    private void drawBirdProcedural(PluginCanvas c, float cx, float cy,
                                     float rotDeg, float flap, float r) {
        // Fallback if SVG didn't load — minimal but readable.
        c.save();
        c.translate(cx, cy);
        c.rotate(rotDeg);
        Gfx.strokeCircle(c, 0, 0, r, Palette.ACCENT_YELLOW, Palette.UI_TEXT_INK, Math.max(1f, 2f * scale));
        Gfx.strokeCircle(c, r * 0.38f, -r * 0.25f, r * 0.31f, 0xFFFFFFFF, 0, 0f);
        Gfx.strokeCircle(c, r * 0.50f, -r * 0.25f, r * 0.16f, Palette.UI_TEXT_INK, 0, 0f);
        c.restore();
    }

    private void drawPipePair(PluginCanvas c, float x, float gapCy, int height) {
        float pw = pipeWidthPx();
        float gap = pipeGapPx();
        float gapTop = gapCy - gap / 2f;
        float gapBot = gapCy + gap / 2f;
        float ground = groundPx();
        // Top pipe stretches from y=0 to gapTop; bottom from gapBot
        // to (height - ground).
        if (spritePipeTop != null && !spritePipeTop.isEmpty()) {
            // Sprite viewBox is 100×300, with the cap occupying the
            // bottom 60.  Stretch the body vertically to match the
            // required height; the cap visually stays the same size
            // because of how the SVG is authored (shaft is paint, cap
            // is geometry).  Anisotropic scale handles the stretch.
            float sx = pw / spritePipeTop.viewBoxWidth();
            float sy = gapTop / spritePipeTop.viewBoxHeight();
            spritePipeTop.drawAnisotropic(c, x, 0f, sx, sy);
        } else {
            drawPipeProcedural(c, x, 0f, x + pw, gapTop, true);
        }
        if (spritePipeBot != null && !spritePipeBot.isEmpty()) {
            float bottomH = (height - ground) - gapBot;
            if (bottomH < 0f) bottomH = 0f;
            float sx = pw / spritePipeBot.viewBoxWidth();
            float sy = bottomH / spritePipeBot.viewBoxHeight();
            spritePipeBot.drawAnisotropic(c, x, gapBot, sx, sy);
        } else {
            drawPipeProcedural(c, x, gapBot, x + pw, height - ground, false);
        }
    }

    private void drawPipeProcedural(
        PluginCanvas c, float x0, float y0, float x1, float y1, boolean capBottom
    ) {
        float strokeW = Math.max(1f, 2f * scale);
        Gfx.roundPanel(c, x0, y0, x1, y1, 4f * scale,
            0xFF7BC95B, 0xFF2A6B28, strokeW);
        // Cap.
        float capH = (x1 - x0) * 0.28f;
        if (capBottom) {
            Gfx.roundPanel(c, x0 - 6f * scale, y1 - capH, x1 + 6f * scale, y1,
                4f * scale, 0xFF7BC95B, 0xFF2A6B28, strokeW);
        } else {
            Gfx.roundPanel(c, x0 - 6f * scale, y0, x1 + 6f * scale, y0 + capH,
                4f * scale, 0xFF7BC95B, 0xFF2A6B28, strokeW);
        }
    }

    private void drawParallaxClouds(PluginCanvas c, int width, int height, long timeMs) {
        // SVG clouds at three different x offsets + parallax speeds.
        if (spriteCloud != null && !spriteCloud.isEmpty()) {
            float vbW = spriteCloud.viewBoxWidth();
            float vbH = spriteCloud.viewBoxHeight();
            // Three cloud bands: tiny back-row, mid, large front.
            drawCloudBand(c, vbW, vbH, width, height,
                timeMs * 0.015f, 0.20f, 0.6f);
            drawCloudBand(c, vbW, vbH, width, height,
                timeMs * 0.030f, 0.35f, 0.9f);
            drawCloudBand(c, vbW, vbH, width, height,
                timeMs * 0.050f, 0.55f, 1.2f);
        }
    }

    private void drawCloudBand(
        PluginCanvas c, float vbW, float vbH,
        int width, int height,
        float scrollPx, float yFracOfHeight, float sizeMult
    ) {
        float drawW = vbW * scale * sizeMult;
        float drawH = vbH * scale * sizeMult;
        float spacing = drawW * 1.5f;
        // First cloud could be off-screen left; iterate until we cover
        // the visible width plus a buffer.
        float startX = -(scrollPx % spacing) - spacing;
        float y = yFracOfHeight * height;
        for (float x = startX; x < width + spacing; x += spacing) {
            float s = scale * sizeMult;
            spriteCloud.draw(c, x, y, s);
        }
    }

    private void drawSilhouetteCity(PluginCanvas c, int width, int height, long timeMs) {
        // Procedural city silhouette — cheap to vary and would just
        // be repetitive as an SVG.  Stays in tinted-white at low alpha
        // so it reads as far-distance.
        float cityHeight = height * 0.25f;
        float cityBase = height - groundPx() - cityHeight * 0.05f;
        float cityScroll = (timeMs * 0.012f) % width;
        com.vocalmonitor.plugin.PluginPaint p = c.newPaint();
        p.setColor(0x55FFFFFF);
        drawCityStrip(c, p, -cityScroll,           cityBase, width, cityHeight);
        drawCityStrip(c, p, -cityScroll + width,   cityBase, width, cityHeight);
    }

    private void drawCityStrip(PluginCanvas c, com.vocalmonitor.plugin.PluginPaint p,
                               float xOff, float baseY, int width, float cityH) {
        long s = 0x123456789ABL;
        float x = xOff;
        float bwBase = 30f * scale, bwSpread = 40f * scale, gap = 4f * scale;
        while (x < xOff + width) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            float bw = bwBase + ((s & 0xFF) / 255f) * bwSpread;
            float bh = cityH * (0.35f + (((s >>> 8) & 0xFF) / 255f) * 0.65f);
            c.drawRect(x, baseY - bh, x + bw, baseY + cityH, p);
            x += bw + gap;
        }
    }

    private void drawGround(PluginCanvas c, int width, int height, long timeMs) {
        float ground = groundPx();
        float grassH = ground * 0.13f;
        com.vocalmonitor.plugin.PluginPaint dirt = c.newPaint();
        dirt.setColor(0xFFDED793);
        c.drawRect(0, height - ground, width, height, dirt);
        com.vocalmonitor.plugin.PluginPaint grass = c.newPaint();
        grass.setColor(0xFF74C44A);
        c.drawRect(0, height - ground, width, height - ground + grassH, grass);
        com.vocalmonitor.plugin.PluginPaint stripe = c.newPaint();
        stripe.setColor(0xFF5BAA38);
        float stripeW = Math.max(8f, 20f * scale);
        float groundScroll = (timeMs * 0.14f) % stripeW;
        for (float gx = -groundScroll; gx < width; gx += stripeW) {
            c.drawRect(gx, height - ground, gx + stripeW / 2f, height - ground + grassH, stripe);
        }
    }

    private void drawScoreTag(PluginCanvas c, float cx, float cy, int n) {
        float halfW = 36f * scale, halfH = 20f * scale;
        Gfx.softShadow(c, cx - halfW, cy - halfH, cx + halfW, cy + halfH,
            8f * scale, 2f * scale, 2f * scale, 0x66000000);
        Gfx.roundPanel(c, cx - halfW, cy - halfH, cx + halfW, cy + halfH,
            8f * scale, Palette.UI_BG_CARD, Palette.UI_TEXT_INK,
            Math.max(1f, 2f * scale));
        Gfx.textCenter(c, Integer.toString(n), cx, cy + 8f * scale,
            28f * scale, Palette.UI_TEXT_INK);
    }

    private void drawMicHud(PluginCanvas c, int width, int height) {
        float padX = 24f * scale;
        float barY = height - 38f * scale;
        float micR = 22f * scale;
        float barW = (width - padX * 2 - micR * 4) / 2f;
        Gfx.textCenter(c, "—  CHIRP TO FLAP  —",
            width / 2f, height - 60f * scale,
            14f * scale, 0xFF4A4030);
        float lvl = Math.min(1f, mic.level() * 6f);
        Gfx.levelBar(c, padX, barY - 8f * scale, padX + barW, barY + 8f * scale, lvl);
        Gfx.levelBar(c, width - padX - barW, barY - 8f * scale,
            width - padX, barY + 8f * scale, lvl);
        // Mic icon.
        float micCx = width / 2f, micCy = barY;
        boolean hot = mic.hotness() >= 1f;
        Gfx.strokeCircle(c, micCx, micCy, micR,
            0xFFFFFFFF, Palette.UI_TEXT_INK, Math.max(1f, 2f * scale));
        com.vocalmonitor.plugin.PluginPaint mb = c.newPaint();
        mb.setColor(hot ? Palette.ACCENT_RED : 0xFF333344);
        c.drawRoundRect(micCx - micR * 0.27f, micCy - micR * 0.45f,
            micCx + micR * 0.27f, micCy + micR * 0.18f, micR * 0.18f, mb);
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
