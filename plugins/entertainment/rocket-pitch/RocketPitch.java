package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.gamekit.Collision;
import com.vocalmonitor.plugin.gamekit.Ease;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;
import com.vocalmonitor.plugin.gamekit.audio.NoteName;
import com.vocalmonitor.plugin.gamekit.audio.PitchTracker;
import com.vocalmonitor.plugin.gamekit.audio.RmsFollower;
import com.vocalmonitor.plugin.gamekit.ui.Button;

import java.util.Map;

/**
 * Rocket Pitch - voice-controlled rocket, rebuilt as a full game on the
 * gamekit (GamePluginBase). Pitch flies the rocket up/down; volume feeds
 * the flame. Ring gates drift in at different heights - sing the pitch
 * that lines the rocket up with a ring and fly through to score.
 * Asteroids tumble in too; clip one and the run ends. Ready / playing /
 * game-over loop with screen-shake, flash, score-pops and particle
 * bursts. Audio is pure passthrough (see GamePluginBase).
 */
public final class RocketPitch extends GamePluginBase {

    private final PitchTracker pitch = new PitchTracker();
    private final RmsFollower rms = new RmsFollower().attack(0.3f).release(0.08f);
    private final Button launchBtn = new Button().fill(Palette.ACCENT_YELLOW);

    private static final int READY = 0, PLAYING = 1, OVER = 2;
    private int state = READY;

    private static final float LO_HZ = 90f, HI_HZ = 520f;
    private float rocketY = 0.6f;
    private float flame = 0f;
    private float tiltDeg = 0f;
    private float prevRocketY = 0.6f;

    private int score = 0;
    private int best = 0;
    private float distance = 0f;

    private static final int MAX_GATES = 6;
    private final float[] gateX = new float[MAX_GATES];
    private final float[] gateY = new float[MAX_GATES];
    private final boolean[] gateAlive = new boolean[MAX_GATES];
    private final boolean[] gateScored = new boolean[MAX_GATES];
    private float gateTimer = 0f;

    private static final int MAX_ROCKS = 6;
    private final float[] rockX = new float[MAX_ROCKS];
    private final float[] rockY = new float[MAX_ROCKS];
    private final float[] rockR = new float[MAX_ROCKS];
    private final float[] rockSpin = new float[MAX_ROCKS];
    private final boolean[] rockAlive = new boolean[MAX_ROCKS];
    private float rockTimer = 0f;

    private float scrollFar = 0f, scrollMid = 0f, scrollNear = 0f;

    private long rng = 0xBEEFCAFE1234L;
    private float rnd() {
        rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
        return (rng & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    }

    @Override
    protected void onInit(int sr) {
        pitch.setSampleRate(sr).lpCutoff(800f).floor(0.008f).idleHz(200f).reset();
        rms.reset();
        state = READY;
        rocketY = prevRocketY = 0.6f; flame = 0f; tiltDeg = 0f;
        score = 0; best = 0; distance = 0f;
        gateTimer = rockTimer = 0f;
        for (int i = 0; i < MAX_GATES; i++) { gateAlive[i] = false; gateScored[i] = false; }
        for (int i = 0; i < MAX_ROCKS; i++) rockAlive[i] = false;
        scrollFar = scrollMid = scrollNear = 0f;
    }

    private void startGame() {
        state = PLAYING;
        score = 0; distance = 0f;
        gateTimer = 0.6f; rockTimer = 2.0f;
        for (int i = 0; i < MAX_GATES; i++) { gateAlive[i] = false; gateScored[i] = false; }
        for (int i = 0; i < MAX_ROCKS; i++) rockAlive[i] = false;
    }

    private void gameOver(int w, int h) {
        state = OVER;
        if (score > best) best = score;
        float rx = w * 0.28f, ry = rocketY * h;
        juice.shake(16f * scale, 0.45f);
        juice.flash(0.30f, Palette.DEATH_FLASH);
        juice.impactRing(rx, ry, 40f * scale, Palette.HIT_FLASH);
        particles.burst(rx, ry, 26, Palette.ACCENT_ORANGE);
        particles.burst(rx, ry, 16, Palette.ACCENT_RED);
        particles.burst(rx, ry, 10, Palette.SPARKLE);
    }

    @Override
    public void onTouchDown(float x, float y) {
        launchBtn.touchDown(x, y);
        if (state == READY)      startGame();
        else if (state == OVER)  state = READY;
    }
    @Override public void onTouchMove(float x, float y) { launchBtn.touchMove(x, y); }
    @Override public void onTouchUp(float x, float y)   { launchBtn.touchUp(x, y); }

    private float scrollSpeed(int w) { return w * (0.26f + Math.min(0.22f, distance * 0.012f)); }
    private float gateInterval()     { return Math.max(1.1f, 2.2f - distance * 0.05f); }
    private float rockInterval()     { return Math.max(1.4f, 3.2f - distance * 0.06f); }

    private float pitchToY() {
        float norm = Ease.clamp((pitch.hz() - LO_HZ) / (HI_HZ - LO_HZ), 0f, 1f);
        return 0.88f - norm * 0.78f;
    }
    private float gateYToHz(float y01) {
        float norm = Ease.clamp((0.88f - y01) / 0.78f, 0f, 1f);
        return LO_HZ + norm * (HI_HZ - LO_HZ);
    }

    private void step(int w, int h) {
        distance += dt;
        prevRocketY = rocketY;
        rocketY += 0.22f * (pitchToY() - rocketY);
        rocketY = Ease.clamp(rocketY, 0.06f, 0.94f);
        tiltDeg = Ease.clamp((rocketY - prevRocketY) / Math.max(1e-4f, dt) * -40f, -28f, 28f);
        flame += 0.25f * (Math.min(1f, rms.level() * 6.5f) - flame);

        float speed = scrollSpeed(w);
        float rx = w * 0.28f, ry = rocketY * h, rocketR = Math.min(w, h) * 0.05f;
        float gateR = Math.min(w, h) * 0.13f;

        gateTimer -= dt;
        if (gateTimer <= 0f) { gateTimer = gateInterval(); spawnGate(); }
        for (int i = 0; i < MAX_GATES; i++) {
            if (!gateAlive[i]) continue;
            gateX[i] -= speed * dt;
            float gx = gateX[i], gy = gateY[i] * h;
            if (!gateScored[i] && gx < rx) {
                if (Math.abs(gy - ry) < gateR * 0.72f) {
                    gateScored[i] = true; score++;
                    juice.scorePop("+1", rx, ry - rocketR * 1.6f, Palette.ACCENT_GREEN);
                    juice.impactRing(gx, gy, gateR, Palette.ACCENT_GREEN);
                    particles.burst(gx, gy, 12, Palette.ACCENT_GREEN);
                    particles.burst(gx, gy, 6, Palette.SPARKLE);
                } else gateScored[i] = true;
            }
            if (gx + gateR < 0f) gateAlive[i] = false;
        }

        rockTimer -= dt;
        if (rockTimer <= 0f) { rockTimer = rockInterval(); spawnRock(); }
        for (int i = 0; i < MAX_ROCKS; i++) {
            if (!rockAlive[i]) continue;
            rockX[i] -= speed * 1.05f * dt;
            rockSpin[i] += dt * 60f;
            float ax = rockX[i], ay = rockY[i] * h, ar = rockR[i] * Math.min(w, h) * 0.07f;
            if (Collision.circleVsCircle(rx, ry, rocketR * 0.8f, ax, ay, ar * 0.85f)) { gameOver(w, h); return; }
            if (ax + ar < 0f) rockAlive[i] = false;
        }
    }

    private void spawnGate() {
        for (int i = 0; i < MAX_GATES; i++) {
            if (gateAlive[i]) continue;
            gateAlive[i] = true; gateScored[i] = false;
            gateX[i] = 1.15f; gateY[i] = 0.18f + rnd() * 0.64f; return;
        }
    }
    private void spawnRock() {
        for (int i = 0; i < MAX_ROCKS; i++) {
            if (rockAlive[i]) continue;
            rockAlive[i] = true;
            rockX[i] = 1.2f; rockY[i] = 0.12f + rnd() * 0.76f;
            rockR[i] = 0.7f + rnd() * 0.8f; rockSpin[i] = rnd() * 360f; return;
        }
    }

    @Override
    public void render(PluginCanvas c, int w, int h, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        beginFrame(w, h, timeMs, streams);
        pitch.feed(streams, dt);
        rms.feed(streams, dt);

        for (int i = 0; i < MAX_GATES; i++) if (gateAlive[i] && gateX[i] <= 1.3f && gateX[i] >= 1.0f) gateX[i] = w * gateX[i];
        for (int i = 0; i < MAX_ROCKS; i++) if (rockAlive[i] && rockX[i] <= 1.3f && rockX[i] >= 1.0f) rockX[i] = w * rockX[i];

        float baseSpeed = (state == PLAYING) ? scrollSpeed(w) : w * 0.06f;
        scrollFar += dt * baseSpeed * 0.15f;
        scrollMid += dt * baseSpeed * 0.40f;
        scrollNear += dt * baseSpeed * 0.90f;

        drawSpace(c, w, h, timeMs);
        if (state == PLAYING) step(w, h);

        c.save();
        juice.applyShake(c);
        if (state == PLAYING || state == OVER) drawGates(c, w, h);
        drawRocket(c, w, h, timeMs);
        if (state == PLAYING || state == OVER) drawRocks(c, w, h);
        c.restore();

        drawPitchScale(c, w, h);
        drawHud(c, w, h);
        if (state == READY)      drawReady(c, w, h);
        else if (state == OVER)  drawOver(c, w, h);

        particles.draw(c);
        juice.drawOverlay(c, w, h);
    }

    private void drawSpace(PluginCanvas c, int w, int h, long timeMs) {
        Gfx.gradientSky(c, w, h, 0xFF0A0E2B, 0xFF241344);
        PluginPaint neb = c.newPaint();
        neb.setRadialGradient(w * 0.72f, h * 0.28f, Math.min(w, h) * 0.6f,
            new int[] { 0x553B2E7A, 0x00241344 }, new float[] { 0f, 1f });
        c.drawRect(0, 0, w, h, neb);

        long s = 0xBEEFCAFEL;
        PluginPaint star = c.newPaint();
        for (int i = 0; i < 70; i++) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            float bx = ((s & 0xFFFF) / 65535f) * (w * 2f);
            float sy = (((s >>> 16) & 0xFFFF) / 65535f) * h;
            float sx = (bx - scrollFar) % (w * 2f); if (sx < 0) sx += w * 2f;
            if (sx > w) continue;
            float tw = 0.4f + 0.6f * (float) Math.sin(timeMs * 0.003 + i);
            star.setColor((((int) (0xCC * tw)) << 24) | 0x00FFFFFF);
            c.drawCircle(sx, sy, 1.6f * scale, star);
        }
        long sp = 0x1234FEEDL;
        for (int i = 0; i < 2; i++) {
            sp ^= sp << 13; sp ^= sp >>> 7; sp ^= sp << 17;
            float bx = ((sp & 0xFFFF) / 65535f) * (w * 2.4f);
            float py = 0.12f * h + (((sp >>> 16) & 0xFFFF) / 65535f) * (h * 0.5f);
            float px = (bx - scrollMid) % (w * 2.4f); if (px < 0) px += w * 2.4f;
            if (px > w + 80f) continue;
            float pr = (28f + i * 14f) * scale;
            PluginPaint pl = c.newPaint();
            pl.setRadialGradient(px - pr * 0.3f, py - pr * 0.3f, pr * 1.4f,
                new int[] { i == 0 ? 0xFF7A6CC0 : 0xFFC06C8A, 0xFF2A1B4E }, new float[] { 0f, 1f });
            c.drawCircle(px, py, pr, pl);
        }
    }

    private void drawGates(PluginCanvas c, int w, int h) {
        float gateR = Math.min(w, h) * 0.13f;
        for (int i = 0; i < MAX_GATES; i++) {
            if (!gateAlive[i]) continue;
            float gx = gateX[i], gy = gateY[i] * h;
            int col = gateScored[i] ? 0xFF3C4A66 : Palette.ACCENT_BLUE;
            PluginPaint ring = c.newPaint();
            ring.setColor(col); ring.setStyle(PluginStyle.STROKE); ring.setStrokeWidth(7f * scale);
            if (!gateScored[i]) ring.setGlow(Palette.ACCENT_BLUE, 12f * scale);
            c.drawCircle(gx, gy, gateR, ring);
            PluginPaint inner = c.newPaint();
            inner.setColor(Palette.withAlpha(0xFFFFFFFF, gateScored[i] ? 0.15f : 0.45f));
            inner.setStyle(PluginStyle.STROKE); inner.setStrokeWidth(2f * scale);
            c.drawCircle(gx, gy, gateR * 0.7f, inner);
            Gfx.textCenter(c, NoteName.of(gateYToHz(gateY[i])), gx, gy + 5f * scale,
                14f * scale, Palette.withAlpha(0xFFFFFFFF, gateScored[i] ? 0.4f : 0.9f));
        }
    }

    private void drawRocks(PluginCanvas c, int w, int h) {
        for (int i = 0; i < MAX_ROCKS; i++) {
            if (!rockAlive[i]) continue;
            float ax = rockX[i], ay = rockY[i] * h, ar = rockR[i] * Math.min(w, h) * 0.07f;
            PluginPaint rk = c.newPaint();
            rk.setRadialGradient(ax - ar * 0.3f, ay - ar * 0.3f, ar * 1.5f,
                new int[] { 0xFF8A8175, 0xFF4A453E }, new float[] { 0f, 1f });
            c.drawCircle(ax, ay, ar, rk);
            PluginPaint bump = c.newPaint(); bump.setColor(0xFF6A6258);
            float a1 = (float) Math.toRadians(rockSpin[i]);
            c.drawCircle(ax + (float) Math.cos(a1) * ar * 0.4f, ay + (float) Math.sin(a1) * ar * 0.4f, ar * 0.45f, bump);
            PluginPaint crater = c.newPaint(); crater.setColor(0xFF3A352F);
            c.drawCircle(ax - ar * 0.25f, ay + ar * 0.2f, ar * 0.2f, crater);
        }
    }

    private void drawRocket(PluginCanvas c, int w, int h, long timeMs) {
        float rx = w * 0.28f;
        float ry;
        if (state == PLAYING || state == OVER) ry = rocketY * h;
        else ry = (pitch.voiced() ? pitchToY() : (0.55f + 0.04f * (float) Math.sin(timeMs * 0.003))) * h;
        float L = Math.min(w, h) * 0.16f, Wd = L * 0.42f;

        c.save();
        c.translate(rx, ry);
        c.rotate(-tiltDeg);

        float fl = (state == PLAYING) ? flame : (pitch.voiced() ? Math.min(1f, rms.level() * 6.5f) : 0.15f);
        if (fl > 0.02f) {
            PluginPath flame = c.newPath();
            float tipY = L * 0.55f + fl * L * 1.1f;
            flame.moveTo(-Wd * 0.32f, L * 0.42f);
            flame.quadTo(0, L * 0.42f + fl * 24f, 0, tipY);
            flame.quadTo(0, L * 0.42f + fl * 24f, Wd * 0.32f, L * 0.42f);
            flame.close();
            PluginPaint fp = c.newPaint();
            fp.setLinearGradient(0, L * 0.42f, 0, tipY,
                new int[] { 0xFFFFEE66, 0xFFFF8844, 0xFFFF3322 }, new float[] { 0f, 0.6f, 1f });
            fp.setGlow(0xFFFF6622, 16f * scale);
            c.drawPath(flame, fp);
        }

        PluginPath body = c.newPath();
        body.moveTo(0, -L * 0.55f);
        body.quadTo(Wd * 0.5f, -L * 0.15f, Wd * 0.42f, L * 0.35f);
        body.lineTo(-Wd * 0.42f, L * 0.35f);
        body.quadTo(-Wd * 0.5f, -L * 0.15f, 0, -L * 0.55f);
        body.close();
        PluginPaint bp = c.newPaint();
        bp.setLinearGradient(-Wd * 0.5f, 0, Wd * 0.5f, 0,
            new int[] { 0xFFFFFFFF, 0xFFCFD6DE, 0xFF94A0AE }, new float[] { 0f, 0.5f, 1f });
        c.drawPath(body, bp);

        PluginPaint finP = c.newPaint(); finP.setColor(Palette.ACCENT_RED);
        PluginPath finL = c.newPath();
        finL.moveTo(-Wd * 0.42f, L * 0.10f); finL.lineTo(-Wd * 0.85f, L * 0.48f); finL.lineTo(-Wd * 0.42f, L * 0.35f); finL.close();
        c.drawPath(finL, finP);
        PluginPath finR = c.newPath();
        finR.moveTo(Wd * 0.42f, L * 0.10f); finR.lineTo(Wd * 0.85f, L * 0.48f); finR.lineTo(Wd * 0.42f, L * 0.35f); finR.close();
        c.drawPath(finR, finP);

        PluginPath cone = c.newPath();
        cone.moveTo(0, -L * 0.55f);
        cone.quadTo(Wd * 0.34f, -L * 0.18f, 0, -L * 0.12f);
        cone.quadTo(-Wd * 0.34f, -L * 0.18f, 0, -L * 0.55f);
        cone.close();
        PluginPaint coneP = c.newPaint(); coneP.setColor(Palette.ACCENT_RED);
        c.drawPath(cone, coneP);

        PluginPaint win = c.newPaint(); win.setColor(Palette.ACCENT_BLUE);
        c.drawCircle(0, L * 0.02f, Wd * 0.22f, win);
        PluginPaint winRim = c.newPaint();
        winRim.setColor(0xFF2B4A66); winRim.setStyle(PluginStyle.STROKE); winRim.setStrokeWidth(2f * scale);
        c.drawCircle(0, L * 0.02f, Wd * 0.22f, winRim);

        c.restore();
    }

    private void drawPitchScale(PluginCanvas c, int w, int h) {
        float sx = w - 30f * scale;
        PluginPaint axis = c.newPaint(); axis.setColor(0x44FFFFFF);
        c.drawLine(sx, h * 0.08f, sx, h * 0.92f, axis);
        PluginPaint tk = c.newPaint(); tk.setColor(0xCCFFFFFF);
        tk.setTextSize(10f * scale); tk.setTextAlign(2);
        int[] notes = { 110, 165, 220, 330, 440 };
        for (int hz : notes) {
            float norm = (hz - LO_HZ) / (HI_HZ - LO_HZ);
            float y = h * (0.88f - Ease.clamp(norm, 0f, 1f) * 0.78f);
            c.drawLine(sx - 4f * scale, y, sx + 4f * scale, y, tk);
            c.drawText(NoteName.of(hz), sx - 8f * scale, y + 3f * scale, tk);
        }
    }

    private void drawHud(PluginCanvas c, int w, int h) {
        if (state != PLAYING) return;
        Gfx.textLeft(c, "SCORE", 16f * scale, 30f * scale, 12f * scale, Palette.UI_TEXT_DIM);
        Gfx.textLeft(c, score + "", 16f * scale, 56f * scale, 30f * scale, Palette.UI_TEXT);
        String note = pitch.voiced() ? NoteName.of(pitch.hz()) + " · " + Math.round(pitch.hz()) + " Hz" : "sing…";
        Gfx.textLeft(c, note, 16f * scale, h - 18f * scale, 14f * scale, Palette.ACCENT_YELLOW);
    }

    private void drawReady(PluginCanvas c, int w, int h) {
        PluginPaint scrim = c.newPaint(); scrim.setColor(0x55000000);
        c.drawRect(0, 0, w, h, scrim);
        float cx = w * 0.5f;
        Gfx.textCenter(c, "ROCKET PITCH", cx, h * 0.26f, 34f * scale, Palette.UI_TEXT);
        Gfx.textCenter(c, "Sing high to climb, low to dive", cx, h * 0.31f, 15f * scale, Palette.UI_TEXT_DIM);
        Gfx.textCenter(c, "Fly through the rings · dodge the rocks", cx, h * 0.345f, 13f * scale, Palette.UI_TEXT_DIM);
        if (best > 0) Gfx.textCenter(c, "Best  " + best, cx, h * 0.40f, 18f * scale, Palette.ACCENT_AMBER);
        float bw = w * 0.46f, bh = 52f * scale;
        if (launchBtn.draw(c, "Launch", cx - bw / 2f, h * 0.80f - bh / 2f,
                cx + bw / 2f, h * 0.80f + bh / 2f, scale)) {
            startGame();
        }
    }

    private void drawOver(PluginCanvas c, int w, int h) {
        PluginPaint scrim = c.newPaint(); scrim.setColor(0x88000000);
        c.drawRect(0, 0, w, h, scrim);
        float cx = w * 0.5f;
        Gfx.textCenter(c, "Splashdown!", cx, h * 0.34f, 30f * scale, Palette.ACCENT_RED);
        Gfx.textCenter(c, "Score", cx, h * 0.44f, 14f * scale, Palette.UI_TEXT_DIM);
        Gfx.textCenter(c, score + "", cx, h * 0.50f, 44f * scale, Palette.UI_TEXT);
        Gfx.textCenter(c, "Best  " + best, cx, h * 0.56f, 16f * scale, Palette.ACCENT_AMBER);
        Gfx.textCenter(c, "Tap to fly again", cx, h * 0.70f, 16f * scale, Palette.UI_TEXT_DIM);
    }
}
