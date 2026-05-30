package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;

import java.util.Map;

/**
 * Vowel Snake — sing the snake around the board.
 *
 * Steer with five sung vowels, detected live from the microphone by the
 * same formant analysis the Formant Tracker uses (14th-order weighted
 * linear prediction + Durand-Kerner root finding → F1/F2), then classified
 * to the nearest cardinal vowel on the F1-F2 map:
 *
 *     I  →  up        A  →  down
 *     E  →  left      U  →  right
 *     O  →  BOOST  (the extra action — a burst of speed while it lasts)
 *
 * O is the "papildus darbība": hold the [o] vowel and the snake dashes,
 * draining a boost meter that recharges when you stop. It's a genuine
 * vowel workout disguised as a game — to turn cleanly you have to hit a
 * clear, sustained vowel target, exactly the skill the Formant Tracker
 * trains, but now there's a snake to feed.
 *
 * Built on the gamekit GamePluginBase (juice / particles / frame timing);
 * the gamekit classes are bundled into this plugin's .dex at build time.
 * Audio-only, no model — pure DSP, so it runs anywhere the host streams
 * a "waveform".
 */
public final class VowelSnake extends GamePluginBase {

    // ── Vowel formant references (F1, F2) — match Formant Tracker ──
    // order: I, E, A, O, U
    private static final int V_I = 0, V_E = 1, V_A = 2, V_O = 3, V_U = 4;
    private static final float[] VF1 = { 300f, 480f, 700f, 500f, 320f };
    private static final float[] VF2 = { 2200f, 1800f, 1100f, 900f, 800f };
    private static final float[] LVF1 = new float[5];
    private static final float[] LVF2 = new float[5];
    static {
        for (int i = 0; i < 5; i++) {
            LVF1[i] = (float) Math.log(VF1[i]);
            LVF2[i] = (float) Math.log(VF2[i]);
        }
    }

    // ── Directions ──
    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;
    private static final int[] DX = { 0, 0, -1, 1 };
    private static final int[] DY = { -1, 1, 0, 0 };

    // ── Game state ──
    private static final int READY = 0, PLAYING = 1, OVER = 2;
    private int state = READY;

    private int cols = 0, rows = 0, cap = 0;
    private int[] segCell;     // circular queue of cell ids (head=newest)
    private boolean[] occ;     // occupancy by cell id
    private int qHead, qTail, len;
    private int curDir, pendingDir;
    private int foodCell = -1;
    private int score = 0, best = 0;

    private float tickAcc = 0f;
    private float boost = 1f;          // 0..1 energy
    private boolean boostHeld = false; // O sung this frame
    private boolean boosting = false;

    private long rng = 0x9E3779B97F4A7C15L;

    // Vowel confirm (debounce raw classification jitter).
    private int curVowel = -1;     // confirmed vowel this frame (-1 none)
    private int lastRaw = -1, rawRun = 0;
    private float voiceLevel = 0f; // smoothed RMS for the HUD mic meter

    @Override
    protected void onInit(int sr) {
        state = READY;
        cols = rows = cap = 0;
        segCell = null; occ = null;
        score = 0; boost = 1f;
        curVowel = -1; lastRaw = -1; rawRun = 0; voiceLevel = 0f;
        java.util.Arrays.fill(audioRing, 0f);
        ringW = 0;
        f1 = f2 = 0f;
    }

    // ═══════════════════════ Render / loop ═══════════════════════
    @Override
    public void render(PluginCanvas canvas, int width, int height, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        if (bg == null) initPaints(canvas);
        if (width < 80 || height < 80) return;
        beginFrame(width, height, timeMs, streams);

        layout(width, height);                 // (re)builds board on resize
        prepareWindow(streams);
        boolean fresh = analyseFormants();     // updates f1/f2, voiceLevel
        readVowel(fresh);                      // sets curVowel + boostHeld
        handleInput();
        step(dt);

        draw(canvas, width, height);
        particles.draw(canvas);
        juice.applyShake(canvas);
        juice.drawOverlay(canvas, width, height);
    }

    @Override
    public void onTouchDown(float x, float y) {
        if (state == READY) startGame();
        else if (state == OVER) { reset(); startGame(); }
    }

    private void startGame() {
        if (cols <= 0) return;
        reset();
        state = PLAYING;
    }

    private void reset() {
        java.util.Arrays.fill(occ, false);
        qHead = 0; qTail = 0; len = 0;
        score = 0; tickAcc = 0f; boost = 1f; boosting = false;
        curDir = RIGHT; pendingDir = RIGHT;
        // Start a length-3 snake centred, heading right.
        int cy = rows / 2, cx = cols / 2;
        for (int i = 2; i >= 0; i--) pushHead(cell(cx - i, cy));
        spawnFood();
        state = PLAYING;
    }

    // ─── Board layout ───
    private float boardX0, boardY0, cellPx;
    private static final float HUD_TOP = 44f, LEGEND_BOT = 26f;

    private void layout(int width, int height) {
        float availW = width - 8f;
        float availH = height - HUD_TOP - LEGEND_BOT;
        if (availH < 40f) availH = height * 0.7f;
        int targetCell = 24;
        int nc = Math.max(8, (int) (availW / targetCell));
        int nr = Math.max(8, (int) (availH / targetCell));
        if (nc != cols || nr != rows || segCell == null) {
            cols = nc; rows = nr; cap = cols * rows;
            segCell = new int[cap + 1];
            occ = new boolean[cap];
            state = READY;           // board changed — back to title
        }
        cellPx = Math.min(availW / cols, availH / rows);
        float boardW = cellPx * cols, boardH = cellPx * rows;
        boardX0 = (width - boardW) * 0.5f;
        boardY0 = HUD_TOP + (availH - boardH) * 0.5f;
    }

    private int cell(int x, int y) { return y * cols + x; }
    private int cellX(int c) { return c % cols; }
    private int cellY(int c) { return c / cols; }

    private void pushHead(int c) {
        qHead = (qHead + 1) % (cap + 1);
        segCell[qHead] = c;
        occ[c] = true;
        len++;
        if (len == 1) qTail = qHead;     // first segment
    }
    private void popTail() {
        occ[segCell[qTail]] = false;
        qTail = (qTail + 1) % (cap + 1);
        len--;
    }

    private void spawnFood() {
        int empties = cap - len;
        if (empties <= 0) { foodCell = -1; return; }
        int k = nextRand(empties);
        for (int c = 0; c < cap; c++) {
            if (occ[c]) continue;
            if (k-- == 0) { foodCell = c; return; }
        }
    }

    private int nextRand(int bound) {
        rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
        long r = (rng >>> 1) % bound;
        return (int) r;
    }

    // ═══════════════════════ Input ═══════════════════════
    private void readVowel(boolean fresh) {
        boostHeld = false;
        curVowel = -1;
        boolean voiced = voiceLevel > 0.012f && f1 > 0f && f2 > 0f;
        if (!voiced || !fresh) { lastRaw = -1; rawRun = 0; return; }
        int raw = classifyVowel(f1, f2);
        if (raw == lastRaw) rawRun++;
        else { lastRaw = raw; rawRun = 1; }
        if (rawRun >= 2) {               // confirmed for ~2 frames
            curVowel = raw;
            if (raw == V_O) boostHeld = true;
        }
    }

    private int classifyVowel(float ff1, float ff2) {
        float lf1 = (float) Math.log(ff1), lf2 = (float) Math.log(ff2);
        int best = -1; float bestD = Float.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            float d1 = lf1 - LVF1[i], d2 = lf2 - LVF2[i];
            float d = d1 * d1 + d2 * d2;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void handleInput() {
        if (state == READY) {
            // Any clear directional vowel starts the game.
            if (curVowel >= 0 && curVowel != V_O) { startGame(); requestDir(curVowel); }
            return;
        }
        if (state == OVER) {
            // A held vowel after the death settles restarts.
            if (overCooldown <= 0f && curVowel >= 0) { reset(); requestDir(curVowel); }
            return;
        }
        if (curVowel == V_O) return;     // boost handled in step()
        if (curVowel >= 0) requestDir(curVowel);
    }

    private void requestDir(int vowel) {
        int dir;
        switch (vowel) {
            case V_I: dir = UP;    break;
            case V_A: dir = DOWN;  break;
            case V_E: dir = LEFT;  break;
            case V_U: dir = RIGHT; break;
            default: return;             // O or none
        }
        // Reject a 180° reversal against the *current* heading.
        if ((dir == UP && curDir == DOWN) || (dir == DOWN && curDir == UP) ||
            (dir == LEFT && curDir == RIGHT) || (dir == RIGHT && curDir == LEFT)) return;
        pendingDir = dir;
    }

    // ═══════════════════════ Simulation ═══════════════════════
    private float overCooldown = 0f;

    private void step(float dtSec) {
        if (state != PLAYING) {
            if (overCooldown > 0f) overCooldown -= dtSec;
            // Boost meter still recharges on the title / over screen.
            boost = Math.min(1f, boost + dtSec / 4f);
            return;
        }

        // Boost energy bookkeeping.
        boosting = boostHeld && boost > 0.05f;
        if (boosting) boost = Math.max(0f, boost - dtSec / 2.2f);
        else          boost = Math.min(1f, boost + dtSec / 4f);

        // Tick interval: speeds up slightly with score, halves while boosting.
        float interval = Math.max(0.075f, 0.16f - score * 0.0025f);
        if (boosting) interval *= 0.5f;

        tickAcc += dtSec;
        // Cap iterations so a long stall (tab hidden) can't fast-forward.
        int guard = 0;
        while (tickAcc >= interval && guard++ < 4) {
            tickAcc -= interval;
            advance();
            if (state != PLAYING) break;
        }
    }

    private void advance() {
        curDir = pendingDir;
        int hc = segCell[qHead];
        int nx = cellX(hc) + DX[curDir];
        int ny = cellY(hc) + DY[curDir];
        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) { die(); return; }
        int nc = cell(nx, ny);
        boolean eating = (nc == foodCell);
        int tailCell = segCell[qTail];
        boolean collide = occ[nc] && !(!eating && nc == tailCell);
        if (collide) { die(); return; }

        if (!eating) popTail();
        pushHead(nc);
        if (eating) {
            score++;
            best = Math.max(best, score);
            float px = boardX0 + (nx + 0.5f) * cellPx;
            float py = boardY0 + (ny + 0.5f) * cellPx;
            particles.burst(px, py, 14, COL_FOOD);
            juice.impactRing(px, py, cellPx * 0.5f, COL_FOOD);
            spawnFood();
        }
    }

    private void die() {
        state = OVER;
        overCooldown = 1.0f;
        best = Math.max(best, score);
        int hc = len > 0 ? segCell[qHead] : 0;
        float px = boardX0 + (cellX(hc) + 0.5f) * cellPx;
        float py = boardY0 + (cellY(hc) + 0.5f) * cellPx;
        particles.burst(px, py, 28, COL_HEAD);
        juice.shake(8f, 0.4f);
        juice.flash(0.3f, 0x55E25656);
    }

    // ═══════════════════════ Formant DSP (from Formant Tracker) ═══════════════════════
    private static final int LPC_ORDER = 14;
    private static final int FRAME_SIZE = 1024;
    private static final int DEC_FACTOR = 4;
    private static final int DEC_SIZE = FRAME_SIZE / DEC_FACTOR;
    private final float[] audioRing = new float[FRAME_SIZE];
    private final float[] frame = new float[FRAME_SIZE];
    private final float[] frameDec = new float[DEC_SIZE];
    private final float[] lpcA = new float[LPC_ORDER + 1];
    private final float[] R = new float[LPC_ORDER + 1];
    private int ringW = 0;
    private float[] aaCoefs;
    private final double[] rootRe = new double[LPC_ORDER];
    private final double[] rootIm = new double[LPC_ORDER];
    private float f1 = 0f, f2 = 0f, f3 = 0f;

    private void prepareWindow(Map<String, float[]> streams) {
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 64) return;
        int n = wave.length;
        int start = n - FRAME_SIZE;
        if (start < 0) {
            int pad = -start;
            for (int i = 0; i < pad; i++) audioRing[i] = 0f;
            for (int i = 0; i < n; i++) audioRing[pad + i] = wave[i];
        } else {
            for (int i = 0; i < FRAME_SIZE; i++) audioRing[i] = wave[start + i];
        }
        ringW = 0;
    }

    // Returns true if it produced a fresh F1/F2 this frame.
    private boolean analyseFormants() {
        if (aaCoefs == null) aaCoefs = lowPassBiquad(5000f, 0.707f, sampleRate);
        float prev = 0f;
        double energy = 0, rawEnergy = 0;
        for (int i = 0; i < FRAME_SIZE; i++) {
            int idx = (ringW + i) % FRAME_SIZE;
            float raw = audioRing[idx];
            rawEnergy += raw * raw;
            float v = raw - 0.97f * prev;
            prev = raw;
            frame[i] = v;
            energy += v * v;
        }
        float rawRms = (float) Math.sqrt(rawEnergy / FRAME_SIZE);
        voiceLevel += 0.4f * (rawRms - voiceLevel);
        float rms = (float) Math.sqrt(energy / FRAME_SIZE);
        if (rms < 0.001f) return false;

        float s1a = 0f, s1b = 0f, s1c = 0f, s1d = 0f;
        float s2a = 0f, s2b = 0f, s2c = 0f, s2d = 0f;
        for (int i = 0; i < FRAME_SIZE; i++) {
            float x = frame[i];
            float y1 = aaCoefs[0] * x + aaCoefs[1] * s1a + aaCoefs[2] * s1b
                     - aaCoefs[3] * s1c - aaCoefs[4] * s1d;
            s1b = s1a; s1a = x; s1d = s1c; s1c = y1;
            float y2 = aaCoefs[0] * y1 + aaCoefs[1] * s2a + aaCoefs[2] * s2b
                     - aaCoefs[3] * s2c - aaCoefs[4] * s2d;
            s2b = s2a; s2a = y1; s2d = s2c; s2c = y2;
            frame[i] = y2;
        }
        for (int i = 0; i < DEC_SIZE; i++) {
            float w = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (DEC_SIZE - 1)));
            frameDec[i] = frame[i * DEC_FACTOR] * w;
        }
        if (!wlp(frameDec, DEC_SIZE, LPC_ORDER, LPC_ORDER, lpcA)) {
            for (int k = 0; k <= LPC_ORDER; k++) {
                float sum = 0f;
                for (int i = k; i < DEC_SIZE; i++) sum += frameDec[i] * frameDec[i - k];
                R[k] = sum;
            }
            if (R[0] < 1e-9f) return false;
            float[] a = new float[LPC_ORDER + 1];
            float[] aPrev = new float[LPC_ORDER + 1];
            float Eerr = R[0];
            a[0] = 1f;
            for (int p = 1; p <= LPC_ORDER; p++) {
                float k = -R[p];
                for (int j = 1; j < p; j++) k -= a[j] * R[p - j];
                k /= Eerr;
                if (k > 0.99f) k = 0.99f; if (k < -0.99f) k = -0.99f;
                System.arraycopy(a, 0, aPrev, 0, p);
                a[p] = k;
                for (int j = 1; j < p; j++) a[j] = aPrev[j] + k * aPrev[p - j];
                Eerr *= 1f - k * k;
                if (Eerr < 1e-9f) Eerr = 1e-9f;
            }
            System.arraycopy(a, 0, lpcA, 0, LPC_ORDER + 1);
        }

        float decSr = sampleRate / (float) DEC_FACTOR;
        float[] candF = new float[LPC_ORDER];
        float[] candBw = new float[LPC_ORDER];
        int nCand = formantRoots(lpcA, LPC_ORDER, decSr, candF, candBw);
        if (nCand == 0) return false;
        for (int i = 1; i < nCand; i++) {
            float kf = candF[i], kbw = candBw[i];
            int j = i - 1;
            while (j >= 0 && candF[j] > kf) {
                candF[j + 1] = candF[j]; candBw[j + 1] = candBw[j]; j--;
            }
            candF[j + 1] = kf; candBw[j + 1] = kbw;
        }
        // Assign the two lowest sensible candidates to F1/F2 with light
        // continuity smoothing — enough for vowel classification.
        float[] newF = new float[3];
        boolean[] used = new boolean[nCand];
        float[] prevF = { f1, f2, f3 };
        boolean haveHistory = f1 > 0f && f2 > 0f;
        if (haveHistory) {
            for (int round = 0; round < 3; round++) {
                int bestSlot = -1, bestCand = -1; float bestDist = 350f;
                for (int slot = 0; slot < 3; slot++) {
                    if (prevF[slot] <= 0f || newF[slot] > 0f) continue;
                    for (int c = 0; c < nCand; c++) {
                        if (used[c]) continue;
                        float d = Math.abs(candF[c] - prevF[slot]);
                        if (d < bestDist) { bestDist = d; bestSlot = slot; bestCand = c; }
                    }
                }
                if (bestSlot < 0) break;
                newF[bestSlot] = candF[bestCand];
                used[bestCand] = true;
            }
        }
        for (int slot = 0; slot < 3; slot++) {
            if (newF[slot] > 0f) continue;
            for (int c = 0; c < nCand; c++) {
                if (used[c]) continue;
                float lo = slot == 0 ? 150f : slot == 1 ? 600f : 1700f;
                float hi = slot == 0 ? 1200f : slot == 1 ? 3200f : 5000f;
                if (candF[c] < lo || candF[c] > hi) continue;
                newF[slot] = candF[c]; used[c] = true; break;
            }
        }
        float coef = 0.35f;
        if (newF[0] > 0f) f1 = f1 == 0f ? newF[0] : f1 + coef * (newF[0] - f1);
        if (newF[1] > 0f) f2 = f2 == 0f ? newF[1] : f2 + coef * (newF[1] - f2);
        if (newF[2] > 0f) f3 = f3 == 0f ? newF[2] : f3 + coef * (newF[2] - f3);
        return f1 > 0f && f2 > 0f;
    }

    private boolean wlp(float[] x, int N, int p, int M, float[] aOut) {
        int n0 = Math.max(p, M);
        if (N <= n0 + 2) return false;
        double[][] C = new double[p][p];
        double[] b = new double[p];
        for (int n = n0; n < N; n++) {
            double w = 0;
            for (int k = 1; k <= M; k++) { double v = x[n - k]; w += v * v; }
            if (w <= 0) continue;
            double xn = x[n];
            for (int i = 1; i <= p; i++) {
                double xi = w * x[n - i];
                b[i - 1] -= xi * xn;
                for (int j = i; j <= p; j++) C[i - 1][j - 1] += xi * x[n - j];
            }
        }
        for (int i = 0; i < p; i++)
            for (int j = 0; j < i; j++) C[i][j] = C[j][i];
        double[] sol = new double[p];
        if (!solveLinear(C, b, p, sol)) return false;
        aOut[0] = 1f;
        for (int i = 0; i < p; i++) aOut[i + 1] = (float) sol[i];
        return true;
    }

    private boolean solveLinear(double[][] A, double[] b, int n, double[] out) {
        double[][] M = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) piv = r;
            if (Math.abs(M[piv][col]) < 1e-12) return false;
            double[] tmp = M[col]; M[col] = M[piv]; M[piv] = tmp;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = M[r][col] / M[col][col];
                for (int c = col; c <= n; c++) M[r][c] -= f * M[col][c];
            }
        }
        for (int i = 0; i < n; i++) out[i] = M[i][n] / M[i][i];
        return true;
    }

    private int formantRoots(float[] a, int p, float decSr, float[] outF, float[] outBw) {
        double zr = 1, zi = 0;
        for (int i = 0; i < p; i++) {
            rootRe[i] = zr; rootIm[i] = zi;
            double nr = zr * 0.4 - zi * 0.9, ni = zr * 0.9 + zi * 0.4;
            zr = nr; zi = ni;
        }
        for (int it = 0; it < 80; it++) {
            double maxd = 0;
            for (int i = 0; i < p; i++) {
                double pr = 1, pi = 0;
                for (int k = 1; k <= p; k++) {
                    double nr = pr * rootRe[i] - pi * rootIm[i] + a[k];
                    double ni = pr * rootIm[i] + pi * rootRe[i];
                    pr = nr; pi = ni;
                }
                double dr = 1, di = 0;
                for (int j = 0; j < p; j++) {
                    if (j == i) continue;
                    double ar = rootRe[i] - rootRe[j], ai = rootIm[i] - rootIm[j];
                    double nr = dr * ar - di * ai, ni = dr * ai + di * ar;
                    dr = nr; di = ni;
                }
                double den = dr * dr + di * di;
                if (den < 1e-30) continue;
                double qr = (pr * dr + pi * di) / den;
                double qi = (pi * dr - pr * di) / den;
                rootRe[i] -= qr; rootIm[i] -= qi;
                maxd = Math.max(maxd, Math.abs(qr) + Math.abs(qi));
            }
            if (maxd < 1e-10) break;
        }
        int n = 0;
        for (int i = 0; i < p && n < outF.length; i++) {
            if (rootIm[i] < 0) continue;
            double rr = rootRe[i], ii = rootIm[i];
            double r = Math.hypot(rr, ii);
            if (r >= 1) { rr /= r * r; ii /= r * r; r = 1 / r; }
            if (r <= 0 || r >= 1) continue;
            double th = Math.atan2(ii, rr);
            if (th < 0) th += 2 * Math.PI;
            float f = (float) (th * decSr / (2 * Math.PI));
            float bw = (float) (-Math.log(r) * decSr / Math.PI);
            if (f < 90f || f > 5500f || bw > 700f) continue;
            outF[n] = f; outBw[n] = bw; n++;
        }
        for (int i = 1; i < n; i++) {
            float kf = outF[i], kbw = outBw[i];
            int j = i - 1;
            while (j >= 0 && outF[j] > kf) {
                outF[j + 1] = outF[j]; outBw[j + 1] = outBw[j]; j--;
            }
            outF[j + 1] = kf; outBw[j + 1] = kbw;
        }
        return n;
    }

    private static float[] lowPassBiquad(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double a0 = 1 + alpha;
        return new float[] {
            (float) ((1 - cs) * 0.5 / a0),
            (float) ((1 - cs) / a0),
            (float) ((1 - cs) * 0.5 / a0),
            (float) (-2 * cs / a0),
            (float) ((1 - alpha) / a0),
        };
    }

    // ═══════════════════════ Drawing ═══════════════════════
    private static final int COL_BG     = 0xFF0E0F12;
    private static final int COL_BOARD  = 0xFF15171C;
    private static final int COL_GRID   = 0xFF1E2128;
    private static final int COL_BORDER = 0xFF2A2D34;
    private static final int COL_HEAD   = 0xFF66DD8A;
    private static final int COL_BODY   = 0xFF3FAE6B;
    private static final int COL_FOOD   = 0xFFE3B544;
    private static final int COL_TEXT   = 0xFFE6E6EA;
    private static final int COL_DIM    = 0xFF8A8B8F;
    private static final int COL_BOOST  = 0xFFEE8A2C;
    // Per-vowel accent for HUD.
    private static final int[] VOW_COL = { 0xFF5BD9E0, 0xFFA0E060, 0xFFE34855, 0xFFEE8A2C, 0xFFC080F0 };
    private static final String[] VOW_NAME = { "I", "E", "A", "O", "U" };
    private static final String[] DIR_WORD = { "UP", "DOWN", "LEFT", "RIGHT" };

    private PluginPaint bg, cellP, txt, txtDim, foodP, headP, bodyP, meter;

    private void initPaints(PluginCanvas c) {
        bg = c.newPaint(); cellP = c.newPaint(); txt = c.newPaint();
        txtDim = c.newPaint(); foodP = c.newPaint(); headP = c.newPaint();
        bodyP = c.newPaint(); meter = c.newPaint();
    }

    private void draw(PluginCanvas canvas, int width, int height) {
        float W = width, H = height;
        bg.setColor(COL_BG).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, 0, W, H, bg);

        // Board background + subtle grid.
        float bw = cellPx * cols, bh = cellPx * rows;
        cellP.setColor(COL_BOARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(boardX0, boardY0, boardX0 + bw, boardY0 + bh, 6f, cellP);
        cellP.setColor(COL_GRID).setStyle(PluginStyle.STROKE).setStrokeWidth(0.6f);
        for (int x = 1; x < cols; x++) {
            float gx = boardX0 + x * cellPx;
            canvas.drawLine(gx, boardY0, gx, boardY0 + bh, cellP);
        }
        for (int y = 1; y < rows; y++) {
            float gy = boardY0 + y * cellPx;
            canvas.drawLine(boardX0, gy, boardX0 + bw, gy, cellP);
        }
        cellP.setColor(COL_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
        canvas.drawRoundRect(boardX0, boardY0, boardX0 + bw, boardY0 + bh, 6f, cellP);

        // Food (pulsing).
        if (foodCell >= 0) {
            float fx = boardX0 + (cellX(foodCell) + 0.5f) * cellPx;
            float fy = boardY0 + (cellY(foodCell) + 0.5f) * cellPx;
            float pulse = 0.30f + 0.08f * (float) Math.sin(lastMs * 0.006);
            foodP.setColor(COL_FOOD).setStyle(PluginStyle.FILL);
            canvas.drawCircle(fx, fy, cellPx * (0.30f + pulse * 0.10f), foodP);
        }

        // Snake.
        if (segCell != null && len > 0) {
            int i = qTail;
            int n = 0;
            while (true) {
                int c = segCell[i];
                boolean head = (i == qHead);
                float x0 = boardX0 + cellX(c) * cellPx;
                float y0 = boardY0 + cellY(c) * cellPx;
                float inset = cellPx * 0.10f;
                int col = head ? COL_HEAD : COL_BODY;
                if (boosting && !head) col = mix(COL_BODY, COL_BOOST, 0.45f);
                bodyP.setColor(col).setStyle(PluginStyle.FILL);
                canvas.drawRoundRect(x0 + inset, y0 + inset,
                        x0 + cellPx - inset, y0 + cellPx - inset, cellPx * 0.25f, bodyP);
                if (head) drawEyes(canvas, x0, y0);
                if (i == qHead) break;
                i = (i + 1) % (cap + 1);
                if (++n > cap) break;
            }
        }

        drawHud(canvas, W);
        drawLegend(canvas, W, H);

        if (state == READY) drawCenter(canvas, W, H, "VOWEL SNAKE",
                "Sing  I↑  A↓  E←  U→   •   O = boost", COL_HEAD);
        else if (state == OVER) drawCenter(canvas, W, H, "GAME OVER",
                "score " + score + "   •   sing or tap to retry", COL_FOOD);
    }

    private void drawEyes(PluginCanvas canvas, float x0, float y0) {
        float cs = cellPx;
        float ex = x0 + cs * 0.5f, ey = y0 + cs * 0.5f;
        float off = cs * 0.16f;
        float ax = DX[curDir] * cs * 0.12f, ay = DY[curDir] * cs * 0.12f;
        headP.setColor(0xFF0E0F12).setStyle(PluginStyle.FILL);
        float perpX = (curDir == LEFT || curDir == RIGHT) ? 0 : off;
        float perpY = (curDir == LEFT || curDir == RIGHT) ? off : 0;
        canvas.drawCircle(ex - perpX + ax, ey - perpY + ay, cs * 0.08f, headP);
        canvas.drawCircle(ex + perpX + ax, ey + perpY + ay, cs * 0.08f, headP);
    }

    private void drawHud(PluginCanvas canvas, float W) {
        txt.setColor(COL_TEXT).setTextSize(15f).setTextAlign(0);
        canvas.drawText("★ " + score, 12f, 26f, txt);
        txtDim.setColor(COL_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("best " + best, 12f, 38f, txtDim);

        // Current vowel chip (centre).
        if (curVowel >= 0) {
            int vc = VOW_COL[curVowel];
            txt.setColor(vc).setTextSize(16f).setTextAlign(1);
            String label = curVowel == V_O ? "O  BOOST"
                    : VOW_NAME[curVowel] + "  " + DIR_WORD[vowelDir(curVowel)];
            canvas.drawText(label, W * 0.5f, 26f, txt);
        } else {
            txtDim.setColor(COL_DIM).setTextSize(12f).setTextAlign(1);
            canvas.drawText(voiceLevel > 0.012f ? "…" : "sing a vowel", W * 0.5f, 26f, txtDim);
        }

        // Boost meter (right).
        float mx1 = W - 12f, mx0 = W - 92f, my = 20f, mh = 8f;
        meter.setColor(COL_GRID).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(mx0, my, mx1, my + mh, 4f, meter);
        meter.setColor(boosting ? 0xFFFFC061 : COL_BOOST).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(mx0, my, mx0 + (mx1 - mx0) * boost, my + mh, 4f, meter);
        txtDim.setColor(COL_DIM).setTextSize(8f).setTextAlign(2);
        canvas.drawText("BOOST (O)", mx1, my + mh + 9f, txtDim);
    }

    private int vowelDir(int vowel) {
        switch (vowel) {
            case V_I: return UP;
            case V_A: return DOWN;
            case V_E: return LEFT;
            default:  return RIGHT; // V_U
        }
    }

    private void drawLegend(PluginCanvas canvas, float W, float H) {
        String[] parts = { "I up", "A dn", "E lf", "U rt", "O boost" };
        int[] idx = { V_I, V_A, V_E, V_U, V_O };
        float total = W - 16f;
        float seg = total / parts.length;
        for (int i = 0; i < parts.length; i++) {
            txtDim.setColor(VOW_COL[idx[i]]).setTextSize(9f).setTextAlign(1);
            canvas.drawText(parts[i], 8f + seg * (i + 0.5f), H - 8f, txtDim);
        }
    }

    private void drawCenter(PluginCanvas canvas, float W, float H,
                            String title, String sub, int col) {
        bg.setColor(0xCC0E0F12).setStyle(PluginStyle.FILL);
        canvas.drawRect(0, HUD_TOP, W, H - LEGEND_BOT, bg);
        txt.setColor(col).setTextSize(26f).setTextAlign(1);
        canvas.drawText(title, W * 0.5f, H * 0.46f, txt);
        txtDim.setColor(COL_TEXT).setTextSize(12f).setTextAlign(1);
        canvas.drawText(sub, W * 0.5f, H * 0.46f + 24f, txtDim);
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
