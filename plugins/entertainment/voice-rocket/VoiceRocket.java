package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.gamekit.Collision;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;
import com.vocalmonitor.plugin.gamekit.audio.PitchTracker;
import com.vocalmonitor.plugin.gamekit.audio.RmsFollower;

import java.util.Map;

/**
 * Voice Rocket — side-scrolling endless flyer.
 *
 * Volume (RmsFollower) drives forward speed with a constant minimum
 * so the world always crawls forward; pitch (PitchTracker) drives
 * vertical position. Static asteroids and sine-trajectory flyers
 * stream in from the right; circle-vs-circle collision ends the run
 * with the standard juice + particles game-over.
 */
public final class VoiceRocket extends GamePluginBase {

    private static final int READY = 0, PLAYING = 1, GAME_OVER = 2;
    private int state = READY;
    private float gameOverTimer = 0f;

    private final PitchTracker pitch = new PitchTracker();
    private final RmsFollower rms = new RmsFollower().attack(0.40f).release(0.20f);
    private float rocketY = 0.6f;
    private float scrollPx = 0f;

    private int score = 0;
    private int best = 0;

    private static final int N_AST = 8;
    private final float[] astX = new float[N_AST];
    private final float[] astY = new float[N_AST];
    private final float[] astR = new float[N_AST];
    private final boolean[] astAlive  = new boolean[N_AST];
    private final boolean[] astScored = new boolean[N_AST];
    private float astTimer = 0f;
    private static final float AST_INTERVAL_S = 1.6f;
    private long astRng = 0xA57E1042L;

    private static final int N_FLY = 6;
    private final float[] flyX     = new float[N_FLY];
    private final float[] flyBaseY = new float[N_FLY];
    private final float[] flyAmp   = new float[N_FLY];
    private final float[] flyFreq  = new float[N_FLY];
    private final float[] flyPhase = new float[N_FLY];
    private final boolean[] flyAlive  = new boolean[N_FLY];
    private final boolean[] flyScored = new boolean[N_FLY];
    private float flyTimer = 0f;
    private static final float FLY_INTERVAL_S = 3.5f;
    private long flyRng = 0xFEED7E11L;

    private int cachedW = 360, cachedH = 200;
    private float cachedRx, cachedRy, cachedRocketLen, cachedRocketW, cachedRocketR;

    @Override
    protected void onInit(int sr) {
        pitch.setSampleRate(sr).lpCutoff(800f).floor(0.008f).idleHz(180f).followRate(0.30f);
        rms.reset();
        resetGame();
    }

    private void resetGame() {
        state = READY;
        score = 0;
        gameOverTimer = 0f;
        rocketY = 0.6f;
        scrollPx = 0f;
        astTimer = 0f;
        flyTimer = 0f;
        for (int i = 0; i < N_AST; i++) { astAlive[i] = false; astScored[i] = false; }
        for (int i = 0; i < N_FLY; i++) { flyAlive[i] = false; flyScored[i] = false; }
    }

    @Override
    public void onTouchDown(float x, float y) {
        if (state == READY) state = PLAYING;
        else if (state == GAME_OVER && gameOverTimer > 0.6f) resetGame();
    }

    @Override
    public void render(PluginCanvas c, int width, int height, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        beginFrame(width, height, timeMs, streams);
        cachedW = width; cachedH = height;

        pitch.feed(streams, dt);
        rms.feed(streams, dt);

        float level = Math.min(1f, rms.level() * 5f);
        float vBase = width * 0.18f;
        float vMax  = width * 0.55f;
        float speed = vBase + (vMax - vBase) * level;

        cachedRx = width * 0.28f;
        cachedRocketLen = Math.min(width, height) * 0.16f;
        cachedRocketW = cachedRocketLen * 0.45f;
        cachedRocketR = cachedRocketLen * 0.30f;

        if (state == PLAYING) {
            float hz = pitch.hz();
            if (hz < 80f)  hz = 80f;
            if (hz > 500f) hz = 500f;
            float norm = (hz - 80f) / (500f - 80f);
            float targetY = 0.85f - norm * 0.75f;
            rocketY += 0.20f * (targetY - rocketY);

            scrollPx += speed * dt;
            astTimer += dt;
            if (astTimer >= AST_INTERVAL_S) { astTimer = 0f; spawnAsteroid(); }
            flyTimer += dt;
            if (flyTimer >= FLY_INTERVAL_S) { flyTimer = 0f; spawnFlyer(); }

            cachedRy = height * rocketY;
            updateObstacles(speed);
        } else if (state == READY) {
            scrollPx += vBase * 0.5f * dt;
            cachedRy = height * rocketY;
            if (rms.level() > 0.02f) state = PLAYING;
        } else {
            gameOverTimer += dt;
            cachedRy = height * rocketY;
            if (gameOverTimer > 1.2f && rms.level() > 0.04f) resetGame();
        }

        Gfx.gradient3(c, width, height,
            Palette.SKY_DUSK_TOP, Palette.SKY_DUSK_MID, Palette.SKY_DUSK_BOT);
        drawStars(c, width, height, scrollPx, timeMs);

        c.save();
        juice.applyShake(c);
        drawObstacles(c);
        if (state != GAME_OVER) {
            drawRocket(c, cachedRx, cachedRy, cachedRocketLen, cachedRocketW, level);
        }
        c.restore();

        particles.draw(c);
        juice.drawOverlay(c, width, height);

        drawHud(c, width, height, level, pitch.hz());

        if (state == READY)          drawReadyOverlay(c, width, height);
        else if (state == GAME_OVER) drawGameOverOverlay(c, width, height);
    }

    private void spawnAsteroid() {
        int slot = -1;
        for (int i = 0; i < N_AST; i++) if (!astAlive[i]) { slot = i; break; }
        if (slot < 0) return;
        astAlive[slot] = true;
        astScored[slot] = false;
        astX[slot] = cachedW + 60f;
        astY[slot] = cachedH * (0.18f + rnd01(astRng) * 0.65f);
        astR[slot] = Math.min(cachedW, cachedH) * (0.05f + rnd01(astRng ^ 0x9E3779B9L) * 0.04f);
        astRng = nextRng(astRng);
    }

    private void spawnFlyer() {
        int slot = -1;
        for (int i = 0; i < N_FLY; i++) if (!flyAlive[i]) { slot = i; break; }
        if (slot < 0) return;
        flyAlive[slot] = true;
        flyScored[slot] = false;
        flyX[slot] = cachedW + 80f;
        flyBaseY[slot] = cachedH * (0.20f + rnd01(flyRng) * 0.55f);
        flyAmp[slot]   = cachedH * (0.05f + rnd01(flyRng ^ 0x9E3779B9L) * 0.08f);
        flyFreq[slot]  = 0.6f + rnd01(flyRng ^ 0xBF58476DL) * 1.0f;
        flyPhase[slot] = 0f;
        flyRng = nextRng(flyRng);
    }

    private void updateObstacles(float speed) {
        float rx = cachedRx, ry = cachedRy, rr = cachedRocketR;
        float flySpeed = cachedW * 0.45f;

        for (int i = 0; i < N_AST; i++) {
            if (!astAlive[i]) continue;
            astX[i] -= speed * dt;
            if (astX[i] + astR[i] < -10f) { astAlive[i] = false; continue; }
            if (!astScored[i] && astX[i] + astR[i] < rx) {
                astScored[i] = true;
                score++;
                juice.scorePop("+1", rx, ry - cachedRocketLen, Palette.ACCENT_YELLOW);
                particles.burst(rx + cachedRocketLen * 0.4f, ry, 4, Palette.SPARKLE);
            }
            if (Collision.circleVsCircle(rx, ry, rr, astX[i], astY[i], astR[i])) {
                gameOver(rx, ry);
                return;
            }
        }

        for (int i = 0; i < N_FLY; i++) {
            if (!flyAlive[i]) continue;
            flyX[i] -= flySpeed * dt;
            flyPhase[i] += dt * flyFreq[i];
            float fy = flyBaseY[i] + flyAmp[i] * (float) Math.sin(flyPhase[i] * Math.PI * 2.0);
            float fr = Math.min(cachedW, cachedH) * 0.045f;
            if (flyX[i] + fr < -10f) { flyAlive[i] = false; continue; }
            if (!flyScored[i] && flyX[i] + fr < rx) {
                flyScored[i] = true;
                score++;
                juice.scorePop("+1", rx, ry - cachedRocketLen, Palette.ACCENT_PINK);
                particles.burst(rx + cachedRocketLen * 0.4f, ry, 4, Palette.ACCENT_PINK);
            }
            if (Collision.circleVsCircle(rx, ry, rr, flyX[i], fy, fr * 0.85f)) {
                gameOver(rx, ry);
                return;
            }
        }
    }

    private void gameOver(float rx, float ry) {
        state = GAME_OVER;
        gameOverTimer = 0f;
        if (score > best) best = score;
        juice.shake(12f * scale, 0.35f);
        juice.flash(0.30f, Palette.DEATH_FLASH);
        particles.burst(rx, ry, 32, Palette.ACCENT_RED);
        particles.burst(rx, ry, 16, Palette.ACCENT_ORANGE);
    }

    private void drawStars(PluginCanvas c, int w, int h, float scroll, long timeMs) {
        long s = 0xBEEFCAFEL;
        PluginPaint star = c.newPaint();
        for (int i = 0; i < 50; i++) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            float baseX = ((s & 0xFFFF) / 65535f) * (w * 2f);
            float sy = (((s >>> 16) & 0xFFFF) / 65535f) * h * 0.50f;
            float sx = (baseX - scroll * 0.4f) % (w * 2f);
            if (sx < 0) sx += w * 2f;
            if (sx > w) continue;
            float tw = 0.4f + 0.6f * (float) Math.sin(timeMs * 0.003 + i);
            star.setColor(0x00FFFFFF | ((int) (0xCC * tw) << 24));
            c.drawCircle(sx, sy, 1.6f * scale, star);
        }
    }

    private void drawObstacles(PluginCanvas c) {
        int rockFill    = 0xFF8A7766;
        int rockOutline = Palette.darken(rockFill, 0.4f);
        int rockCrater  = Palette.darken(rockFill, 0.25f);
        for (int i = 0; i < N_AST; i++) {
            if (!astAlive[i]) continue;
            Gfx.strokeCircle(c, astX[i], astY[i], astR[i], rockFill, rockOutline, 2f * scale);
            Gfx.strokeCircle(c, astX[i] - astR[i] * 0.3f, astY[i] - astR[i] * 0.2f,
                astR[i] * 0.25f, rockCrater, 0, 0f);
        }
        float fr = Math.min(cachedW, cachedH) * 0.045f;
        for (int i = 0; i < N_FLY; i++) {
            if (!flyAlive[i]) continue;
            float fy = flyBaseY[i] + flyAmp[i] * (float) Math.sin(flyPhase[i] * Math.PI * 2.0);
            Gfx.strokeCircle(c, flyX[i], fy, fr, Palette.ACCENT_PINK,
                Palette.darken(Palette.ACCENT_PINK, 0.35f), 1.5f * scale);
            Gfx.strokeCircle(c, flyX[i], fy - fr * 0.45f, fr * 0.55f,
                Palette.ACCENT_BLUE, 0, 0f);
            Gfx.strokeCircle(c, flyX[i] + fr * 1.3f, fy, fr * 0.25f,
                Palette.withAlpha(Palette.ACCENT_PINK, 0.45f), 0, 0f);
        }
    }

    private void drawRocket(PluginCanvas c, float rx, float ry, float len, float w, float level01) {
        if (level01 > 0.02f) {
            PluginPath flame = c.newPath();
            float tipX  = rx - len * 0.55f - level01 * len * 1.2f;
            float baseX = rx - len * 0.45f;
            flame.moveTo(baseX, ry - w * 0.35f);
            flame.quadTo(baseX - level01 * 30f, ry, tipX, ry);
            flame.quadTo(baseX - level01 * 30f, ry, baseX, ry + w * 0.35f);
            flame.close();
            PluginPaint fp = c.newPaint();
            fp.setLinearGradient(tipX, ry, baseX, ry,
                new int[] { 0xFFFFEE66, 0xFFFF8844, 0xFFFF3322 },
                new float[] { 0f, 0.6f, 1f });
            fp.setGlow(0xFFFF6622, 16f);
            c.drawPath(flame, fp);
        }
        PluginPath body = c.newPath();
        body.moveTo(rx - len * 0.45f, ry - w * 0.4f);
        body.lineTo(rx + len * 0.35f, ry - w * 0.4f);
        body.quadTo(rx + len * 0.55f, ry, rx + len * 0.35f, ry + w * 0.4f);
        body.lineTo(rx - len * 0.45f, ry + w * 0.4f);
        body.close();
        PluginPaint bp = c.newPaint();
        bp.setLinearGradient(rx, ry - w * 0.4f, rx, ry + w * 0.4f,
            new int[] { 0xFFEEEEEE, 0xFFBBBBBB, 0xFF888888 },
            new float[] { 0f, 0.5f, 1f });
        c.drawPath(body, bp);

        PluginPaint fin = c.newPaint();
        fin.setColor(Palette.ACCENT_RED);
        PluginPath f1 = c.newPath();
        f1.moveTo(rx - len * 0.45f, ry - w * 0.4f);
        f1.lineTo(rx - len * 0.55f, ry - w * 0.85f);
        f1.lineTo(rx - len * 0.25f, ry - w * 0.4f);
        f1.close();
        c.drawPath(f1, fin);
        PluginPath f2 = c.newPath();
        f2.moveTo(rx - len * 0.45f, ry + w * 0.4f);
        f2.lineTo(rx - len * 0.55f, ry + w * 0.85f);
        f2.lineTo(rx - len * 0.25f, ry + w * 0.4f);
        f2.close();
        c.drawPath(f2, fin);

        Gfx.strokeCircle(c, rx + len * 0.15f, ry, w * 0.18f,
            Palette.ACCENT_BLUE, 0xFF335577, 2f * scale);
    }

    private void drawHud(PluginCanvas c, int w, int h, float level01, float hz) {
        Gfx.textLeft(c, "Skaits: " + score, 18f * scale, 30f * scale,
            20f * scale, Palette.UI_TEXT);
        Gfx.textRight(c, "Labākais: " + best, w - 18f * scale, 30f * scale,
            18f * scale, Palette.UI_TEXT);
        String hzText = hz > 60f ? Math.round(hz) + " Hz" : "— Hz";
        Gfx.textCenter(c, hzText, w / 2f, 30f * scale, 18f * scale, Palette.UI_TEXT);
        float bx0 = 18f * scale, bx1 = w * 0.32f;
        float by0 = h - 28f * scale, by1 = h - 14f * scale;
        Gfx.levelBar(c, bx0, by0, bx1, by1, level01);
        Gfx.textLeft(c, "Ātrums", bx0, by0 - 6f * scale, 12f * scale, Palette.UI_TEXT_DIM);
    }

    private void drawReadyOverlay(PluginCanvas c, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float halfW = w * 0.36f, halfH = h * 0.20f;
        Gfx.card(c, cx - halfW, cy - halfH, cx + halfW, cy + halfH, 16f * scale, 2f * scale);
        Gfx.textCenter(c, "Voice Rocket", cx, cy - halfH * 0.35f,
            26f * scale, Palette.UI_TEXT_INK);
        Gfx.textCenter(c, "Murmini, lai sāktu!", cx, cy + halfH * 0.05f,
            18f * scale, Palette.UI_TEXT_INK);
        Gfx.textCenter(c, "Skaļāk = ātrāk · Augstāks tonis = augstāk",
            cx, cy + halfH * 0.55f, 12f * scale, Palette.UI_TEXT_INK);
    }

    private void drawGameOverOverlay(PluginCanvas c, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float halfW = w * 0.36f, halfH = h * 0.22f;
        Gfx.card(c, cx - halfW, cy - halfH, cx + halfW, cy + halfH, 16f * scale, 2f * scale);
        Gfx.textCenter(c, "Spēle beigusies", cx, cy - halfH * 0.45f,
            22f * scale, Palette.UI_TEXT_INK);
        Gfx.textCenter(c, "Skaits: " + score, cx, cy - halfH * 0.05f,
            18f * scale, Palette.UI_TEXT_INK);
        Gfx.textCenter(c, "Labākais: " + best, cx, cy + halfH * 0.30f,
            16f * scale, Palette.UI_TEXT_INK);
        Gfx.textCenter(c, "Pieskaries vai murmini, lai sāktu vēlreiz",
            cx, cy + halfH * 0.70f, 12f * scale, Palette.UI_TEXT_INK);
    }

    private static long nextRng(long s) {
        s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
        return s;
    }

    private static float rnd01(long s) {
        s = nextRng(s);
        return (s & 0x7FFFFFFFL) / (float) Long.MAX_VALUE * 2f;
    }
}
