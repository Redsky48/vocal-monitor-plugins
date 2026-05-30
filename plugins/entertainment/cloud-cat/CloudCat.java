package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;

import java.util.Map;

/**
 * Cloud Cat — a finger-steered cloud jumper.
 *
 * A cat bounces endlessly up a tower of clouds. Some clouds are solid and
 * hold; some crumble and fall the moment you push off them, so you have to
 * keep moving. Steer left/right by pressing and dragging your finger across
 * the screen — the cat chases your touch. Collect crystals and gold coins
 * sprinkled on the clouds, and every so often, when you climb high enough, a
 * gift drops in for a big bonus. Spend your loot in the shop on hats and
 * outfits that show up on the cat as you play.
 *
 * Touch only — no microphone. Built on the gamekit GamePluginBase (juice,
 * particles, frame timing), which is bundled into this plugin's .dex at
 * build time. Crystals, gold and unlocked cosmetics persist for the session.
 */
public final class CloudCat extends GamePluginBase {

    // ── States ──
    private static final int MENU = 0, PLAYING = 1, OVER = 2, SHOP = 3;
    private int state = MENU;
    private int shopReturn = MENU;

    // ── Cat physics (screen px; tuned constants are pre-scale) ──
    private float catX, catY, catVX, catVY;
    private static final float G = 1500f;      // gravity px/s² (×scale)
    private static final float JUMP = 690f;    // bounce up speed (×scale)
    private static final float MAXVX = 320f;   // max horizontal speed (×scale)
    private float catR = 16f;

    // ── Clouds (parallel arrays, recycled) ──
    private static final int N = 14;
    private static final int SOLID = 0, FALLER = 1;
    private static final int IT_NONE = 0, IT_CRYSTAL = 1, IT_GOLD = 2, IT_GIFT = 3;
    private final float[] clX = new float[N];
    private final float[] clY = new float[N];
    private final float[] clW = new float[N];
    private final int[] clType = new int[N];
    private final boolean[] clFalling = new boolean[N];
    private final float[] clFallV = new float[N];
    private final int[] clItem = new int[N];
    private final boolean[] clTaken = new boolean[N];
    private boolean cloudsReady = false;

    // ── Progress / economy ──
    private float climbPx = 0f;
    private int runCoins = 0;
    private int bestM = 0;
    private int gold = 0, crystals = 0;          // persist for the session
    private float nextGiftM = 120f;
    private boolean pendingGift = false;

    // ── Touch ──
    private boolean touching = false;
    private float touchX = 0f;

    private long rng = 0x2545F4914F6CDD1DL;
    private float lastW = 0f, lastH = 0f;

    // ── Shop catalogue ──
    private static final String[] HAT_NAME = { "None", "Cap", "Bow", "Crown", "Wizard" };
    private static final int[] HAT_GOLD    = { 0, 25, 30, 0, 0 };
    private static final int[] HAT_CRYS    = { 0, 0, 0, 12, 18 };
    private static final String[] CLO_NAME = { "Classic", "Pink", "Sky", "Galaxy" };
    private static final int[] CLO_GOLD    = { 0, 18, 18, 0 };
    private static final int[] CLO_CRYS    = { 0, 0, 0, 20 };
    private static final int[] CLO_COL     = { 0xFFF2A65A, 0xFFEF8FB6, 0xFF6FA8FF, 0xFF8A6BE0 };
    private final boolean[] hatOwned = { true, false, false, false, false };
    private final boolean[] cloOwned = { true, false, false, false };
    private int hatEq = 0, cloEq = 0;

    @Override
    protected void onInit(int sr) {
        state = MENU;
        cloudsReady = false;
        gold = 0; crystals = 0; bestM = 0;
        hatEq = 0; cloEq = 0;
        java.util.Arrays.fill(hatOwned, false); hatOwned[0] = true;
        java.util.Arrays.fill(cloOwned, false); cloOwned[0] = true;
    }

    // ═══════════════════════ Loop ═══════════════════════
    @Override
    public void render(PluginCanvas canvas, int width, int height, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        if (sky == null) initPaints(canvas);
        if (width < 80 || height < 80) return;
        beginFrame(width, height, timeMs, streams);
        lastW = width; lastH = height;
        catR = 16f * scale;

        if (state == PLAYING) step(dt, width, height);

        drawSky(canvas, width, height);
        if (state == PLAYING || state == OVER) {
            canvas.save();
            juice.applyShake(canvas);        // shake the world, not the UI
            drawClouds(canvas);
            drawCat(canvas, catX, catY, catR, catVX >= 0 ? 1f : -1f);
            canvas.restore();
        }
        particles.draw(canvas);
        if (state == PLAYING || state == OVER) drawHud(canvas, width);
        juice.drawOverlay(canvas, width, height);   // flash / score pops

        if (state == MENU) drawMenu(canvas, width, height);
        else if (state == OVER) drawOver(canvas, width, height);
        else if (state == SHOP) drawShop(canvas, width, height);
    }

    // ═══════════════════════ Touch ═══════════════════════
    @Override
    public void onTouchDown(float x, float y) {
        switch (state) {
            case MENU:
                if (in(rPlay, x, y)) startRun();
                else if (in(rShop, x, y)) { shopReturn = MENU; state = SHOP; }
                break;
            case OVER:
                if (in(rPlay, x, y)) startRun();
                else if (in(rShop, x, y)) { shopReturn = OVER; state = SHOP; }
                break;
            case SHOP:
                handleShopTap(x, y);
                break;
            case PLAYING:
                touching = true; touchX = x;
                break;
        }
    }

    @Override public void onTouchMove(float x, float y) {
        if (state == PLAYING) { touching = true; touchX = x; }
    }
    @Override public void onTouchUp(float x, float y) { touching = false; }

    private void handleShopTap(float x, float y) {
        if (in(rBack, x, y)) { state = shopReturn; return; }
        for (int i = 0; i < HAT_NAME.length; i++)
            if (in(rHat[i], x, y)) { buyOrEquip(true, i); return; }
        for (int i = 0; i < CLO_NAME.length; i++)
            if (in(rClo[i], x, y)) { buyOrEquip(false, i); return; }
    }

    private void buyOrEquip(boolean hat, int i) {
        boolean[] owned = hat ? hatOwned : cloOwned;
        if (owned[i]) { if (hat) hatEq = i; else cloEq = i; return; }
        int cg = hat ? HAT_GOLD[i] : CLO_GOLD[i];
        int cc = hat ? HAT_CRYS[i] : CLO_CRYS[i];
        if (gold >= cg && crystals >= cc) {
            gold -= cg; crystals -= cc; owned[i] = true;
            if (hat) hatEq = i; else cloEq = i;
            juice.flash(0.25f, 0x553FD07A);
        } else {
            juice.shake(4f, 0.2f);   // can't afford — little nudge
        }
    }

    // ═══════════════════════ Simulation ═══════════════════════
    private void startRun() {
        if (lastW <= 0f) return;
        float W = lastW, H = lastH;
        climbPx = 0f; runCoins = 0;
        nextGiftM = 120f; pendingGift = false;
        catX = W * 0.5f; catY = H * 0.5f; catVX = 0f; catVY = 0f;
        // First cloud right under the cat, then fill upward.
        placeCloud(0, W * 0.5f, H * 0.62f, SOLID, IT_NONE);
        float topY = H * 0.62f;
        for (int i = 1; i < N; i++) {
            topY -= gap();
            placeCloudRandom(i, topY, W);
        }
        cloudsReady = true;
        touching = false;
        state = PLAYING;
    }

    private float gap() { return (74f + rnd(40)) * scale; }

    private void placeCloud(int i, float cx, float cy, int type, int item) {
        clX[i] = cx; clY[i] = cy; clW[i] = (58f + rnd(26)) * scale;
        clType[i] = type; clFalling[i] = false; clFallV[i] = 0f;
        clItem[i] = item; clTaken[i] = false;
    }

    private void placeCloudRandom(int i, float cy, float W) {
        float w = (58f + rnd(26)) * scale;
        float cx = w * 0.6f + rnd((int) Math.max(1, W - w * 1.2f));
        int type = rnd(100) < 32 ? FALLER : SOLID;
        int item;
        if (pendingGift) { item = IT_GIFT; pendingGift = false; }
        else {
            int r = rnd(100);
            item = r < 13 ? IT_CRYSTAL : r < 46 ? IT_GOLD : IT_NONE;
        }
        clX[i] = cx; clY[i] = cy; clW[i] = w;
        clType[i] = type; clFalling[i] = false; clFallV[i] = 0f;
        clItem[i] = item; clTaken[i] = false;
    }

    private void step(float dtSec, float W, float H) {
        float s = scale;
        // Horizontal: chase the finger; otherwise glide to a stop.
        if (touching) {
            float want = (touchX - catX) * 6f;
            catVX = clamp(want, -MAXVX * s, MAXVX * s);
        } else {
            catVX *= 0.90f;
        }
        catX += catVX * dtSec;
        if (catX < -catR) catX = W + catR;
        else if (catX > W + catR) catX = -catR;

        // Vertical: gravity + bounce on clouds.
        catVY += G * s * dtSec;
        catY += catVY * dtSec;

        float catBottom = catY + catR;
        float prevBottom = catBottom - catVY * dtSec;   // feet position before this step
        if (catVY > 0f) {
            for (int i = 0; i < N; i++) {
                if (clFalling[i]) continue;
                float top = clY[i];
                // Swept test: did the cat's feet cross this cloud top this frame?
                // (robust even when the cat is falling fast enough to skip a thin band)
                if (prevBottom <= top + 6f * s && catBottom >= top
                        && Math.abs(catX - clX[i]) < clW[i] * 0.5f + catR * 0.4f) {
                    catVY = -JUMP * s;
                    catY = top - catR;
                    if (clType[i] == FALLER) { clFalling[i] = true; clFallV[i] = 30f * s; }
                    break;
                }
            }
        }

        // Falling clouds drop away.
        for (int i = 0; i < N; i++) {
            if (clFalling[i]) {
                clFallV[i] += G * s * 0.6f * dtSec;
                clY[i] += clFallV[i] * dtSec;
            }
        }

        // Scroll the world down when the cat climbs past the line.
        float scrollLine = H * 0.42f;
        if (catY < scrollLine) {
            float d = scrollLine - catY;
            catY = scrollLine;
            climbPx += d;
            for (int i = 0; i < N; i++) clY[i] += d;
            // Gift schedule.
            float m = climbPx / (8f * s);
            if (!pendingGift && m >= nextGiftM) { pendingGift = true; nextGiftM += 120f; }
        }

        // Recycle clouds that fell below the screen.
        for (int i = 0; i < N; i++) {
            if (clY[i] > H + 30f * s) {
                float minY = Float.MAX_VALUE;
                for (int j = 0; j < N; j++) if (clY[j] < minY) minY = clY[j];
                placeCloudRandom(i, minY - gap(), W);
            }
        }

        // Collect items.
        for (int i = 0; i < N; i++) {
            if (clTaken[i] || clItem[i] == IT_NONE) continue;
            float ix = clX[i], iy = clY[i] - 18f * s;
            float dx = catX - ix, dy = catY - iy;
            if (dx * dx + dy * dy < (catR + 11f * s) * (catR + 11f * s)) {
                collect(clItem[i], ix, iy);
                clTaken[i] = true;
            }
        }

        // Death.
        if (catY - catR > H + 6f * s) {
            int m = (int) (climbPx / (8f * s));
            if (m > bestM) bestM = m;
            juice.shake(7f, 0.4f);
            juice.flash(0.3f, 0x55E25656);
            state = OVER;
        }
    }

    private void collect(int item, float x, float y) {
        if (item == IT_CRYSTAL) {
            crystals++; runCoins++;
            particles.burst(x, y, 12, COL_CRYSTAL);
        } else if (item == IT_GOLD) {
            gold++; runCoins++;
            particles.burst(x, y, 12, COL_GOLD);
        } else if (item == IT_GIFT) {
            gold += 30; crystals += 8;
            particles.burst(x, y, 36, COL_GIFT);
            particles.burst(x, y, 24, COL_GOLD);
            juice.flash(0.35f, 0x553FD0C0);
            juice.scorePop("GIFT!  +30g +8", x, y - 10f * scale, COL_GIFT);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    private int rnd(int bound) {
        rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
        return (int) ((rng >>> 1) % Math.max(1, bound));
    }

    // ═══════════════════════ Drawing ═══════════════════════
    private static final int COL_SKY_TOP = 0xFF8FD3FF;
    private static final int COL_SKY_BOT = 0xFFE9F6FF;
    private static final int COL_CLOUD   = 0xFFFFFFFF;
    private static final int COL_CLOUD_SH = 0xFFC9DEF0;
    private static final int COL_FALLER  = 0xFFB9C2CC;
    private static final int COL_CRYSTAL = 0xFF49C6E5;
    private static final int COL_GOLD    = 0xFFF5C842;
    private static final int COL_GIFT    = 0xFFEF5DA8;
    private static final int COL_INK     = 0xFF20242B;
    private static final int COL_TEXT    = 0xFF20242B;
    private static final int COL_TEXT_L  = 0xFFFFFFFF;
    private static final int COL_PANEL   = 0xF21A1F27;
    private static final int COL_BTN     = 0xFF2E3742;
    private static final int COL_GREEN   = 0xFF3FD07A;

    private PluginPaint sky, p, txt, txtC, ink;
    private PluginPath path;

    private void initPaints(PluginCanvas c) {
        sky = c.newPaint(); p = c.newPaint(); txt = c.newPaint();
        txtC = c.newPaint(); ink = c.newPaint(); path = c.newPath();
    }

    private void drawSky(PluginCanvas canvas, float W, float H) {
        int bands = 18;
        for (int i = 0; i < bands; i++) {
            float t = i / (float) (bands - 1);
            sky.setColor(mix(COL_SKY_TOP, COL_SKY_BOT, t)).setStyle(PluginStyle.FILL);
            canvas.drawRect(0, H * i / bands, W, H * (i + 1) / bands + 1, sky);
        }
    }

    private void drawClouds(PluginCanvas canvas) {
        if (!cloudsReady) return;
        float s = scale;
        for (int i = 0; i < N; i++) {
            float cx = clX[i], cy = clY[i], w = clW[i];
            boolean faller = clType[i] == FALLER;
            int body = faller ? COL_FALLER : COL_CLOUD;
            // Soft shadow then puffy body (cluster of circles).
            p.setColor(COL_CLOUD_SH).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(cx - w * 0.5f, cy + 2f * s, cx + w * 0.5f, cy + 11f * s, 6f * s, p);
            p.setColor(body);
            canvas.drawCircle(cx - w * 0.28f, cy + 4f * s, 8f * s, p);
            canvas.drawCircle(cx + w * 0.28f, cy + 4f * s, 8f * s, p);
            canvas.drawCircle(cx, cy, 11f * s, p);
            canvas.drawRoundRect(cx - w * 0.5f, cy + 1f * s, cx + w * 0.5f, cy + 9f * s, 5f * s, p);
            if (faller) {   // crumble hint — three dots
                p.setColor(0xFF8A97A4);
                for (int k = -1; k <= 1; k++)
                    canvas.drawCircle(cx + k * 7f * s, cy + 5f * s, 1.4f * s, p);
            }
            // Item floating above.
            if (!clTaken[i] && clItem[i] != IT_NONE)
                drawItem(canvas, clItem[i], cx, cy - 18f * s, s);
        }
    }

    private void drawItem(PluginCanvas canvas, int item, float x, float y, float s) {
        float bob = (float) Math.sin(lastMsBob() + x) * 2f * s;
        y += bob;
        if (item == IT_GOLD) {
            p.setColor(COL_GOLD).setStyle(PluginStyle.FILL);
            canvas.drawCircle(x, y, 7f * s, p);
            p.setColor(0xFFFFE9A8);
            canvas.drawCircle(x - 1.5f * s, y - 1.5f * s, 2.4f * s, p);
        } else if (item == IT_CRYSTAL) {
            p.setColor(COL_CRYSTAL).setStyle(PluginStyle.FILL);
            tri(canvas, x, y - 8f * s, x - 6f * s, y, x + 6f * s, y, p);
            tri(canvas, x, y + 8f * s, x - 6f * s, y, x + 6f * s, y, p);
            p.setColor(0xFFBDEBF7);
            tri(canvas, x, y - 8f * s, x - 2.5f * s, y, x + 1f * s, y, p);
        } else if (item == IT_GIFT) {
            p.setColor(COL_GIFT).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(x - 8f * s, y - 7f * s, x + 8f * s, y + 8f * s, 2f * s, p);
            p.setColor(0xFFFFFFFF);
            canvas.drawRect(x - 1.4f * s, y - 7f * s, x + 1.4f * s, y + 8f * s, p);
            canvas.drawRect(x - 8f * s, y - 1.4f * s, x + 8f * s, y + 1.4f * s, p);
            p.setColor(COL_GIFT);
            canvas.drawCircle(x - 3.5f * s, y - 8f * s, 2.6f * s, p);
            canvas.drawCircle(x + 3.5f * s, y - 8f * s, 2.6f * s, p);
        }
    }

    private float lastMsBob() { return lastMs * 0.005f; }

    // Draw the cat with the equipped outfit + hat.
    private void drawCat(PluginCanvas canvas, float cx, float cy, float r, float face) {
        int body = CLO_COL[cloEq];
        // Tail.
        p.setColor(body).setStyle(PluginStyle.STROKE).setStrokeWidth(r * 0.34f);
        canvas.drawLine(cx - face * r * 0.7f, cy + r * 0.5f,
                cx - face * r * 1.3f, cy + r * 0.0f, p);
        // Body.
        p.setStyle(PluginStyle.FILL).setColor(body);
        canvas.drawRoundRect(cx - r * 0.78f, cy - r * 0.1f, cx + r * 0.78f, cy + r * 0.95f, r * 0.5f, p);
        // Head.
        float hy = cy - r * 0.45f;
        canvas.drawCircle(cx, hy, r * 0.72f, p);
        // Ears.
        tri(canvas, cx - r * 0.62f, hy - r * 0.35f, cx - r * 0.18f, hy - r * 0.7f,
                cx - r * 0.12f, hy - r * 0.2f, p);
        tri(canvas, cx + r * 0.62f, hy - r * 0.35f, cx + r * 0.18f, hy - r * 0.7f,
                cx + r * 0.12f, hy - r * 0.2f, p);
        p.setColor(0xFFEF8FB6);   // inner ears
        tri(canvas, cx - r * 0.5f, hy - r * 0.34f, cx - r * 0.24f, hy - r * 0.55f,
                cx - r * 0.2f, hy - r * 0.26f, p);
        tri(canvas, cx + r * 0.5f, hy - r * 0.34f, cx + r * 0.24f, hy - r * 0.55f,
                cx + r * 0.2f, hy - r * 0.26f, p);
        // Eyes + nose.
        ink.setColor(COL_INK).setStyle(PluginStyle.FILL);
        canvas.drawCircle(cx - r * 0.26f + face * r * 0.06f, hy, r * 0.1f, ink);
        canvas.drawCircle(cx + r * 0.26f + face * r * 0.06f, hy, r * 0.1f, ink);
        p.setColor(0xFFE9728F);
        canvas.drawCircle(cx, hy + r * 0.18f, r * 0.07f, p);
        // Hat.
        drawHat(canvas, cx, hy - r * 0.7f, r, hatEq, face);
    }

    private void drawHat(PluginCanvas canvas, float cx, float topY, float r, int hat, float face) {
        switch (hat) {
            case 1: // Cap
                p.setColor(0xFF5B8DEF).setStyle(PluginStyle.FILL);
                canvas.drawRoundRect(cx - r * 0.55f, topY - r * 0.28f, cx + r * 0.55f, topY + r * 0.2f, r * 0.25f, p);
                canvas.drawRoundRect(cx + face * r * 0.2f, topY + r * 0.06f,
                        cx + face * r * 0.95f, topY + r * 0.22f, r * 0.1f, p);
                break;
            case 2: // Bow
                p.setColor(0xFFE2557E).setStyle(PluginStyle.FILL);
                tri(canvas, cx, topY + r * 0.1f, cx - r * 0.55f, topY - r * 0.18f, cx - r * 0.55f, topY + r * 0.38f, p);
                tri(canvas, cx, topY + r * 0.1f, cx + r * 0.55f, topY - r * 0.18f, cx + r * 0.55f, topY + r * 0.38f, p);
                canvas.drawCircle(cx, topY + r * 0.1f, r * 0.16f, p);
                break;
            case 3: // Crown
                p.setColor(0xFFF5C842).setStyle(PluginStyle.FILL);
                canvas.drawRect(cx - r * 0.5f, topY + r * 0.0f, cx + r * 0.5f, topY + r * 0.28f, p);
                tri(canvas, cx - r * 0.5f, topY + r * 0.04f, cx - r * 0.5f, topY - r * 0.4f, cx - r * 0.18f, topY + r * 0.04f, p);
                tri(canvas, cx, topY + r * 0.04f, cx, topY - r * 0.5f, cx + r * 0.18f, topY + r * 0.04f, p);
                tri(canvas, cx + r * 0.5f, topY + r * 0.04f, cx + r * 0.5f, topY - r * 0.4f, cx + r * 0.18f, topY + r * 0.04f, p);
                break;
            case 4: // Wizard
                p.setColor(0xFF7A5BD0).setStyle(PluginStyle.FILL);
                tri(canvas, cx, topY - r * 0.85f, cx - r * 0.55f, topY + r * 0.25f, cx + r * 0.55f, topY + r * 0.25f, p);
                p.setColor(0xFFF5C842);
                canvas.drawCircle(cx + r * 0.1f, topY - r * 0.2f, r * 0.09f, p);
                canvas.drawCircle(cx - r * 0.15f, topY + r * 0.05f, r * 0.07f, p);
                break;
            default: break; // None
        }
    }

    private void tri(PluginCanvas canvas, float x1, float y1, float x2, float y2,
                     float x3, float y3, PluginPaint paint) {
        path.reset();
        path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close();
        canvas.drawPath(path, paint);
    }

    private void drawHud(PluginCanvas canvas, float W) {
        int m = (int) (climbPx / (8f * scale));
        txt.setColor(COL_TEXT).setTextSize(15f).setTextAlign(0);
        canvas.drawText(m + " m", 12f, 24f, txt);
        // Gold + crystal counters (right).
        p.setColor(COL_GOLD).setStyle(PluginStyle.FILL);
        canvas.drawCircle(W - 96f, 19f, 6f, p);
        txt.setColor(COL_TEXT).setTextSize(13f).setTextAlign(0);
        canvas.drawText("" + gold, W - 86f, 24f, txt);
        p.setColor(COL_CRYSTAL);
        tri(canvas, W - 44f, 12f, W - 50f, 20f, W - 38f, 20f, p);
        tri(canvas, W - 44f, 26f, W - 50f, 20f, W - 38f, 20f, p);
        canvas.drawText("" + crystals, W - 32f, 24f, txt);
    }

    // ── Menu / Over / Shop ──
    private final float[] rPlay = new float[4], rShop = new float[4], rBack = new float[4];
    private final float[][] rHat = new float[5][4];
    private final float[][] rClo = new float[4][4];

    private void drawMenu(PluginCanvas canvas, float W, float H) {
        dim(canvas, W, H, 0x66000000);
        txtC.setColor(COL_INK).setTextSize(30f).setTextAlign(1);
        canvas.drawText("CLOUD CAT", W * 0.5f, H * 0.26f, txtC);
        drawCat(canvas, W * 0.5f, H * 0.4f, 26f * scale, 1f);
        button(canvas, rPlay, W * 0.5f - 80f, H * 0.56f, 160f, 44f, "PLAY", COL_GREEN, true);
        button(canvas, rShop, W * 0.5f - 80f, H * 0.56f + 54f, 160f, 40f, "SHOP", COL_BTN, false);
        walletLine(canvas, W, H * 0.56f + 116f);
        txtC.setColor(0xFF3A4350).setTextSize(11f).setTextAlign(1);
        canvas.drawText("drag your finger to steer the cat", W * 0.5f, H * 0.92f, txtC);
    }

    private void drawOver(PluginCanvas canvas, float W, float H) {
        dim(canvas, W, H, 0xAA000000);
        int m = (int) (climbPx / (8f * scale));
        txtC.setColor(COL_TEXT_L).setTextSize(26f).setTextAlign(1);
        canvas.drawText("GAME OVER", W * 0.5f, H * 0.30f, txtC);
        txtC.setTextSize(14f);
        canvas.drawText(m + " m   ·   best " + bestM + " m", W * 0.5f, H * 0.30f + 26f, txtC);
        canvas.drawText("this run: +" + runCoins + " collected", W * 0.5f, H * 0.30f + 46f, txtC);
        button(canvas, rPlay, W * 0.5f - 80f, H * 0.52f, 160f, 44f, "RETRY", COL_GREEN, true);
        button(canvas, rShop, W * 0.5f - 80f, H * 0.52f + 54f, 160f, 40f, "SHOP", COL_BTN, false);
        walletLine(canvas, W, H * 0.52f + 116f);
    }

    private void drawShop(PluginCanvas canvas, float W, float H) {
        dim(canvas, W, H, 0xE60E1018);
        txtC.setColor(COL_TEXT_L).setTextSize(22f).setTextAlign(1);
        canvas.drawText("SHOP", W * 0.5f, 40f, txtC);
        walletLine(canvas, W, 64f);

        float pad = 14f;
        float cellW = (W - pad * 2f - 8f * 4f) / 5f;
        float cellH = Math.min(cellW * 1.15f, 74f);
        // Hats row.
        txtC.setColor(0xFFAEB7C2).setTextSize(12f).setTextAlign(0);
        canvas.drawText("HATS", pad, 92f, txtC);
        float hy = 100f;
        for (int i = 0; i < HAT_NAME.length; i++) {
            float x = pad + i * (cellW + 8f);
            shopCell(canvas, rHat[i], x, hy, cellW, cellH, true, i,
                    HAT_NAME[i], HAT_GOLD[i], HAT_CRYS[i], hatOwned[i], hatEq == i);
        }
        // Clothes row.
        canvas.drawText("OUTFITS", pad, hy + cellH + 30f, txtC);
        float cy = hy + cellH + 38f;
        for (int i = 0; i < CLO_NAME.length; i++) {
            float x = pad + i * (cellW + 8f);
            shopCell(canvas, rClo[i], x, cy, cellW, cellH, false, i,
                    CLO_NAME[i], CLO_GOLD[i], CLO_CRYS[i], cloOwned[i], cloEq == i);
        }
        // Live preview.
        drawCat(canvas, W * 0.5f, cy + cellH + 64f, 28f * scale, 1f);
        button(canvas, rBack, W * 0.5f - 70f, H - 56f, 140f, 40f, "BACK", COL_BTN, false);
    }

    private void shopCell(PluginCanvas canvas, float[] rect, float x, float y, float w, float h,
                          boolean hat, int idx, String name, int cg, int cc,
                          boolean owned, boolean equipped) {
        setR(rect, x, y, x + w, y + h);
        int border = equipped ? COL_GREEN : (owned ? 0xFF5A6573 : 0xFF3A424E);
        p.setColor(0xFF222933).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x, y, x + w, y + h, 7f, p);
        p.setColor(border).setStyle(PluginStyle.STROKE).setStrokeWidth(equipped ? 2.4f : 1.2f);
        canvas.drawRoundRect(x, y, x + w, y + h, 7f, p);
        // Icon: a swatch (clothes) or a tiny hat preview.
        float icx = x + w * 0.5f, icy = y + h * 0.36f;
        if (hat) {
            p.setColor(0xFF3A424E).setStyle(PluginStyle.FILL);
            canvas.drawCircle(icx, icy, h * 0.2f, p);
            drawHat(canvas, icx, icy - h * 0.06f, h * 0.5f, idx, 1f);
        } else {
            p.setColor(CLO_COL[idx]).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(icx - w * 0.22f, icy - h * 0.18f, icx + w * 0.22f, icy + h * 0.18f, 5f, p);
        }
        // Label + price/owned/equip.
        txtC.setColor(COL_TEXT_L).setTextSize(10f).setTextAlign(1);
        canvas.drawText(name, icx, y + h * 0.68f, txtC);
        String tag = equipped ? "EQUIPPED" : owned ? "tap to wear"
                : (cc > 0 ? cc + " cr" : cg + " g");
        txtC.setColor(equipped ? COL_GREEN : owned ? 0xFFAEB7C2
                : (gold >= cg && crystals >= cc ? COL_GOLD : 0xFFB55B5B)).setTextSize(9.5f);
        canvas.drawText(tag, icx, y + h * 0.9f, txtC);
    }

    private void walletLine(PluginCanvas canvas, float W, float y) {
        p.setColor(COL_GOLD).setStyle(PluginStyle.FILL);
        canvas.drawCircle(W * 0.5f - 44f, y - 4f, 6f, p);
        txtC.setColor(COL_TEXT_L).setTextSize(13f).setTextAlign(0);
        canvas.drawText("" + gold, W * 0.5f - 34f, y, txtC);
        p.setColor(COL_CRYSTAL);
        tri(canvas, W * 0.5f + 16f, y - 11f, W * 0.5f + 10f, y - 3f, W * 0.5f + 22f, y - 3f, p);
        tri(canvas, W * 0.5f + 16f, y + 5f, W * 0.5f + 10f, y - 3f, W * 0.5f + 22f, y - 3f, p);
        canvas.drawText("" + crystals, W * 0.5f + 28f, y, txtC);
    }

    private void button(PluginCanvas canvas, float[] rect, float x, float y, float w, float h,
                        String label, int col, boolean primary) {
        setR(rect, x, y, x + w, y + h);
        p.setColor(col).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x, y, x + w, y + h, h * 0.32f, p);
        if (!primary) {
            p.setColor(0xFF566273).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
            canvas.drawRoundRect(x, y, x + w, y + h, h * 0.32f, p);
        }
        txtC.setColor(primary ? 0xFF0E2417 : COL_TEXT_L).setTextSize(16f).setTextAlign(1);
        canvas.drawText(label, x + w * 0.5f, y + h * 0.63f, txtC);
    }

    private void dim(PluginCanvas canvas, float W, float H, int col) {
        sky.setColor(col).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, sky);
    }

    private static void setR(float[] r, float x0, float y0, float x1, float y1) {
        r[0] = x0; r[1] = y0; r[2] = x1; r[3] = y1;
    }
    private static boolean in(float[] r, float x, float y) {
        return x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
