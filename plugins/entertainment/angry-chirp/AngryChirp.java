package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginHost;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Angry Chirp — voice-controlled Flappy Bird.
 *
 * **Audio mic flow:** consumes streams["waveform"] from the host
 * inside render() (slim live monitor path).  RMS of the latest chunk
 * is compared to a slow-moving baseline; when it spikes above the
 * baseline by `chirpThresh × baseline` AND clears an absolute floor,
 * fires one flap impulse and enters a short refractory window so a
 * single sustained shout doesn't fly off the screen.
 *
 * **Game loop:** integrates bird velocity / pipe scroll on the UI
 * thread using wall-clock dt from `timeMs`.  Pipe positions live in
 * fixed-size arrays so we never allocate per frame.  PRNG is a
 * private xorshift so reset gives a reproducible-looking layout but
 * the gameplay is fresh each tap.
 *
 * **Touch:** tap anywhere to flap (accessibility backup for the mic
 * input), tap to start when ready, tap to restart when game-over.
 * No pause for now — keep the kid moving.
 */
public final class AngryChirp implements VocalMonitorVisualPlugin {

    // ── Game-state machine ───────────────────────────────────
    private static final int STATE_READY     = 0;
    private static final int STATE_PLAYING   = 1;
    private static final int STATE_GAME_OVER = 2;
    private int state = STATE_READY;
    private long gameOverAtMs = -1L;

    // ── Bird ─────────────────────────────────────────────────
    private float birdY = 0.4f;          // 0..1 of canvas height
    private float birdVel = 0f;          // px/sec (positive = down)
    private float birdRotDeg = 0f;
    private float flapAnim = 0f;         // 0..1 frame timer for wing
    private static final float GRAVITY_PX_S2  = 1400f;
    private static final float FLAP_IMPULSE   = -520f;

    // ── Pipes ────────────────────────────────────────────────
    private static final int MAX_PIPES = 8;
    private final float[]   pipeX     = new float[MAX_PIPES];
    private final float[]   pipeGapCy = new float[MAX_PIPES];
    private final boolean[] pipeAlive = new boolean[MAX_PIPES];
    private final boolean[] pipeScored= new boolean[MAX_PIPES];
    private float spawnTimer = 0f;
    private static final float SPAWN_INTERVAL_S = 1.8f;
    private static final float PIPE_WIDTH       = 70f;
    private static final float PIPE_GAP         = 200f;
    private static final float SCROLL_SPEED     = 140f;

    // ── Score ────────────────────────────────────────────────
    private int score = 0;
    private int bestScore = 0;

    // ── Mic / chirp detection ────────────────────────────────
    private float micRms = 0f;           // smoothed instantaneous
    private float micBaseline = 0f;      // slow-moving baseline
    private static final float CHIRP_FLOOR    = 0.015f;
    private static final float CHIRP_MULT     = 2.5f;
    private static final float REFRACTORY_S   = 0.18f;
    private float refractoryTimer = 0f;

    // ── Wall-clock dt ────────────────────────────────────────
    private long lastRenderMs = -1L;

    // ── PRNG ─────────────────────────────────────────────────
    private long rngState = 0xABCDEF12345L;
    private float nextRandom() {
        rngState ^= rngState << 13;
        rngState ^= rngState >>> 7;
        rngState ^= rngState << 17;
        return (rngState & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    }

    private PluginHost host = null;
    @Override public void setHost(PluginHost h) { this.host = h; }

    @Override
    public void init(int sr) {
        resetForReady();
        lastRenderMs = -1L;
        micRms = 0f;
        micBaseline = 0f;
        refractoryTimer = 0f;
        bestScore = 0;
    }

    private void resetForReady() {
        state = STATE_READY;
        birdY = 0.40f;
        birdVel = 0f;
        birdRotDeg = 0f;
        flapAnim = 0f;
        score = 0;
        spawnTimer = 0f;
        for (int i = 0; i < MAX_PIPES; i++) {
            pipeAlive[i] = false;
            pipeScored[i] = false;
        }
        refractoryTimer = 0f;
    }

    private void startGame() {
        state = STATE_PLAYING;
        score = 0;
        spawnTimer = SPAWN_INTERVAL_S * 0.4f;   // first pipe arrives sooner
        birdVel = FLAP_IMPULSE;                  // jump-start
        flapAnim = 1f;
    }

    private void gameOver(long timeMs) {
        state = STATE_GAME_OVER;
        gameOverAtMs = timeMs;
        if (score > bestScore) bestScore = score;
    }

    private void flap() {
        if (state != STATE_PLAYING) return;
        if (refractoryTimer > 0f) return;
        birdVel = FLAP_IMPULSE;
        flapAnim = 1f;
        refractoryTimer = REFRACTORY_S;
    }

    // ── Plugin contract ──────────────────────────────────────
    @Override public String[] parameterNames() { return new String[0]; }
    @Override public float parameterMin(String n)     { return 0f; }
    @Override public float parameterMax(String n)     { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n)  { return n; }
    @Override public void setParameter(String n, float v) {}

    @Override
    public void process(float[] input, float[] output) {
        // Passthrough — slim's live monitor reads streams["waveform"]
        // in render(), so we don't drive game state from here.
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) output[i] = input[i];
    }

    @Override
    public void onTouchDown(float x, float y) {
        // Tap anywhere: start, restart, or flap.
        if (state == STATE_READY) {
            startGame();
            flap();
        } else if (state == STATE_PLAYING) {
            flap();
        } else if (state == STATE_GAME_OVER) {
            // 0.5 s grace so the user doesn't accidentally re-trigger
            // on the same gesture that crashed the bird.
            resetForReady();
        }
    }

    // ── Live-mic feed: RMS + chirp detection ────────────────
    private void feedLive(Map<String, float[]> streams, float dt) {
        if (refractoryTimer > 0f) refractoryTimer -= dt;
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length == 0) {
            // Smoothly decay baseline so silence doesn't lock detector.
            micRms *= 0.92f;
            micBaseline += 0.05f * (micRms - micBaseline);
            return;
        }
        double sumSq = 0.0;
        for (int i = 0; i < wave.length; i++) sumSq += wave[i] * wave[i];
        float rms = (float) Math.sqrt(sumSq / wave.length);
        // Instantaneous follows the mic fast (attack), but the
        // baseline slides slowly so a sustained tone doesn't mark
        // itself as "chirp" — only transients do.
        micRms += 0.45f * (rms - micRms);
        micBaseline += 0.06f * (micRms - micBaseline);
        boolean chirpHit = micRms > CHIRP_FLOOR &&
            micRms > Math.max(micBaseline * CHIRP_MULT, CHIRP_FLOOR);
        if (chirpHit) flap();
    }

    // ── Game-state step ─────────────────────────────────────
    private void stepWorld(float dt, int width, int height) {
        if (state != STATE_PLAYING) return;
        // Bird physics.
        birdVel += GRAVITY_PX_S2 * dt;
        if (birdVel > 600f) birdVel = 600f;
        float pxY = birdY * height + birdVel * dt;
        birdY = pxY / height;
        // Tilt with velocity for that Flappy feel.
        birdRotDeg = Math.max(-25f, Math.min(70f, birdVel * 0.06f));
        if (flapAnim > 0f) flapAnim = Math.max(0f, flapAnim - dt * 8f);

        // World boundaries — floor / ceiling = death.
        float groundY = height - 80f;
        float birdScreenY = birdY * height;
        if (birdScreenY > groundY - 14f) {
            birdY = (groundY - 14f) / height;
            gameOver(System.currentTimeMillis());
            return;
        }
        if (birdScreenY < -20f) {
            birdY = -20f / height;
            gameOver(System.currentTimeMillis());
            return;
        }

        // Pipes.
        spawnTimer += dt;
        if (spawnTimer >= SPAWN_INTERVAL_S) {
            spawnTimer = 0f;
            spawnPipe(width, height);
        }
        float birdX = width * 0.30f;
        for (int i = 0; i < MAX_PIPES; i++) {
            if (!pipeAlive[i]) continue;
            pipeX[i] -= SCROLL_SPEED * dt;
            if (pipeX[i] + PIPE_WIDTH < 0f) {
                pipeAlive[i] = false;
                continue;
            }
            // Score when the bird's X crosses the pipe centre.
            if (!pipeScored[i] && pipeX[i] + PIPE_WIDTH < birdX) {
                pipeScored[i] = true;
                score++;
            }
            // Collision: bird rect vs pipe rect (above + below the gap).
            float bx0 = birdX - 16f, bx1 = birdX + 16f;
            float by0 = birdScreenY - 12f, by1 = birdScreenY + 12f;
            float px0 = pipeX[i], px1 = pipeX[i] + PIPE_WIDTH;
            if (bx1 > px0 && bx0 < px1) {
                float gapTop = pipeGapCy[i] - PIPE_GAP / 2f;
                float gapBot = pipeGapCy[i] + PIPE_GAP / 2f;
                if (by0 < gapTop || by1 > gapBot) {
                    gameOver(System.currentTimeMillis());
                    return;
                }
            }
        }
    }

    private void spawnPipe(int width, int height) {
        for (int i = 0; i < MAX_PIPES; i++) {
            if (!pipeAlive[i]) {
                pipeX[i] = width + 20f;
                float marginTop = 80f, marginBot = 180f;
                float minCy = marginTop + PIPE_GAP / 2f;
                float maxCy = height - marginBot - PIPE_GAP / 2f;
                pipeGapCy[i] = minCy + nextRandom() * (maxCy - minCy);
                pipeAlive[i] = true;
                pipeScored[i] = false;
                return;
            }
        }
    }

    // ── Render ───────────────────────────────────────────────
    @Override
    public void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    ) {
        float dt = (lastRenderMs < 0) ? 0.016f
            : Math.min(0.10f, (timeMs - lastRenderMs) / 1000f);
        lastRenderMs = timeMs;

        feedLive(streams, dt);
        stepWorld(dt, width, height);

        // ── Background: sky gradient ──
        PluginPaint sky = canvas.newPaint();
        sky.setLinearGradient(0, 0, 0, height,
            new int[] { 0xFF66BBE0, 0xFFA8DDF0 },
            new float[] { 0f, 1f });
        canvas.drawRect(0, 0, width, height, sky);

        // ── City silhouette (parallax slow) ──
        float cityScroll = (timeMs * 0.012f) % width;
        PluginPaint city = canvas.newPaint();
        city.setColor(0x55FFFFFF);
        drawCity(canvas, city, -cityScroll,             height - 200f, width);
        drawCity(canvas, city, -cityScroll + width,     height - 200f, width);

        // ── Clouds (parallax slow) ──
        float cloudScroll = (timeMs * 0.020f) % (width + 200f);
        PluginPaint cloud = canvas.newPaint();
        cloud.setColor(0xCCFFFFFF);
        drawClouds(canvas, cloud, -cloudScroll, height);
        drawClouds(canvas, cloud, -cloudScroll + width + 200f, height);

        // ── Pipes ──
        for (int i = 0; i < MAX_PIPES; i++) {
            if (!pipeAlive[i]) continue;
            drawPipePair(canvas, pipeX[i], pipeGapCy[i], height);
        }

        // ── Ground strip ──
        PluginPaint ground = canvas.newPaint();
        ground.setColor(0xFFDED793);
        canvas.drawRect(0, height - 80f, width, height, ground);
        PluginPaint grass = canvas.newPaint();
        grass.setColor(0xFF74C44A);
        canvas.drawRect(0, height - 80f, width, height - 70f, grass);
        // Grass blades — striped for cuteness.
        PluginPaint stripe = canvas.newPaint();
        stripe.setColor(0xFF5BAA38);
        float groundScroll = (timeMs * 0.14f) % 20f;
        for (float gx = -groundScroll; gx < width; gx += 20f) {
            canvas.drawRect(gx, height - 80f, gx + 10f, height - 70f, stripe);
        }

        // ── Bird ──
        drawBird(canvas, width * 0.30f, birdY * height, birdRotDeg, flapAnim);

        // ── HUD: score top-centre ──
        if (state == STATE_PLAYING || state == STATE_GAME_OVER) {
            drawScoreTag(canvas, width / 2f, 70f, score);
        }

        // ── HUD: bottom mic bar with bars + label ──
        drawMicHud(canvas, width, height);

        // ── State overlays ──
        if (state == STATE_READY) {
            drawCentreCard(canvas, width, height,
                "Chirp to start",
                "Make a sharp sound — 'pa!' or clap — to flap.");
        } else if (state == STATE_GAME_OVER) {
            drawCentreCard(canvas, width, height,
                "Game over · " + score,
                bestScore > 0 ? "Best: " + bestScore + " · tap to retry"
                              : "Tap to try again");
        }
    }

    // ── Drawing helpers ─────────────────────────────────────
    private void drawBird(PluginCanvas c, float cx, float cy, float rotDeg, float flap) {
        c.save();
        c.translate(cx, cy);
        c.rotate(rotDeg);
        // Body.
        PluginPaint body = c.newPaint();
        body.setRadialGradient(-4, -4, 22,
            new int[] { 0xFFFFE066, 0xFFFFB733, 0xFFE07B1F },
            new float[] { 0f, 0.6f, 1f });
        c.drawCircle(0, 0, 16f, body);
        // Belly highlight.
        PluginPaint belly = c.newPaint();
        belly.setColor(0xFFFFF1B0);
        c.drawCircle(0, 6f, 8f, belly);
        // Wing.
        PluginPaint wing = c.newPaint();
        wing.setColor(0xFFE25656);
        float wingY = -2f + (flap > 0.5f ? -6f : 4f);
        c.drawCircle(-4f, wingY, 8f, wing);
        // Eye.
        PluginPaint eyeWhite = c.newPaint();
        eyeWhite.setColor(0xFFFFFFFF);
        c.drawCircle(6f, -4f, 5f, eyeWhite);
        PluginPaint pupil = c.newPaint();
        pupil.setColor(0xFF101018);
        c.drawCircle(8f, -4f, 2.5f, pupil);
        // Beak.
        PluginPath beak = c.newPath();
        beak.moveTo(12f, -2f);
        beak.lineTo(22f, 0f);
        beak.lineTo(12f, 4f);
        beak.close();
        PluginPaint beakP = c.newPaint();
        beakP.setColor(0xFFFF8833);
        c.drawPath(beak, beakP);
        c.restore();
    }

    private void drawPipePair(PluginCanvas c, float x, float gapCy, int height) {
        float gapTop = gapCy - PIPE_GAP / 2f;
        float gapBot = gapCy + PIPE_GAP / 2f;
        PluginPaint pipe = c.newPaint();
        pipe.setLinearGradient(x, 0, x + PIPE_WIDTH, 0,
            new int[] { 0xFF7BC95B, 0xFFA8E27D, 0xFF5BA340 },
            new float[] { 0f, 0.45f, 1f });
        // Top pipe body + cap.
        c.drawRect(x, 0, x + PIPE_WIDTH, gapTop - 20f, pipe);
        c.drawRect(x - 6f, gapTop - 20f, x + PIPE_WIDTH + 6f, gapTop, pipe);
        // Bottom pipe body + cap.
        c.drawRect(x, gapBot + 20f, x + PIPE_WIDTH, height - 80f, pipe);
        c.drawRect(x - 6f, gapBot, x + PIPE_WIDTH + 6f, gapBot + 20f, pipe);
        // Outlines.
        PluginPaint outline = c.newPaint();
        outline.setColor(0xFF2A6B28);
        outline.setStyle(PluginStyle.STROKE);
        outline.setStrokeWidth(2f);
        c.drawRect(x, 0, x + PIPE_WIDTH, gapTop - 20f, outline);
        c.drawRect(x - 6f, gapTop - 20f, x + PIPE_WIDTH + 6f, gapTop, outline);
        c.drawRect(x, gapBot + 20f, x + PIPE_WIDTH, height - 80f, outline);
        c.drawRect(x - 6f, gapBot, x + PIPE_WIDTH + 6f, gapBot + 20f, outline);
    }

    private void drawCity(PluginCanvas c, PluginPaint p, float xOff, float baseY, int width) {
        // Deterministic skyline.
        long s = 0x123456789ABL;
        float x = xOff;
        while (x < xOff + width) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            float bw = 30f + ((s & 0xFF) / 255f) * 40f;
            float bh = 40f + (((s >>> 8) & 0xFF) / 255f) * 100f;
            c.drawRect(x, baseY - bh, x + bw, baseY + 200f, p);
            x += bw + 4f;
        }
    }

    private void drawClouds(PluginCanvas c, PluginPaint p, float xOff, int height) {
        long s = 0xCAFEBABEDEADL;
        for (int i = 0; i < 5; i++) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            float cxx = xOff + ((s & 0xFFFF) / 65535f) * 800f;
            float cyy = 80f + (((s >>> 16) & 0xFF) / 255f) * (height * 0.45f);
            c.drawCircle(cxx,        cyy,        20f, p);
            c.drawCircle(cxx + 22f,  cyy - 6f,   16f, p);
            c.drawCircle(cxx - 22f,  cyy - 4f,   14f, p);
            c.drawCircle(cxx + 8f,   cyy + 10f,  15f, p);
        }
    }

    private void drawScoreTag(PluginCanvas c, float cx, float cy, int n) {
        PluginPaint bg = c.newPaint();
        bg.setColor(0xFFFFFFFF);
        c.drawRoundRect(cx - 36f, cy - 28f, cx + 36f, cy + 14f, 8f, bg);
        PluginPaint border = c.newPaint();
        border.setColor(0xFF101018);
        border.setStyle(PluginStyle.STROKE);
        border.setStrokeWidth(2f);
        c.drawRoundRect(cx - 36f, cy - 28f, cx + 36f, cy + 14f, 8f, border);
        PluginPaint t = c.newPaint();
        t.setColor(0xFF101018);
        t.setTextSize(28f);
        t.setTextAlign(1);
        c.drawText(Integer.toString(n), cx, cy + 8f, t);
    }

    private void drawMicHud(PluginCanvas c, int width, int height) {
        float padX = 24f;
        float barY = height - 38f;
        float barW = (width - padX * 2 - 80f) / 2f;
        // Label.
        PluginPaint lbl = c.newPaint();
        lbl.setColor(0xFF4A4030);
        lbl.setTextSize(14f);
        lbl.setTextAlign(1);
        canvasDrawDashedText(c, lbl, "CHIRP TO FLAP", width / 2f, height - 56f);
        // Left meter (RMS).
        float lvl = Math.min(1f, micRms * 6f);
        drawBarSegments(c, padX, barY, barW, lvl, false);
        // Mic icon centre.
        float micCx = width / 2f, micCy = barY;
        PluginPaint micRing = c.newPaint();
        micRing.setColor(0xFFFFFFFF);
        c.drawCircle(micCx, micCy, 22f, micRing);
        PluginPaint micRingB = c.newPaint();
        micRingB.setColor(0xFF101018);
        micRingB.setStyle(PluginStyle.STROKE);
        micRingB.setStrokeWidth(2f);
        c.drawCircle(micCx, micCy, 22f, micRingB);
        PluginPaint micBody = c.newPaint();
        boolean hot = micRms > Math.max(micBaseline * CHIRP_MULT, CHIRP_FLOOR);
        micBody.setColor(hot ? 0xFFE25656 : 0xFF333344);
        c.drawRoundRect(micCx - 6f, micCy - 10f, micCx + 6f, micCy + 4f, 4f, micBody);
        c.drawLine(micCx, micCy + 4f, micCx, micCy + 10f, micRingB);
        c.drawLine(micCx - 6f, micCy + 10f, micCx + 6f, micCy + 10f, micRingB);
        // Right meter (mirrored).
        drawBarSegments(c, width - padX - barW, barY, barW, lvl, true);
    }

    private void drawBarSegments(PluginCanvas c, float x0, float cy, float w, float level, boolean mirror) {
        int n = 6;
        float segW = (w - (n - 1) * 4f) / n;
        for (int i = 0; i < n; i++) {
            int idx = mirror ? (n - 1 - i) : i;
            boolean lit = idx < Math.round(level * n);
            int colour = lit ? (idx >= 4 ? 0xFFE25656 : (idx >= 2 ? 0xFFE3B544 : 0xFF66CC66))
                             : 0xFF22624A;
            PluginPaint seg = c.newPaint();
            seg.setColor(colour);
            float sx = x0 + i * (segW + 4f);
            float h = 6f + (idx + 1) * 1.5f;
            c.drawRoundRect(sx, cy - h, sx + segW, cy + h, 2f, seg);
        }
    }

    private void canvasDrawDashedText(PluginCanvas c, PluginPaint p, String text, float cx, float cy) {
        // Centred text with a dash on each side, to match the screenshot's
        // "— CHIRP TO FLAP —" treatment without needing a font with
        // emdash kerning support.
        p.setColor(p == null ? 0xFF4A4030 : 0xFF4A4030);
        c.drawText("—  " + text + "  —", cx, cy, p);
    }

    private void drawCentreCard(PluginCanvas c, int width, int height,
                                String title, String sub) {
        float cx = width / 2f;
        float cardY = height * 0.42f;
        float halfW = Math.min(280f, width * 0.42f);
        PluginPaint shadow = c.newPaint();
        shadow.setColor(0x77000000);
        c.drawRoundRect(cx - halfW + 4, cardY + 4, cx + halfW + 4, cardY + 110, 14f, shadow);
        PluginPaint card = c.newPaint();
        card.setColor(0xFFFFFFFF);
        c.drawRoundRect(cx - halfW, cardY, cx + halfW, cardY + 106, 14f, card);
        PluginPaint cb = c.newPaint();
        cb.setColor(0xFF101018);
        cb.setStyle(PluginStyle.STROKE);
        cb.setStrokeWidth(2f);
        c.drawRoundRect(cx - halfW, cardY, cx + halfW, cardY + 106, 14f, cb);
        PluginPaint t = c.newPaint();
        t.setColor(0xFFE25656);
        t.setTextSize(22f);
        t.setTextAlign(1);
        c.drawText(title, cx, cardY + 40f, t);
        PluginPaint s = c.newPaint();
        s.setColor(0xFF4A4030);
        s.setTextSize(13f);
        s.setTextAlign(1);
        c.drawText(sub, cx, cardY + 72f, s);
    }
}
