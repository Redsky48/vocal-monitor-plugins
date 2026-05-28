package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.gamekit.Ease;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;
import com.vocalmonitor.plugin.gamekit.audio.NoteName;
import com.vocalmonitor.plugin.gamekit.audio.PitchTracker;

import java.util.Map;

/**
 * Siren Slide — glissando / "siren" pitch-tracking trainer.
 *
 * A smooth target line sweeps up and down across the vocal range — the
 * vocal pedagogue's #1 warm-up for blending registers (chest → mix →
 * head) without a break.  The singer slides their voice to ride the
 * line; their live pitch is a glowing puck that leaves a coloured
 * comet trail, green where they hug the line and red where they fall
 * off it.
 *
 * <p>The target is a continuous curve scrolling right-to-left.  The
 * "now" line sits at a fixed x (≈ 32% from the left), so the part of
 * the curve to the left is history (with the user's trail drawn over
 * it) and the part to the right is the slide that's coming up — the
 * singer can see the next swoop and prepare for it.
 *
 * <p>Scoring: per-frame cents-error between the user's pitch and the
 * target at the now-line.  Inside ±{@code tolerance} cents counts as
 * "on", and the running on-target percentage is the headline score.
 * The trail and puck recolour live so feedback is immediate, not
 * post-hoc.
 *
 * <p>Parameters let the user pick the slide's range and speed; the
 * range is expressed as a low/high note so it adapts to the singer's
 * own comfortable tessitura instead of one-size-fits-all.
 *
 * Audio passes through unchanged (see {@link GamePluginBase}).
 */
public final class SirenSlide extends GamePluginBase {

    private final PitchTracker pitch = new PitchTracker();

    // ── Parameters ──────────────────────────────────────────
    private float lowHz = 130.81f;    // C3
    private float highHz = 392.00f;   // G4
    private float speed = 0.5f;       // 0..1 → slide period
    private float tolerance = 35f;    // cents window counted as "on"

    // ── Target curve phase ──────────────────────────────────
    private float phase = 0f;         // radians, advances with time

    // ── User trail (ring buffer of recent points) ───────────
    private static final int TRAIL = 90;
    private final float[] trailHz = new float[TRAIL];   // 0 = gap
    private final float[] trailErr = new float[TRAIL];  // abs cents at sample
    private int trailHead = 0;
    private float sampleAccum = 0f;

    // ── Score ───────────────────────────────────────────────
    private float onPct = 0f;         // smoothed on-target %
    private float framesScored = 0f;
    private float framesOn = 0f;
    private float comboS = 0f;        // current on-target streak (s)
    private float bestComboS = 0f;

    @Override
    protected void onInit(int sr) {
        pitch.setSampleRate(sr).lpCutoff(800f).floor(0.007f).reset();
        phase = 0f;
        trailHead = 0; sampleAccum = 0f;
        for (int i = 0; i < TRAIL; i++) { trailHz[i] = 0f; trailErr[i] = 999f; }
        onPct = 0f; framesScored = framesOn = 0f;
        comboS = 0f; bestComboS = 0f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "lowHz", "highHz", "speed", "tolerance" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "lowHz":  return 80f;
            case "highHz": return 200f;
            case "speed":  return 0f;
            case "tolerance": return 15f;
            default: return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "lowHz":  return 400f;
            case "highHz": return 900f;
            case "speed":  return 1f;
            case "tolerance": return 80f;
            default: return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "lowHz":  return 130.81f;
            case "highHz": return 392.00f;
            case "speed":  return 0.5f;
            case "tolerance": return 35f;
            default: return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "lowHz":  return "Low note";
            case "highHz": return "High note";
            case "speed":  return "Slide speed";
            case "tolerance": return "Tolerance ¢";
            default: return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "lowHz":  lowHz = v; break;
            case "highHz": highHz = v; break;
            case "speed":  speed = v; break;
            case "tolerance": tolerance = v; break;
        }
    }

    // ── Target curve: smooth sine sweep between low & high, in
    //    log-frequency space so it sounds like an even glissando. ──
    /** Returns target Hz at a phase offset (radians) from "now". */
    private float targetHzAt(float ph) {
        float lo = (float) Math.log(Math.max(40f, lowHz));
        float hi = (float) Math.log(Math.max(lowHz + 1f, highHz));
        // raised-cosine 0..1; smooth turns at the extremes
        float u = 0.5f - 0.5f * (float) Math.cos(ph);
        // ease the turnarounds a touch for a more vocal feel
        u = Ease.inOutSine(u);
        return (float) Math.exp(lo + (hi - lo) * u);
    }

    private float periodS() {
        // speed 0 → 9s slow, speed 1 → 2.2s brisk
        return Ease.lerp(9f, 2.2f, Ease.clamp(speed, 0f, 1f));
    }

    /** Map a Hz to a y (top = high pitch). Adds 1 semitone of head-room
     *  above/below the range so the line never clips the edges. */
    private float hzToY(float hz, float top, float bot) {
        float lo = (float) Math.log(Math.max(40f, lowHz));
        float hi = (float) Math.log(Math.max(lowHz + 1f, highHz));
        float pad = (hi - lo) * 0.10f;
        lo -= pad; hi += pad;
        float u = (float) ((Math.log(Math.max(20f, hz)) - lo) / (hi - lo));
        u = Ease.clamp(u, 0f, 1f);
        return bot - u * (bot - top);   // high pitch → small y
    }

    @Override
    public void onTouchDown(float x, float y) {
        // reset the running score for a fresh run
        framesScored = framesOn = 0f; onPct = 0f;
        comboS = 0f; bestComboS = 0f;
    }

    @Override
    public void render(PluginCanvas c, int w, int h, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        beginFrame(w, h, timeMs, streams);
        pitch.feed(streams, dt);

        // advance the slide
        float omega = (float) (2.0 * Math.PI) / periodS();
        phase += omega * dt;

        float top = h * 0.18f, bot = h * 0.86f;
        float nowX = w * 0.32f;

        // background
        Gfx.gradientSky(c, w, h, 0xFF0B1020, 0xFF12182E);

        c.save();
        juice.applyShake(c);

        // ── staff guide lines at each semitone of the range ──
        PluginPaint guide = c.newPaint();
        guide.setColor(0x14FFFFFF); guide.setStyle(PluginStyle.STROKE); guide.setStrokeWidth(1f);
        int loM = Math.round(NoteName.midiOf(lowHz));
        int hiM = Math.round(NoteName.midiOf(highHz));
        for (int m = loM; m <= hiM; m++) {
            float y = hzToY(NoteName.hzOf(m), top, bot);
            c.drawLine(0, y, w, y, guide);
            // note label every octave (C)
            if (m % 12 == 0) {
                Gfx.textLeft(c, NoteName.ofMidi(m), 6f * scale, y - 3f * scale,
                    11f * scale, 0x55FFFFFF);
            }
        }

        // ── target curve: future (right of now) + past (left) ──
        // We draw the whole visible window as one poly-line, sampling
        // the analytic curve so it's perfectly smooth.
        PluginPath curve = c.newPath();
        int N = 120;
        float windowAheadS = periodS() * 0.9f;       // how far right we look
        float windowBackS  = periodS() * 0.55f;       // how far left (history)
        float xPerS = (w - nowX) / windowAheadS;       // px per second to the right
        boolean started = false;
        for (int i = 0; i <= N; i++) {
            float frac = (float) i / N;                // 0..1 across window
            float tRel = -windowBackS + frac * (windowBackS + windowAheadS);
            float px = nowX + tRel * xPerS;
            float ph = phase + omega * tRel;
            float y = hzToY(targetHzAt(ph), top, bot);
            if (!started) { curve.moveTo(px, y); started = true; }
            else curve.lineTo(px, y);
        }
        PluginPaint curveP = c.newPaint();
        curveP.setColor(0xFF3A7BD5); curveP.setStyle(PluginStyle.STROKE);
        curveP.setStrokeWidth(7f * scale); curveP.setGlow(0xFF3A7BD5, 9f * scale);
        c.drawPath(curve, curveP);
        // soft "future" dim overlay to the right of now so the past pops
        PluginPaint fut = c.newPaint(); fut.setColor(0x33000000);
        c.drawRect(nowX, 0, w, h, fut);

        // ── target marker dot at the now-line ──
        float targetY = hzToY(targetHzAt(phase), top, bot);
        Gfx.strokeCircle(c, nowX, targetY, 7f * scale, 0x00000000, 0xFFBFD8FF, 2.5f * scale);

        // ── now-line ──
        PluginPaint nowP = c.newPaint();
        nowP.setColor(0x66FFFFFF); nowP.setStyle(PluginStyle.STROKE); nowP.setStrokeWidth(1.5f * scale);
        c.drawLine(nowX, top - 8f * scale, nowX, bot + 8f * scale, nowP);

        // ── sample the user's pitch into the trail (fixed cadence) ──
        sampleAccum += dt;
        float sampleEvery = 0.025f;
        boolean voiced = pitch.voiced();
        float userY = 0f, errCents = 999f;
        if (voiced) {
            float tHz = targetHzAt(phase);
            errCents = Math.abs(NoteName.cents(pitch.hz(), tHz));
            userY = hzToY(pitch.hz(), top, bot);
        }
        while (sampleAccum >= sampleEvery) {
            sampleAccum -= sampleEvery;
            trailHz[trailHead] = voiced ? pitch.hz() : 0f;
            trailErr[trailHead] = voiced ? errCents : 999f;
            trailHead = (trailHead + 1) % TRAIL;
        }

        // ── draw the user trail as a comet to the LEFT of now ──
        // newest sample is just behind the now-line; older samples
        // recede left, fading.
        float trailDx = (w * 0.0f);  // (positions computed below)
        float pxPerSample = (nowX) / (TRAIL * 0.85f);
        float prevX = -1f, prevY = 0f;
        for (int k = 0; k < TRAIL; k++) {
            int idx = (trailHead - 1 - k + TRAIL * 2) % TRAIL;
            float hz = trailHz[idx];
            float px = nowX - k * pxPerSample;
            if (px < 0) break;
            if (hz <= 0f) { prevX = -1f; continue; }   // gap (silence)
            float y = hzToY(hz, top, bot);
            float a = Ease.clamp(1f - (float) k / TRAIL, 0.12f, 1f);
            int col = trailErr[idx] <= tolerance ? Palette.ACCENT_GREEN
                    : trailErr[idx] <= tolerance * 2f ? Palette.ACCENT_AMBER
                    : Palette.ACCENT_RED;
            if (prevX >= 0f) {
                PluginPaint seg = c.newPaint();
                seg.setColor(Palette.withAlpha(col, a));
                seg.setStyle(PluginStyle.STROKE); seg.setStrokeWidth(4.5f * scale);
                c.drawLine(prevX, prevY, px, y, seg);
            }
            prevX = px; prevY = y;
        }

        // ── user puck at the now-line ──
        if (voiced) {
            int puckCol = errCents <= tolerance ? Palette.ACCENT_GREEN
                        : errCents <= tolerance * 2f ? Palette.ACCENT_AMBER
                        : Palette.ACCENT_RED;
            Gfx.strokeCircle(c, nowX, userY, 10f * scale,
                Palette.withAlpha(puckCol, 0.9f), 0xFFFFFFFF, 2f * scale);
            PluginPaint glowP = c.newPaint();
            glowP.setColor(puckCol); glowP.setGlow(puckCol, 16f * scale);
            c.drawCircle(nowX, userY, 4f * scale, glowP);

            // running score
            framesScored += 1f;
            if (errCents <= tolerance) { framesOn += 1f; comboS += dt; if (comboS > bestComboS) bestComboS = comboS; }
            else comboS = 0f;
        } else {
            comboS = 0f;
        }
        float pct = framesScored > 0f ? (framesOn / framesScored) * 100f : 0f;
        onPct += 0.1f * (pct - onPct);

        c.restore();

        // ── HUD ──
        Gfx.textCenter(c, "Siren Slide", w / 2f, 36f * scale, 22f * scale, Palette.UI_TEXT);
        Gfx.textCenter(c,
            NoteName.of(lowHz) + " ↕ " + NoteName.of(highHz),
            w / 2f, 56f * scale, 13f * scale, Palette.UI_TEXT_DIM);

        // on-target % chip (top-left) and combo (top-right)
        int onCol = onPct > 70f ? Palette.ACCENT_GREEN
                  : onPct > 40f ? Palette.ACCENT_AMBER : Palette.ACCENT_RED;
        Gfx.textLeft(c, "ON-TARGET", 14f * scale, h * 0.95f - 18f * scale, 11f * scale, Palette.UI_TEXT_DIM);
        Gfx.textLeft(c, Math.round(onPct) + "%", 14f * scale, h * 0.95f, 24f * scale, onCol);
        Gfx.textRight(c, "STREAK", w - 14f * scale, h * 0.95f - 18f * scale, 11f * scale, Palette.UI_TEXT_DIM);
        Gfx.textRight(c, String.format("%.1fs", comboS), w - 14f * scale, h * 0.95f, 24f * scale,
            comboS > 0.5f ? Palette.ACCENT_GREEN : Palette.UI_TEXT_DIM);

        if (!voiced) {
            Gfx.textCenter(c, "Slide your voice along the line",
                w / 2f, h * 0.95f, 16f * scale, Palette.UI_TEXT_DIM);
        }

        particles.draw(c);
        juice.drawOverlay(c, w, h);
    }
}
