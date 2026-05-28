package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.gamekit.Ease;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;
import com.vocalmonitor.plugin.gamekit.audio.NoteName;
import com.vocalmonitor.plugin.gamekit.audio.PitchTracker;

import java.util.Map;

/**
 * Interval Trainer — ear-training + pitch-jump trainer.
 *
 * The app plays a root note, then a second note an interval above it,
 * then asks the singer to <em>sing the second note back</em>.  Where
 * Scale Runner drills single notes, this drills the <em>relationship</em>
 * between two notes — the next skill after note-matching and the
 * foundation of singing in tune against accompaniment.
 *
 * <p>Flow (a tiny state machine, all timed off {@code timeMs}):
 * <ol>
 *   <li><b>ROOT</b> — the root tone sounds (~1 s) and lights up on the
 *       interval ladder.</li>
 *   <li><b>INTERVAL</b> — the target tone sounds (~1 s) and lights up a
 *       rung higher, labelled with the interval name (m3, P5, …).</li>
 *   <li><b>LISTEN</b> — both tones go quiet; a live tuner needle shows
 *       how close the singer's pitch is to the target.  Hold within
 *       ±{@code tolerance} cents for {@code holdNeeded} seconds to score
 *       a hit.</li>
 *   <li><b>RESULT</b> — a brief "Nailed it!" / "+1" celebration (or a
 *       gentle "close — try again"), then a new interval is chosen and
 *       the cycle repeats.</li>
 * </ol>
 *
 * <p>The reference tones are synthesised in {@link #process} so they
 * play through the monitor / at save-time; the live tuner reads the
 * mic from {@code streams["waveform"]} in {@link #render} (slim's live
 * monitor path).  A {@code mode} parameter picks the interval pool:
 * ascending only, descending, or a mixed set; {@code root} sets the
 * tonal centre so the exercise sits in the singer's comfortable range.
 */
public final class IntervalTrainer extends GamePluginBase {

    private final PitchTracker pitch = new PitchTracker();

    // ── Parameters ──────────────────────────────────────────
    private float rootHz = 220f;       // A3 default tonal centre
    private float mode = 0f;           // 0 easy(P4,P5,P8) 1 thirds 2 all
    private float tolerance = 40f;     // cents window to count as a hit
    private float toneLevel = 0.18f;   // reference tone volume

    // ── Interval pools (semitone offsets) ───────────────────
    private static final int[] POOL_EASY   = { 5, 7, 12 };          // P4 P5 P8
    private static final int[] POOL_THIRDS = { 3, 4, 7 };           // m3 M3 P5
    private static final int[] POOL_ALL    = { 1,2,3,4,5,7,9,12 };  // mixed

    // ── State machine ───────────────────────────────────────
    private static final int ST_ROOT = 0, ST_INTERVAL = 1, ST_LISTEN = 2, ST_RESULT = 3;
    private int state = ST_ROOT;
    private float stateT = 0f;          // seconds in current state
    private int curSemitones = 7;       // chosen interval
    private float targetHz = 330f;      // root * 2^(semis/12)
    private float holdAccum = 0f;       // time held in-tune
    private boolean lastHitGood = false;

    // ── Score ───────────────────────────────────────────────
    private int correct = 0;
    private int attempts = 0;
    private int streak = 0;
    private int bestStreak = 0;

    // ── Tone oscillator ─────────────────────────────────────
    private double tonePhase = 0.0;

    // ── timings ─────────────────────────────────────────────
    private static final float ROOT_S = 1.0f, INTERVAL_S = 1.1f, RESULT_S = 1.3f;
    private static final float HOLD_NEEDED = 0.6f;
    private static final float LISTEN_TIMEOUT_S = 9f;

    // smoothed needle
    private float needleCents = 0f;

    // ── PRNG ────────────────────────────────────────────────
    private long rng = 0x1234ABCD9876L;
    private int nextInt(int n) {
        rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
        return (int) ((rng & 0x7FFFFFFFL) % n);
    }

    @Override
    protected void onInit(int sr) {
        pitch.setSampleRate(sr).lpCutoff(800f).floor(0.007f).reset();
        state = ST_ROOT; stateT = 0f; holdAccum = 0f;
        correct = attempts = streak = bestStreak = 0;
        tonePhase = 0.0; needleCents = 0f;
        chooseInterval();
    }

    private int[] pool() {
        int m = Math.round(mode);
        if (m <= 0) return POOL_EASY;
        if (m == 1) return POOL_THIRDS;
        return POOL_ALL;
    }

    private void chooseInterval() {
        int[] p = pool();
        curSemitones = p[nextInt(p.length)];
        targetHz = rootHz * (float) Math.pow(2.0, curSemitones / 12.0);
    }

    // ── Parameters API ──────────────────────────────────────
    @Override public String[] parameterNames() {
        return new String[] { "root", "mode", "tolerance", "toneLevel" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "root": return 110f;
            case "tolerance": return 20f;
            default: return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "root": return 440f;
            case "mode": return 2f;
            case "tolerance": return 70f;
            case "toneLevel": return 0.5f;
            default: return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "root": return 220f;
            case "mode": return 0f;
            case "tolerance": return 40f;
            case "toneLevel": return 0.18f;
            default: return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "root": return "Root Hz";
            case "mode": return "Pool (0 easy/1 3rds/2 all)";
            case "tolerance": return "Tolerance ¢";
            case "toneLevel": return "Tone vol";
            default: return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "root": rootHz = v; targetHz = rootHz * (float) Math.pow(2.0, curSemitones / 12.0); break;
            case "mode": mode = v; break;
            case "tolerance": tolerance = v; break;
            case "toneLevel": toneLevel = v; break;
        }
    }

    /** Which reference tone (if any) should be sounding right now. */
    private float soundingHz() {
        if (state == ST_ROOT) return rootHz;
        if (state == ST_INTERVAL) return targetHz;
        return 0f;   // LISTEN / RESULT are silent
    }

    @Override
    public void process(float[] input, float[] output) {
        final double twoPi = 2.0 * Math.PI;
        float hz = soundingHz();
        int n = Math.min(input.length, output.length);
        if (hz <= 0f) {
            for (int i = 0; i < n; i++) output[i] = input[i];
            return;
        }
        double inc = twoPi * hz / sampleRate;
        for (int i = 0; i < n; i++) {
            float tone = (float) (Math.sin(tonePhase) * toneLevel);
            tonePhase += inc;
            if (tonePhase > twoPi) tonePhase -= twoPi;
            output[i] = (float) Math.tanh(tone + input[i]);
        }
    }

    @Override
    public void onTouchDown(float x, float y) {
        // tap during RESULT (or any time) skips to the next interval
        if (state == ST_RESULT || state == ST_LISTEN) {
            nextRound(false);
        }
    }

    private void nextRound(boolean scored) {
        chooseInterval();
        state = ST_ROOT; stateT = 0f; holdAccum = 0f;
    }

    private String intervalName(int s) {
        switch (s) {
            case 1: return "m2"; case 2: return "M2"; case 3: return "m3";
            case 4: return "M3"; case 5: return "P4"; case 6: return "TT";
            case 7: return "P5"; case 8: return "m6"; case 9: return "M6";
            case 10: return "m7"; case 11: return "M7"; case 12: return "P8";
            default: return s + "st";
        }
    }

    // ── Render ──────────────────────────────────────────────
    @Override
    public void render(PluginCanvas c, int w, int h, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        beginFrame(w, h, timeMs, streams);
        pitch.feed(streams, dt);
        stateT += dt;

        // ── advance the state machine ──
        if (state == ST_ROOT && stateT >= ROOT_S) { state = ST_INTERVAL; stateT = 0f; }
        else if (state == ST_INTERVAL && stateT >= INTERVAL_S) { state = ST_LISTEN; stateT = 0f; holdAccum = 0f; }
        else if (state == ST_LISTEN) {
            boolean voiced = pitch.voiced();
            float cents = voiced ? pitch.centsFrom(targetHz) : 999f;
            needleCents += 0.3f * ((voiced ? Ease.clamp(cents, -120f, 120f) : 0f) - needleCents);
            if (voiced && Math.abs(cents) <= tolerance) holdAccum += dt;
            else holdAccum = Math.max(0f, holdAccum - dt * 0.6f);
            if (holdAccum >= HOLD_NEEDED) {
                // HIT
                lastHitGood = true; attempts++; correct++; streak++;
                if (streak > bestStreak) bestStreak = streak;
                state = ST_RESULT; stateT = 0f;
                juice.flash(0.18f, Palette.ACCENT_GREEN);
                juice.scorePop("+1", w * 0.5f, h * 0.42f, Palette.ACCENT_GREEN);
                particles.burst(w * 0.5f, h * 0.42f, 20, Palette.ACCENT_GREEN);
                particles.burst(w * 0.5f, h * 0.42f, 10, Palette.SPARKLE);
            } else if (stateT >= LISTEN_TIMEOUT_S) {
                lastHitGood = false; attempts++; streak = 0;
                state = ST_RESULT; stateT = 0f;
            }
        } else if (state == ST_RESULT && stateT >= RESULT_S) {
            nextRound(lastHitGood);
        }

        // Sound the active reference tone via the host. On slim's live
        // monitor the host only calls render() (never process()), so the
        // tone synthesised in process() would never be heard — playTone()
        // routes it to the host's audio output instead. Silent in LISTEN /
        // RESULT (soundingHz() returns 0).
        if (host != null) {
            float toneHz = soundingHz();
            host.playTone(toneHz, toneHz > 0f ? toneLevel : 0f);
        }

        // ── background ──
        Gfx.gradientSky(c, w, h, 0xFF0C1322, 0xFF141B2E);
        c.save();
        juice.applyShake(c);

        float cx = w * 0.5f;
        Gfx.textCenter(c, "Interval Trainer", cx, 22f * scale, 20f * scale, Palette.UI_TEXT);

        // mode label
        String modeLbl = Math.round(mode) == 0 ? "Perfect intervals"
                       : Math.round(mode) == 1 ? "Thirds & fifths" : "Mixed intervals";
        Gfx.textCenter(c, modeLbl, cx, 40f * scale, 12f * scale, Palette.UI_TEXT_DIM);

        // ── interval ladder ──
        // Two rungs: root (lower) and target (higher). We place them on a
        // vertical staff so the *visual jump* matches the musical jump.
        float ladderX = cx;
        float ladderTop = h * 0.26f, ladderBot = h * 0.66f;
        float ladderH = ladderBot - ladderTop;
        // normalise the interval to a 0..1 height inside the ladder
        float maxSemi = 12f;
        float rootY = ladderBot;
        float targY = ladderBot - (curSemitones / maxSemi) * ladderH;

        // staff guide ticks at each semitone
        PluginPaint tick = c.newPaint();
        tick.setColor(0x18FFFFFF); tick.setStyle(PluginStyle.STROKE); tick.setStrokeWidth(1f);
        for (int s = 0; s <= 12; s++) {
            float y = ladderBot - (s / maxSemi) * ladderH;
            c.drawLine(cx - w * 0.30f, y, cx + w * 0.30f, y, tick);
        }
        // connector line root → target
        PluginPaint conn = c.newPaint();
        conn.setColor(0x55FFFFFF); conn.setStyle(PluginStyle.STROKE); conn.setStrokeWidth(2.5f * scale);
        c.drawLine(ladderX, rootY, ladderX, targY, conn);

        boolean rootLit   = (state == ST_ROOT);
        boolean targetLit = (state == ST_INTERVAL)
                          || (state == ST_RESULT && lastHitGood);

        // root node
        drawNode(c, ladderX, rootY, NoteName.of(rootHz), "root",
            rootLit ? Palette.ACCENT_BLUE : 0xFF3A4658, rootLit);
        // target node
        int targCol = state == ST_RESULT
            ? (lastHitGood ? Palette.ACCENT_GREEN : Palette.ACCENT_RED)
            : Palette.ACCENT_AMBER;
        drawNode(c, ladderX, targY,
            NoteName.of(targetHz), intervalName(curSemitones), targCol,
            targetLit || state == ST_LISTEN || state == ST_RESULT);

        // big interval name (focal point) — sits in the header gap above
        // the ladder so it never collides with the title / mode label.
        Gfx.textCenter(c, intervalName(curSemitones), cx, 66f * scale, 26f * scale,
            targetLit ? targCol : Palette.UI_TEXT);

        // ── phase-specific lower panel ──
        float panelY = h * 0.78f;
        if (state == ST_ROOT) {
            Gfx.textCenter(c, "Listen — root note", cx, panelY, 18f * scale, Palette.ACCENT_BLUE);
            drawSpeaker(c, cx, panelY + 28f * scale, scale, Palette.ACCENT_BLUE, stateT);
        } else if (state == ST_INTERVAL) {
            Gfx.textCenter(c, "…and the target", cx, panelY, 18f * scale, Palette.ACCENT_AMBER);
            drawSpeaker(c, cx, panelY + 28f * scale, scale, Palette.ACCENT_AMBER, stateT);
        } else if (state == ST_LISTEN) {
            // tuner needle vs target
            drawTuner(c, w, h, cx, panelY, needleCents, pitch.voiced(), holdAccum);
        } else { // RESULT
            String msg = lastHitGood ? "Nailed it!" : "Close — try again";
            int col = lastHitGood ? Palette.ACCENT_GREEN : Palette.ACCENT_AMBER;
            Gfx.textCenter(c, msg, cx, panelY + 6f * scale, 24f * scale, col);
        }

        c.restore();

        // ── score HUD ──
        Gfx.textLeft(c, "STREAK", 14f * scale, h * 0.955f - 18f * scale, 11f * scale, Palette.UI_TEXT_DIM);
        Gfx.textLeft(c, streak + "", 14f * scale, h * 0.955f, 22f * scale,
            streak > 0 ? Palette.ACCENT_GREEN : Palette.UI_TEXT_DIM);
        int pct = attempts > 0 ? Math.round(100f * correct / attempts) : 0;
        Gfx.textRight(c, "ACCURACY", w - 14f * scale, h * 0.955f - 18f * scale, 11f * scale, Palette.UI_TEXT_DIM);
        Gfx.textRight(c, pct + "%", w - 14f * scale, h * 0.955f, 22f * scale, Palette.UI_TEXT);

        particles.draw(c);
        juice.drawOverlay(c, w, h);
    }

    /** A labelled note bubble on the ladder. */
    private void drawNode(PluginCanvas c, float x, float y, String note, String sub,
                          int color, boolean lit) {
        float r = (lit ? 26f : 22f) * scale;
        if (lit) {
            PluginPaint glow = c.newPaint();
            glow.setColor(color); glow.setGlow(color, 18f * scale);
            c.drawCircle(x, y, r * 0.6f, glow);
        }
        Gfx.strokeCircle(c, x, y, r,
            Palette.withAlpha(color, lit ? 0.85f : 0.35f),
            lit ? 0xFFFFFFFF : 0x66FFFFFF, 2f * scale);
        Gfx.textCenter(c, note, x, y + 6f * scale, 18f * scale,
            lit ? Palette.UI_TEXT_INK : Palette.UI_TEXT);
        Gfx.textCenter(c, sub, x + r + 22f * scale, y + 5f * scale, 13f * scale, Palette.UI_TEXT_DIM);
    }

    /** Animated speaker pulse while a tone plays. */
    private void drawSpeaker(PluginCanvas c, float x, float y, float scale, int color, float t) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 10.0);
        for (int i = 0; i < 3; i++) {
            float rr = (10f + i * 9f + pulse * 4f) * scale;
            Gfx.strokeCircle(c, x, y, rr, 0x00000000,
                Palette.withAlpha(color, 0.5f - i * 0.13f), 2f * scale);
        }
    }

    /** Live tuner: horizontal scale, needle from cents, hold progress. */
    private void drawTuner(PluginCanvas c, int w, int h, float cx, float baseY,
                           float cents, boolean voiced, float hold) {
        Gfx.textCenter(c, "Sing the target", cx, baseY - 14f * scale, 16f * scale, Palette.UI_TEXT);
        float gw = w * 0.78f, left = cx - gw / 2f, right = cx + gw / 2f;
        float gy = baseY + 14f * scale, th = 12f * scale;
        // track
        PluginPaint bar = c.newPaint(); bar.setColor(0xFF222A38);
        c.drawRoundRect(left, gy - th, right, gy + th, 7f * scale, bar);
        // in-tune band (±tolerance on a ±100¢ scale)
        PluginPaint band = c.newPaint(); band.setColor(0x3366DD66);
        float bandHalf = gw * (tolerance / 200f);
        c.drawRoundRect(cx - bandHalf, gy - th, cx + bandHalf, gy + th, 7f * scale, band);
        // ticks
        PluginPaint tk = c.newPaint(); tk.setColor(0x66888888);
        for (int ct = -100; ct <= 100; ct += 25) {
            float tx = cx + gw * (ct / 200f);
            c.drawLine(tx, gy - th - 3f, tx, gy - th - (ct == 0 ? 14f : 8f) * scale, tk);
        }
        if (voiced) {
            float cc = Ease.clamp(cents, -110f, 110f);
            float nx = cx + gw * (cc / 200f);
            boolean inTune = Math.abs(cents) <= tolerance;
            int col = inTune ? Palette.ACCENT_GREEN
                    : (Math.abs(cents) < tolerance * 2 ? Palette.ACCENT_AMBER : Palette.ACCENT_RED);
            PluginPaint needle = c.newPaint();
            needle.setColor(col); needle.setGlow(col, 14f * scale);
            c.drawRoundRect(nx - 4f * scale, gy - th - 14f * scale,
                nx + 4f * scale, gy + th + 14f * scale, 4f * scale, needle);
            // hold progress ring under the label
            float hp = Ease.clamp(hold / HOLD_NEEDED, 0f, 1f);
            PluginPaint hpBar = c.newPaint(); hpBar.setColor(Palette.ACCENT_GREEN);
            c.drawRoundRect(left, gy + th + 16f * scale, left + gw * hp, gy + th + 22f * scale, 3f * scale, hpBar);
            String lbl = inTune ? "Hold it…" : ((cents > 0 ? "+" : "") + Math.round(cents) + "¢");
            Gfx.textCenter(c, lbl, cx, gy + th + 44f * scale, 15f * scale, col);
        } else {
            Gfx.textCenter(c, "…", cx, gy + th + 44f * scale, 15f * scale, Palette.UI_TEXT_DIM);
        }
    }
}
